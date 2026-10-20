package dev.yyblite.install

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.yyblite.R
import java.io.File

/** 背景無法直接啟動安裝器（BAL）時的備援：一則可直接點開 InstallerX 的通知。 */
object InstallNotification {
    private const val CHANNEL_ID = "install"

    fun post(ctx: Context, pkg: String, name: String, version: String, abi: String) {
        val file = File(ctx.getExternalFilesDir("apk"), "${pkg}_${version}_${abi}.apk")
        if (!file.exists()) return

        val intent = InstallHandoff.buildIntent(ctx, file)
        if (intent.component == null) return

        val manager = ctx.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                ctx.getString(R.string.notification_install_channel),
                NotificationManager.IMPORTANCE_HIGH
            )
        )

        val pending = PendingIntent.getActivity(
            ctx,
            pkg.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(ctx.getString(R.string.notification_ready, name))
            .setContentText(ctx.getString(R.string.notification_ready_hint, version))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        try {
            NotificationManagerCompat.from(ctx).notify(pkg.hashCode(), notification)
        } catch (_: SecurityException) {
            // 通知權限被拒時靜默；檔案仍在，使用者可從列表手動安裝。
        }
    }
}
