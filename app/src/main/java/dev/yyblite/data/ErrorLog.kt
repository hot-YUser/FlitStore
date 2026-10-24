package dev.yyblite.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 診斷記錄：檢查、下載、安裝的錯誤保留最近 100 筆，重啟後仍在。
 * 內容維持英文技術字串，方便使用者直接複製回報。
 */
object ErrorLog {
    private const val PREFS = "diagnostics"
    private const val KEY = "log"
    private const val MAX = 100

    private var prefs: SharedPreferences? = null
    private val lines = ArrayDeque<String>()
    private val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries

    fun init(ctx: Context) {
        synchronized(this) {
            if (prefs != null) return
            val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs = p
            p.getString(KEY, null)?.lineSequence()?.filter { it.isNotBlank() }?.forEach { lines.addLast(it) }
            _entries.value = lines.toList()
        }
    }

    fun record(tag: String, message: String) {
        val line = synchronized(this) {
            val formatted = "${format.format(Date())} [$tag] $message"
            lines.addLast(formatted)
            while (lines.size > MAX) lines.removeFirst()
            prefs?.edit { putString(KEY, lines.joinToString("\n")) }
            _entries.value = lines.toList()
            formatted
        }
        android.util.Log.w("YYBDiag", line)
    }

    fun text(): String = synchronized(this) { lines.joinToString("\n") }

    fun clear() {
        synchronized(this) {
            lines.clear()
            prefs?.edit { remove(KEY) }
            _entries.value = emptyList()
        }
    }
}
