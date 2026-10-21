package dev.yyblite.data

import android.content.Context
import org.json.JSONObject

/**
 * 版本查詢結果快取：開 App 時先顯示上次結果，再背景更新；
 * 同一組已安裝版本在 [FRESH_MS] 內不必重抓（simple.jsp 每次 364KB，無 ETag）。
 */
class UpdateCache(ctx: Context) {
    companion object {
        const val FRESH_MS = 30 * 60 * 1000L
        private const val KEY_APPS = "apps"
        private const val KEY_FINGERPRINT = "fingerprint"
        private const val KEY_CHECKED_AT = "checked_at"
        private const val KEY_NOT_FOUND = "not_found"
        private const val NEGATIVE_TTL_MS = 3L * 24 * 60 * 60 * 1000
    }

    private val prefs = ctx.getSharedPreferences("update_cache", Context.MODE_PRIVATE)

    fun load(): Map<String, StoreApp> {
        val raw = prefs.getString(KEY_APPS, null) ?: return emptyMap()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, StoreApp>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val app = runCatching { StoreAppJson.decode(root.getJSONObject(key)) }.getOrNull()
            if (app != null) out[key] = app
        }
        return out
    }

    fun save(apps: Collection<StoreApp>) {
        val root = JSONObject()
        apps.forEach { app -> root.put(app.pkg, StoreAppJson.encode(app)) }
        prefs.edit().putString(KEY_APPS, root.toString()).apply()
    }

    fun put(app: StoreApp) {
        val root = runCatching {
            prefs.getString(KEY_APPS, null)?.let { JSONObject(it) } ?: JSONObject()
        }.getOrDefault(JSONObject())
        root.put(app.pkg, StoreAppJson.encode(app))
        prefs.edit().putString(KEY_APPS, root.toString()).apply()
    }

    fun remove(pkg: String) {
        val root = runCatching {
            prefs.getString(KEY_APPS, null)?.let { JSONObject(it) } ?: JSONObject()
        }.getOrDefault(JSONObject())
        root.remove(pkg)
        prefs.edit().putString(KEY_APPS, root.toString()).apply()
    }

    fun isNotFoundFresh(pkg: String): Boolean {
        val raw = prefs.getString(KEY_NOT_FOUND, null) ?: return false
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return false
        val at = root.optLong(pkg, 0L)
        return at > 0 && System.currentTimeMillis() - at < NEGATIVE_TTL_MS
    }

    fun markNotFound(pkg: String) {
        val root = runCatching {
            prefs.getString(KEY_NOT_FOUND, null)?.let { JSONObject(it) } ?: JSONObject()
        }.getOrDefault(JSONObject())
        root.put(pkg, System.currentTimeMillis())
        prefs.edit().putString(KEY_NOT_FOUND, root.toString()).apply()
    }

    fun clearNotFound(pkg: String) {
        val raw = prefs.getString(KEY_NOT_FOUND, null) ?: return
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (!root.has(pkg)) return
        root.remove(pkg)
        prefs.edit().putString(KEY_NOT_FOUND, root.toString()).apply()
    }

    fun lastFingerprint(): String = prefs.getString(KEY_FINGERPRINT, "") ?: ""

    fun lastCheckedAt(): Long = prefs.getLong(KEY_CHECKED_AT, 0L)

    fun markChecked(fingerprint: String, at: Long = System.currentTimeMillis()) {
        prefs.edit().putString(KEY_FINGERPRINT, fingerprint).putLong(KEY_CHECKED_AT, at).apply()
    }

    fun isFresh(fingerprint: String): Boolean =
        fingerprint == lastFingerprint() && System.currentTimeMillis() - lastCheckedAt() < FRESH_MS

    /** 已安裝版本集合的指紋；任一套件升版即失效。 */
    fun fingerprint(apps: List<InstalledApp>): String {
        val joined = apps.sortedBy { it.pkg }
            .joinToString("|") { "${it.pkg}:${it.versionCode}:${it.versionName}" }
        return java.security.MessageDigest.getInstance("MD5")
            .digest(joined.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
