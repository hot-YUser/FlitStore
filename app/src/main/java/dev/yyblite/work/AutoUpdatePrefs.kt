package dev.yyblite.work

import android.content.Context

/**
 * 背景自動更新開關與嘗試冷卻。
 * 同一套件同一版本 12 小時內只自動嘗試一次，避免安裝失敗時每 3 小時重抓。
 */
class AutoUpdatePrefs(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("auto_update", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) {
            prefs.edit().putBoolean("enabled", value).apply()
            if (!value) prefs.edit().clear().apply()
        }

    fun shouldAttempt(pkg: String, version: String): Boolean {
        val last = prefs.getLong("$pkg@$version", 0L)
        return System.currentTimeMillis() - last > 12L * 60 * 60 * 1000
    }

    fun markAttempt(pkg: String, version: String) {
        prefs.edit().putLong("$pkg@$version", System.currentTimeMillis()).apply()
    }
}
