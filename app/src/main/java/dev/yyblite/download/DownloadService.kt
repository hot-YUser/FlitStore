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
import dev.yyblite.data.ErrorLog
import dev.yyblite.data.StoreApp
import dev.yyblite.install.ApkSignature
import dev.yyblite.install.AutoInstall
import dev.yyblite.install.InstallNotification
import dev.yyblite.install.InstallOutcome
import dev.yyblite.install.InstallerEngine
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
        private const val EXTRA_ABI = "abi"
        private const val EXTRA_SIGNATURE = "signature"
        private const val EXTRA_SIZE = "size"

        fun intent(
            ctx: Context,
            app: StoreApp,
            url: String,
            abi: String,
            md5: String
        ): Intent =
            Intent(ctx, DownloadService::class.java)
                .putExtra(EXTRA_PKG, app.pkg)
                .putExtra(EXTRA_NAME, app.name)
                .putExtra(EXTRA_VERSION, app.versionName)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_MD5, md5)
                .putExtra(EXTRA_ABI, abi)
                .putExtra(EXTRA_SIGNATURE, app.signatureMd5)
                .putExtra(EXTRA_SIZE, app.sizeBytes)
    }

    private val active = AtomicInteger(0)
    private val downloader by lazy { SegmentDownloader(resolveSegments()) }
    private val installExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "dl-install").apply { isDaemon = true }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 除錯版可用 Settings.Global 的 yyb_host 強制 CDN 主機（例如 imtt2.dd.qq.com）。 */
    private fun forceHost(url: String): String {
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return url
        val host = runCatching {
            android.provider.Settings.Global.getString(contentResolver, "yyb_host")
        }.getOrNull().orEmpty()
        if (host.isBlank()) return url
        return url.replace(Regex("//imtt[0-9]*\\.dd\\.qq\\.com"), "//$host")
    }

    /** 除錯版可用 Settings.Global 的 yyb_segments 調整單檔分段數。 */
    private fun resolveSegments(): Int {
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return 0
        return runCatching {
            android.provider.Settings.Global.getInt(contentResolver, "yyb_segments", 0)
        }.getOrDefault(0).coerceIn(0, 512)
    }

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
        val abi = intent.getStringExtra(EXTRA_ABI) ?: "v7a"
        val signature = intent.getStringExtra(EXTRA_SIGNATURE)
        val sizeHint = intent.getLongExtra(EXTRA_SIZE, -1L)

        active.incrementAndGet()
        startForegroundCompat(buildNotification(getString(R.string.notification_title), name, 0, -1))
        DownloadBus.publish(DownloadState(pkg, Phase.QUEUED, version = version, abi = abi, sizeHint = sizeHint))

        DownloadQueue.submit { runDownload(pkg, name, version, url, md5, abi, signature, sizeHint) }
        return START_NOT_STICKY
    }

    private fun runDownload(
        pkg: String,
        name: String,
        version: String,
        url: String,
        md5: String,
        abi: String,
        expectedSignature: String?,
        sizeHint: Long
    ) {
        val startedAt = System.currentTimeMillis()
        val effectiveUrl = forceHost(url)
        android.util.Log.i("YYBDL", "start $pkg $version $effectiveUrl")
        DownloadBus.publish(DownloadState(pkg, Phase.RUNNING, version = version, abi = abi, sizeHint = sizeHint))
        var total = -1L
        try {
            val dir = getExternalFilesDir("apk") ?: throw IOException("外部儲存空間不可用")
            if (!dir.exists() && !dir.mkdirs()) throw IOException("無法建立下載目錄")

            val target = File(dir, "${pkg}_${version}_${abi}.apk")
            val part = File(dir, target.name + ".part")

            var lastBytes = 0L
            var lastTime = System.currentTimeMillis()
            var speed = 0.0
            downloader.downloadFull(effectiveUrl, md5.ifBlank { null }, part) { done, size ->
                total = size
                val now = System.currentTimeMillis()
                val dt = now - lastTime
                if (dt >= 300) {
                    val instant = (done - lastBytes) * 1000.0 / dt
                    speed = if (speed == 0.0) instant else speed * 0.6 + instant * 0.4
                    lastBytes = done
                    lastTime = now
                }
                DownloadBus.publish(
                    DownloadState(
                        pkg, Phase.RUNNING, done, size,
                        version = version, abi = abi, speedBps = speed.toLong(), sizeHint = sizeHint
                    )
                )
                notifyProgress(name, done, size)
            }

            if (target.exists() && !target.delete()) throw IOException("無法覆寫舊檔")
            if (!part.renameTo(target)) throw IOException("無法重新命名下載檔")
            android.util.Log.i(
                "YYBDL",
                "download done $pkg ${target.length()} bytes in ${System.currentTimeMillis() - startedAt}ms"
            )

            if (!expectedSignature.isNullOrBlank()) {
                val accepted = ApkSignature.certificateMd5s(this, target)
                if (accepted.isEmpty()) {
                    target.delete()
                    throw IOException("無法讀取 APK 簽章")
                }
                if (accepted.none { it.equals(expectedSignature, ignoreCase = true) }) {
                    target.delete()
                    throw IOException("簽章不符：商店 $expectedSignature / 檔案 $accepted")
                }
            }

            android.util.Log.i("YYBDL", "done $pkg bytes=$total")
            DownloadBus.publish(
                DownloadState(pkg, Phase.DONE, total, total, version = version, abi = abi, sizeHint = sizeHint)
            )
            notifyProgress(name, total, total)
            installExecutor.execute {
                try {
                    installDownloaded(target, pkg, name, version, abi)
                } finally {
                    finishActive()
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("YYBDL", "fail $pkg: ${e.message}", e)
            ErrorLog.record("download", "$pkg: ${e.message}")
            DownloadBus.publish(
                DownloadState(
                    pkg, Phase.FAILED, 0, total, e.message ?: e.javaClass.simpleName,
                    version, abi, 0, sizeHint
                )
            )
            notifyFailure(name, e.message)
            finishActive()
        }
    }

    private fun installDownloaded(target: File, pkg: String, name: String, version: String, abi: String) {
        val downgrade = InstallerEngine.downgradeInfo(this, listOf(target))
        if (downgrade != null) {
            // 商店頁以 versionName 判斷「較新」，但 APK 的 versionCode 可能更舊；
            // 這種情況直接刪檔並記錄，避免每次刷新都重抓。
            target.delete()
            dev.yyblite.data.UpdateCache(this).markRegression(pkg, version)
            DownloadBus.clear(pkg)
            android.util.Log.w(
                "YYBDL",
                "store regression $pkg: apk ${downgrade.targetCode} < installed ${downgrade.installedCode}"
            )
            ErrorLog.record(
                "regression",
                "$pkg: apk ${downgrade.targetCode} < installed ${downgrade.installedCode}"
            )
            InstallNotification.postFailure(
                this, name,
                getString(
                    R.string.store_regression,
                    downgrade.targetCode,
                    downgrade.installedCode
                )
            )
            return
        }
        when (val outcome = InstallerEngine.install(this, listOf(target))) {
            is InstallOutcome.Installed ->
                android.util.Log.i("YYBDL", "installed $pkg via ${outcome.via}")
            InstallOutcome.Handoff ->
                if (!AutoInstall.tryInstall(this, pkg, version, abi)) {
                    InstallNotification.post(this, pkg, name, version, abi)
                }
            is InstallOutcome.Failed -> {
                android.util.Log.w("YYBDL", "install failed via ${outcome.via}: ${outcome.message}")
                ErrorLog.record("install", "$pkg: ${outcome.message}")
                InstallNotification.postFailure(this, name, outcome.message)
            }
        }
    }

    private fun finishActive() {
        if (active.decrementAndGet() == 0) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
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

    override fun onDestroy() {
        installExecutor.shutdown()
        super.onDestroy()
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
