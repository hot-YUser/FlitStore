package dev.yyblite.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import dev.yyblite.MainActivity
import dev.yyblite.R
import dev.yyblite.data.StoreApp
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class DownloadService : Service() {

    companion object {
        private const val CHANNEL_ID = "dl"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_PKG = "pkg"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_VERSION = "version"
        private const val EXTRA_URL = "url"
        private const val EXTRA_MD5 = "md5"

        fun intent(ctx: Context, app: StoreApp): Intent =
            Intent(ctx, DownloadService::class.java)
                .putExtra(EXTRA_PKG, app.pkg)
                .putExtra(EXTRA_NAME, app.name)
                .putExtra(EXTRA_VERSION, app.versionName)
                .putExtra(EXTRA_URL, app.apkUrl)
                .putExtra(EXTRA_MD5, app.apkMd5)
    }

    private val active = AtomicInteger(0)
    private val downloader = SegmentDownloader(32)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pkg = intent?.getStringExtra(EXTRA_PKG) ?: return START_NOT_STICKY
        val name = intent.getStringExtra(EXTRA_NAME) ?: pkg
        val version = intent.getStringExtra(EXTRA_VERSION) ?: "0"
        val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
        val md5 = intent.getStringExtra(EXTRA_MD5) ?: ""

        active.incrementAndGet()
        startForegroundCompat(buildNotification(getString(R.string.notification_title), name, 0, -1))
        DownloadBus.publish(DownloadState(pkg, Phase.QUEUED))

        DownloadQueue.submit { runDownload(pkg, name, version, url, md5) }
        return START_NOT_STICKY
    }

    private fun runDownload(pkg: String, name: String, version: String, url: String, md5: String) {
        android.util.Log.i("YYBDL", "start $pkg $version $url")
        DownloadBus.publish(DownloadState(pkg, Phase.RUNNING))
        var total = -1L
        try {
            val dir = getExternalFilesDir("apk") ?: throw IOException("外部儲存空間不可用")
            if (!dir.exists() && !dir.mkdirs()) throw IOException("無法建立下載目錄")

            val target = File(dir, "${pkg}_${version}.apk")
            val part = File(dir, target.name + ".part")

            downloader.downloadFull(url, md5.ifBlank { null }, part) { done, size ->
                total = size
                DownloadBus.publish(DownloadState(pkg, Phase.RUNNING, done, size))
                notifyProgress(name, done, size)
            }

            if (target.exists() && !target.delete()) throw IOException("無法覆寫舊檔")
            if (!part.renameTo(target)) throw IOException("無法重新命名下載檔")

            android.util.Log.i("YYBDL", "done $pkg bytes=$total")
            DownloadBus.publish(DownloadState(pkg, Phase.DONE, total, total))
            notifyProgress(name, total, total)
        } catch (e: Exception) {
            android.util.Log.w("YYBDL", "fail $pkg: ${e.message}", e)
            DownloadBus.publish(
                DownloadState(pkg, Phase.FAILED, 0, total, e.message ?: e.javaClass.simpleName)
            )
            notifyFailure(name, e.message)
        } finally {
            if (active.decrementAndGet() == 0) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(title: String, appName: String, done: Long, total: Long): android.app.Notification {
        val percent = if (total > 0) ((done * 100) / total).toInt() else 0
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(appName)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, total <= 0)
            .setContentIntent(open)
            .build()
    }

    private fun notifyProgress(appName: String, done: Long, total: Long) {
        val notification = buildNotification(getString(R.string.notification_title), appName, done, total)
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // 通知權限被拒時靜默；下載與前景服務不受影響。
        }
    }

    private fun notifyFailure(appName: String, message: String?) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("下載失敗：$appName")
            .setContentText(message ?: "")
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // 同上。
        }
    }
}
