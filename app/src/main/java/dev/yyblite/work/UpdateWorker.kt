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
        UpdateNotifications.show(ctx, found.sortedBy { it.name.lowercase() })
        return Result.success()
    }
}
