package dev.yyblite.platform

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.yyblite.MainActivity
import dev.yyblite.R
import dev.yyblite.core.store.StoreApp
import dev.yyblite.download.DownloadPhase
import dev.yyblite.download.DownloadState
import dev.yyblite.install.InstallHandoff
import dev.yyblite.ui.Format
import java.io.File

/** 所有通知集中在這裡：頻道、ID、內容。權限被拒時一律靜默，不影響下載與安裝。 */
object Notifications {
    private const val CHANNEL_DOWNLOADS = "dl"
    private const val CHANNEL_INSTALL = "install"
    private const val CHANNEL_UPDATES = "updates"

    const val ID_DOWNLOADS = 1001
    const val ID_UPDATES = 2001
    const val ID_WORKER = 2002

    fun ensureChannels(ctx: Context) {
        NotificationManagerCompat.from(ctx).createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CHANNEL_DOWNLOADS, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(ctx.getString(R.string.channel_downloads)).build(),
                NotificationChannelCompat.Builder(CHANNEL_INSTALL, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName(ctx.getString(R.string.channel_install)).build(),
                NotificationChannelCompat.Builder(CHANNEL_UPDATES, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName(ctx.getString(R.string.channel_updates)).build()
            )
        )
    }

    /** 下載中的摘要：幾個項目、總進度、總速度；點一下回到「更新」分頁。 */
    fun downloads(ctx: Context, states: Map<String, DownloadState>): Notification {
        val running = states.values.filter { it.active }
        val downloading = running.filter { it.phase == DownloadPhase.DOWNLOADING }
        val total = downloading.sumOf { it.total.coerceAtLeast(0) }
        val done = downloading.sumOf { it.bytes }
        val speed = downloading.sumOf { it.speedBps }
        val title = if (running.isEmpty()) {
            ctx.getString(R.string.notification_preparing)
        } else {
            ctx.resources.getQuantityString(R.plurals.notification_downloading, running.size, running.size)
        }
        val text = running.joinToString("、") { it.name }
        return NotificationCompat.Builder(ctx, CHANNEL_DOWNLOADS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(if (speed > 0) "${Format.speed(ctx, speed)} · $text" else text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(100, if (total > 0) ((done * 100) / total).toInt() else 0, total <= 0)
            .setContentIntent(openApp(ctx, MainActivity.TAB_UPDATES))
            .build()
    }

    fun updatesFound(ctx: Context, apps: List<StoreApp>) {
        if (apps.isEmpty()) {
            NotificationManagerCompat.from(ctx).cancel(ID_UPDATES)
            return
        }
        val names = apps.take(4).joinToString("、") { it.name }
        val text = if (apps.size > 4) ctx.getString(R.string.notification_updates_more, names, apps.size) else names
        post(
            ctx, ID_UPDATES,
            NotificationCompat.Builder(ctx, CHANNEL_UPDATES)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(ctx.resources.getQuantityString(R.plurals.notification_updates, apps.size, apps.size))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openApp(ctx, MainActivity.TAB_UPDATES))
                .setAutoCancel(true)
                .build()
        )
    }

    /** 背景無法直接開啟安裝器（BAL）時的備援：點一下就打開 InstallerX。 */
    fun readyToInstall(ctx: Context, pkg: String, name: String, version: String, file: File) {
        val intent = runCatching { InstallHandoff.buildIntent(ctx, file) }.getOrNull() ?: return
        if (intent.component == null) return
        val pending = PendingIntent.getActivity(
            ctx, ("ready:$pkg").hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        post(
            ctx, readyId(pkg),
            NotificationCompat.Builder(ctx, CHANNEL_INSTALL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(ctx.getString(R.string.notification_ready, name))
                .setContentText(ctx.getString(R.string.notification_ready_hint, version))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
        )
    }

    fun failed(ctx: Context, pkg: String, name: String, message: String) {
        post(
            ctx, failId(pkg),
            NotificationCompat.Builder(ctx, CHANNEL_INSTALL)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(ctx.getString(R.string.notification_failed, name))
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(openApp(ctx, MainActivity.TAB_UPDATES))
                .setAutoCancel(true)
                .build()
        )
    }

    fun worker(ctx: Context): Notification =
        NotificationCompat.Builder(ctx, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.auto_update_title))
            .setContentText(ctx.getString(R.string.notification_auto_update_running))
            .setOngoing(true)
            .setSilent(true)
            .build()

    fun cancelFor(ctx: Context, pkg: String) {
        val manager = NotificationManagerCompat.from(ctx)
        manager.cancel(readyId(pkg))
        manager.cancel(failId(pkg))
    }

    private fun readyId(pkg: String) = ("ready:$pkg").hashCode()

    private fun failId(pkg: String) = ("fail:$pkg").hashCode()

    private fun openApp(ctx: Context, tab: String): PendingIntent = PendingIntent.getActivity(
        ctx, tab.hashCode(),
        Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TAB, tab)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun post(ctx: Context, id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(ctx).notify(id, notification)
        } catch (_: SecurityException) {
            // 通知權限被拒：靜默。
        }
    }
}
