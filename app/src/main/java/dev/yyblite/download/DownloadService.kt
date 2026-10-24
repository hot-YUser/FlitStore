package dev.yyblite.download

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import dev.yyblite.graph
import dev.yyblite.platform.Notifications

/** 只負責讓行程在下載期間保持前景；佇列與狀態都在 [DownloadManager]。 */
class DownloadService : Service() {

    @Volatile private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val manager = graph.downloads
        val notification = Notifications.downloads(this, manager.states.value)
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(this, Notifications.ID_DOWNLOADS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(Notifications.ID_DOWNLOADS, notification)
        }
        val request = intent?.let(DownloadRequest::from)
        if (request != null) manager.run(request) else if (!manager.hasActive()) stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var instance: DownloadService? = null

        fun refresh(states: Map<String, DownloadState>) {
            val service = instance ?: return
            if (states.values.none { it.active }) return
            runCatching {
                NotificationManagerCompat.from(service)
                    .notify(Notifications.ID_DOWNLOADS, Notifications.downloads(service, states))
            }
        }

        /** 只在沒有更新的啟動請求時才真正停止（stopSelfResult 以最後的 startId 判斷）。 */
        fun stopWhenIdle() {
            val service = instance ?: return
            ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
            service.stopSelfResult(service.lastStartId)
        }
    }
}
