package dev.yyblite.data

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 診斷記錄：把檢查、下載、安裝的錯誤保留最近 100 筆，
 * 可在「診斷記錄」對話框查看與複製，重啟後仍保留。
 */
object ErrorLog {
    private const val PREFS = "diagnostics"
    private const val KEY = "log"
    private const val MAX = 100

    private var prefs: SharedPreferences? = null
    private val lines = ArrayDeque<String>()
    private val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun init(ctx: Context) {
        synchronized(this) {
            if (prefs != null) return
            val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs = p
            p.getString(KEY, null)?.lineSequence()?.filter { it.isNotBlank() }?.forEach { lines.addLast(it) }
        }
    }

    fun record(tag: String, message: String) {
        val line = synchronized(this) {
            val formatted = "${format.format(Date())} [$tag] $message"
            lines.addLast(formatted)
            while (lines.size > MAX) lines.removeFirst()
            prefs?.edit()?.putString(KEY, lines.joinToString("\n"))?.apply()
            formatted
        }
        android.util.Log.w("YYBDiag", line)
    }

    fun all(): List<String> = synchronized(this) { lines.toList() }

    fun text(): String = all().joinToString("\n")

    fun clear() {
        synchronized(this) {
            lines.clear()
            prefs?.edit()?.remove(KEY)?.apply()
        }
    }
}
