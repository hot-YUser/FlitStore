package dev.yyblite.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Format {
    fun speed(bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0) return "0 KB/s"
        val kb = bytesPerSecond / 1024.0
        return if (kb < 1024) {
            String.format(Locale.US, "%.0f KB/s", kb)
        } else {
            String.format(Locale.US, "%.1f MB/s", kb / 1024.0)
        }
    }

    fun eta(seconds: Long): String {
        if (seconds <= 0) return "--"
        val m = seconds / 60
        val s = seconds % 60
        return if (m > 0) "${m} 分 ${String.format(Locale.US, "%02d", s)} 秒" else "$s 秒"
    }

    fun bytes(bytes: Long): String {
        if (bytes <= 0) return "未知"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb < 1) String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else String.format(Locale.US, "%.1f MB", mb)
    }

    fun date(epochSeconds: Long): String {
        if (epochSeconds <= 0) return ""
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(epochSeconds * 1000))
    }

    fun time(epochMillis: Long): String {
        if (epochMillis <= 0) return ""
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(epochMillis))
    }
}
