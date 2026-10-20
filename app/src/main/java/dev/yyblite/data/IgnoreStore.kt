package dev.yyblite.data

import android.content.Context

data class IgnoredEntry(val pkg: String, val version: String?, val forever: Boolean)

/** 「忽略本次更新」以商店版本為準；「忽略此應用更新」永久生效。 */
class IgnoreStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("ignored_updates", Context.MODE_PRIVATE)

    fun ignoreOnce(pkg: String, version: String) {
        prefs.edit().putString("once:$pkg", version).apply()
    }

    fun ignoreForever(pkg: String) {
        prefs.edit().putBoolean("forever:$pkg", true).apply()
    }

    fun clear(pkg: String) {
        prefs.edit().remove("once:$pkg").remove("forever:$pkg").apply()
    }

    fun isIgnored(pkg: String, storeVersion: String): Boolean {
        if (prefs.getBoolean("forever:$pkg", false)) return true
        return prefs.getString("once:$pkg", null) == storeVersion
    }

    fun isIgnoredForever(pkg: String): Boolean = prefs.getBoolean("forever:$pkg", false)

    fun ignoredOnceVersion(pkg: String): String? = prefs.getString("once:$pkg", null)

    fun entries(): List<IgnoredEntry> = prefs.all.mapNotNull { (key, value) ->
        when {
            key.startsWith("forever:") && value == true ->
                IgnoredEntry(key.removePrefix("forever:"), null, true)
            key.startsWith("once:") && value is String ->
                IgnoredEntry(key.removePrefix("once:"), value, false)
            else -> null
        }
    }
}
