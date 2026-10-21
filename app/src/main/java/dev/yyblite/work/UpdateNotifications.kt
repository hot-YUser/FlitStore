package dev.yyblite.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.yyblite.MainActivity
import dev.yyblite.R
import dev.yyblite.data.StoreApp

object UpdateNotifications {
    private const val CHANNEL_ID = "updates"
    private const val ID = 2001

    fun ensureChannel(ctx: Context) {
        val manager = ctx.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                ctx.getString(R.string.updates_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }

    fun show(ctx: Context, apps: List<StoreApp>) {
        if (apps.isEmpty()) {
            NotificationManagerCompat.from(ctx).cancel(ID)
            return
        }
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val names = apps.take(4).joinToString("、") { it.name }
        val text = if (apps.size > 4) "$names 等 ${apps.size} 個" else names
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(ctx.getString(R.string.updates_found, apps.size))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(ID, notification)
        } catch (_: SecurityException) {
        }
    }
}
