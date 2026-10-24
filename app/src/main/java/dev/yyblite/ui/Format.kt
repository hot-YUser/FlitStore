package dev.yyblite.ui

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import dev.yyblite.R
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** 依目前語系格式化大小、速度、日期。 */
object Format {
    fun size(ctx: Context, bytes: Long): String =
        if (bytes <= 0) ctx.getString(R.string.value_unknown) else Formatter.formatShortFileSize(ctx, bytes)

    fun speed(ctx: Context, bytesPerSecond: Long): String =
        ctx.getString(R.string.value_speed, Formatter.formatShortFileSize(ctx, bytesPerSecond.coerceAtLeast(0)))

    /** 剩餘時間以 m:ss 表示，各語系通用。 */
    fun eta(seconds: Long): String =
        if (seconds <= 0) "--:--" else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)

    fun date(epochSeconds: Long): String =
        if (epochSeconds <= 0) "" else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochSeconds * 1000))

    fun relative(epochMillis: Long): String =
        DateUtils.getRelativeTimeSpanString(epochMillis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

    fun rating(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)
}
