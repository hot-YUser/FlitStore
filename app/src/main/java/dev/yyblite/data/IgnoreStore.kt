package dev.yyblite.data

import android.content.Context
import androidx.core.content.edit

data class IgnoredEntry(val pkg: String, val version: String?, val forever: Boolean)

/** 「忽略此版本」以商店版本（加 APK MD5）為準；「永遠忽略」對該應用永久生效。檔名與鍵沿用 v0.1.0。 */
class IgnoreStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("ignored_updates", Context.MODE_PRIVATE)

    fun ignoreOnce(pkg: String, version: String, apkMd5: String?) {
        val key = if (apkMd5.isNullOrBlank()) version else "$version:$apkMd5"
        prefs.edit { putString("once:$pkg", key) }
    }

    fun ignoreForever(pkg: String) {
        prefs.edit { putBoolean("forever:$pkg", true) }
    }

    fun clear(pkg: String) {
        prefs.edit { remove("once:$pkg").remove("forever:$pkg") }
    }

    fun isIgnored(pkg: String, storeVersion: String, apkMd5: String? = null): Boolean {
        if (prefs.getBoolean("forever:$pkg", false)) return true
        val stored = prefs.getString("once:$pkg", null) ?: return false
        if (stored == storeVersion) return true
        return !apkMd5.isNullOrBlank() && stored == "$storeVersion:$apkMd5"
    }

    fun has(pkg: String): Boolean = prefs.contains("forever:$pkg") || prefs.contains("once:$pkg")

    fun entries(): List<IgnoredEntry> = prefs.all.mapNotNull { (key, value) ->
        when {
            key.startsWith("forever:") && value == true ->
                IgnoredEntry(key.removePrefix("forever:"), null, true)
            key.startsWith("once:") && value is String ->
                IgnoredEntry(key.removePrefix("once:"), value.substringBefore(':'), false)
            else -> null
        }
    }.distinctBy { it.pkg }
}
