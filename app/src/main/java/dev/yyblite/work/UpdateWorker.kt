package dev.yyblite.work

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.yyblite.data.IgnoreStore
import dev.yyblite.data.InstalledScanner
import dev.yyblite.data.LookupResult
import dev.yyblite.data.StoreApp
import dev.yyblite.data.StoreClient
import dev.yyblite.data.UpdateCache
import dev.yyblite.data.UpdateChecker
import dev.yyblite.data.UpdateDecision
import dev.yyblite.install.ApkSignature
import dev.yyblite.ui.ThumbCache
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 背景更新檢查：掃描已安裝清單、逐一套件查詢商店版本、寫回快取，
 * 有可更新項目時發通知。與前景刷新共用同一份 UpdateCache 與忽略規則。
 */
class UpdateWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        ThumbCache.pruneIfDue(ctx)
        val installed = InstalledScanner.scan(ctx)
        android.util.Log.i("YYBLite", "worker start: ${installed.size} apps")
        if (installed.isEmpty()) return Result.success()

        val cache = UpdateCache(ctx)
        val ignoreStore = IgnoreStore(ctx)
        val cached = cache.load()
        val fingerprint = cache.fingerprint(installed)

        val toCheck = installed.filterNot { cache.isNotFoundFresh(it.pkg) }
        val found = Collections.synchronizedList(ArrayList<StoreApp>())
        val successes = Collections.synchronizedList(ArrayList<StoreApp>())
        val removed = Collections.synchronizedList(ArrayList<String>())
        val done = CountDownLatch(1)
        val checker = UpdateChecker(32)

        checker.check(
            toCheck,
            { app, result ->
                when (result) {
                    is LookupResult.Found -> {
                        val store = result.app
                        cache.clearNotFound(store.pkg)
                        successes.add(store)
                        val certOk = ApkSignature.matchesInstalled(ctx, store.signatureMd5, store.pkg)
                        if (certOk &&
                            UpdateDecision.needsUpdate(store, app.versionName, app.versionCode) &&
                            !ignoreStore.isIgnored(store.pkg, store.versionName, store.apkMd5)
                        ) {
                            found.add(store)
                        }
                    }
                    LookupResult.NotInStore -> {
                        removed.add(app.pkg)
                        cache.markNotFound(app.pkg)
                    }
                    is LookupResult.Failed -> Unit
                }
            },
            { done.countDown() }
        )

        done.await(15, TimeUnit.MINUTES)
        checker.shutdown()

        val merged = HashMap(cached)
        successes.forEach { merged[it.pkg] = it }
        removed.forEach { merged.remove(it) }
        cache.save(merged.values)
        cache.markChecked(fingerprint)

        android.util.Log.i("YYBLite", "worker done: ${found.size} updates")

        val auto = AutoUpdatePrefs(ctx)
        val autoTargets = if (auto.enabled) {
            found.filter { auto.shouldAttempt(it.pkg, it.versionName) }
                .sortedBy { it.name.lowercase() }
        } else {
            emptyList()
        }
        if (autoTargets.isNotEmpty()) {
            // 背景啟動前景服務在 Android 12+ 會受限；先讓 Worker 自己成為前景服務取得豁免。
            promoteToForeground()
            val started = AutoUpdate.start(ctx, autoTargets)
            // 已自動接手的項目不再重複發「有更新」通知；啟動失敗的仍提示使用者。
            UpdateNotifications.show(ctx, found.filterNot { it in started })
        } else {
            UpdateNotifications.show(ctx, found.sortedBy { it.name.lowercase() })
        }
        return Result.success()
    }

    private fun promoteToForeground() {
        val ctx = applicationContext
        val notification = androidx.core.app.NotificationCompat
            .Builder(ctx, "updates")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(ctx.getString(dev.yyblite.R.string.auto_update_title))
            .setContentText(ctx.getString(dev.yyblite.R.string.auto_update_running))
            .setOngoing(true)
            .build()
        val info = if (android.os.Build.VERSION.SDK_INT >= 29) {
            androidx.work.ForegroundInfo(
                2002, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            androidx.work.ForegroundInfo(2002, notification)
        }
        runCatching { setForegroundAsync(info).get(10, TimeUnit.SECONDS) }
            .onFailure { android.util.Log.w("YYBLite", "worker foreground promotion failed", it) }
    }
}
