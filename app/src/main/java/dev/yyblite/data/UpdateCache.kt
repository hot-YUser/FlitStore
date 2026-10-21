package dev.yyblite.data

import android.content.Context
import org.json.JSONObject

/**
 * 版本查詢結果快取：開 App 時先顯示上次結果，再即時刷新覆蓋。
 */
class UpdateCache(ctx: Context) {
    companion object {
        private const val KEY_APPS = "apps"
        private const val KEY_FINGERPRINT = "fingerprint"
        private const val KEY_CHECKED_AT = "checked_at"
        private const val KEY_NOT_FOUND = "not_found"
        private const val KEY_REGRESSION = "regression"
        private const val KEY_BUILDS = "builds"
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

    /** 商店提供比已安裝更舊的 versionCode（版本回退），記錄後不再提示。 */
    fun markRegression(pkg: String, storeVersion: String) {
        val root = runCatching {
            prefs.getString(KEY_REGRESSION, null)?.let { JSONObject(it) } ?: JSONObject()
        }.getOrDefault(JSONObject())
        root.put(pkg, storeVersion)
        prefs.edit().putString(KEY_REGRESSION, root.toString()).apply()
    }

    fun isRegression(pkg: String, storeVersion: String): Boolean {
        val raw = prefs.getString(KEY_REGRESSION, null) ?: return false
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return false
        return root.optString(pkg) == storeVersion
    }

    fun clearRegression(pkg: String) {
        val raw = prefs.getString(KEY_REGRESSION, null) ?: return
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (!root.has(pkg)) return
        root.remove(pkg)
        prefs.edit().putString(KEY_REGRESSION, root.toString()).apply()
    }

    /** 已探測的商店建置號（鍵為 APK URL 的 MD5）。 */
    fun buildProbe(pkg: String): Pair<String, Long>? {
        val raw = prefs.getString(KEY_BUILDS, null) ?: return null
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val obj = root.optJSONObject(pkg) ?: return null
        val md5 = obj.optString("m")
        val code = obj.optLong("c", 0)
        if (md5.isBlank() || code <= 0) return null
        return md5 to code
    }

    fun putBuildProbe(pkg: String, md5: String, code: Long) {
        val root = runCatching {
            prefs.getString(KEY_BUILDS, null)?.let { JSONObject(it) } ?: JSONObject()
        }.getOrDefault(JSONObject())
        root.put(pkg, JSONObject().put("m", md5).put("c", code))
        prefs.edit().putString(KEY_BUILDS, root.toString()).apply()
    }

    fun lastFingerprint(): String = prefs.getString(KEY_FINGERPRINT, "") ?: ""

    fun lastCheckedAt(): Long = prefs.getLong(KEY_CHECKED_AT, 0L)

    fun markChecked(fingerprint: String, at: Long = System.currentTimeMillis()) {
        prefs.edit().putString(KEY_FINGERPRINT, fingerprint).putLong(KEY_CHECKED_AT, at).apply()
    }

    /** 已安裝版本集合的指紋；任一套件升版即失效。 */
    fun fingerprint(apps: List<InstalledApp>): String {
        val joined = apps.sortedBy { it.pkg }
            .joinToString("|") { "${it.pkg}:${it.versionCode}:${it.versionName}" }
        return java.security.MessageDigest.getInstance("MD5")
            .digest(joined.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
