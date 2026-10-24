package dev.yyblite.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.yyblite.core.store.StoreApp
import dev.yyblite.data.ErrorLog
import dev.yyblite.graph
import dev.yyblite.platform.Notifications
import dev.yyblite.ui.image.ThumbCache
import java.util.concurrent.TimeUnit

/**
 * 每 3 小時的背景檢查。與前景共用同一個 [dev.yyblite.data.UpdateRepository]，
 * 所以版本回退、忽略、簽章不符的判定與畫面上完全一致。
 */
class UpdateWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val graph = ctx.graph
        ThumbCache.pruneIfDue(ctx)
        val offered = graph.updates.checkBlocking()
        Log.i("YYBLite", "worker done: ${offered.size} updates")

        val settings = graph.settings
        val targets = if (settings.autoUpdate) {
            offered.filter { settings.shouldAttemptAuto(it.pkg, it.versionName) }
        } else {
            emptyList()
        }
        val started = if (targets.isNotEmpty()) {
            // 背景啟動前景服務在 Android 12+ 受限；先讓 Worker 自己成為前景取得豁免。
            promoteToForeground()
            startAll(targets)
        } else {
            emptySet()
        }
        // 已自動接手的項目不再重複提示；啟動失敗的仍發「有更新」通知。
        Notifications.updatesFound(ctx, offered.filterNot { it.pkg in started })
        return Result.success()
    }

    private fun startAll(apps: List<StoreApp>): Set<String> {
        val graph = applicationContext.graph
        val started = HashSet<String>()
        for (app in apps) {
            try {
                if (graph.downloads.enqueueBlocking(app)) {
                    graph.settings.markAutoAttempt(app.pkg, app.versionName)
                    started += app.pkg
                }
            } catch (e: Exception) {
                ErrorLog.record("auto-update", "${app.pkg}: ${e.message}")
            }
        }
        Log.i("YYBLite", "auto update started ${started.size}/${apps.size}")
        return started
    }

    private fun promoteToForeground() {
        val notification = Notifications.worker(applicationContext)
        val info = if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(Notifications.ID_WORKER, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notifications.ID_WORKER, notification)
        }
        runCatching { setForegroundAsync(info).get(10, TimeUnit.SECONDS) }
            .onFailure { Log.w("YYBLite", "worker foreground promotion failed", it) }
    }

    companion object {
        private const val NAME = "yyb-update-check"

        fun schedule(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(3, TimeUnit.HOURS, 30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
