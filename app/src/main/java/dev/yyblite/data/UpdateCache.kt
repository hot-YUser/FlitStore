package dev.yyblite.data

import android.content.Context
import dev.yyblite.core.store.StoreApp
import dev.yyblite.core.store.StoreAppJson
import dev.yyblite.core.util.KvFile
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 商店查詢結果與判定輔助資料的本機快取，全部可由網路重建。
 * 128 條執行緒會同時寫入，所以底層用 [KvFile]（記憶體為準、單執行緒落盤）。
 */
class UpdateCache(private val ctx: Context) {
    private val apps by lazy { KvFile(File(ctx.filesDir, "cache/store_apps.json")) }
    private val meta by lazy { KvFile(File(ctx.filesDir, "cache/store_meta.json")) }
    private val decoded = ConcurrentHashMap<String, StoreApp>()
    @Volatile private var loaded = false

    /** 在背景執行緒先讀檔並解碼；v0.1.0 的 SharedPreferences 快取一併清掉（可重建）。 */
    fun warmUp() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            for ((pkg, json) in apps.snapshot()) {
                runCatching { StoreAppJson.decode(JSONObject(json)) }.getOrNull()?.let { decoded[pkg] = it }
            }
            ctx.deleteSharedPreferences("update_cache")
            loaded = true
        }
    }

    fun get(pkg: String): StoreApp? {
        warmUp()
        return decoded[pkg]
    }

    fun put(app: StoreApp) {
        warmUp()
        decoded[app.pkg] = app
        apps.put(app.pkg, StoreAppJson.encode(app).toString())
    }

    fun remove(pkg: String) {
        decoded.remove(pkg)
        apps.remove(pkg)
    }

    /** 未收錄的套件 3 天內不再重查。 */
    fun isNotFoundFresh(pkg: String): Boolean {
        val at = meta["nf:$pkg"]?.toLongOrNull() ?: return false
        return System.currentTimeMillis() - at < NEGATIVE_TTL_MS
    }

    fun markNotFound(pkg: String) = meta.put("nf:$pkg", System.currentTimeMillis().toString())

    fun clearNotFound(pkg: String) = meta.remove("nf:$pkg")

    /** 商店標示較新、APK 的 versionCode 卻比已安裝舊（版本回退），記錄後不再提供。 */
    fun isRegression(pkg: String, storeVersion: String): Boolean = meta["rg:$pkg"] == storeVersion

    fun markRegression(pkg: String, storeVersion: String) = meta.put("rg:$pkg", storeVersion)

    /** 探測失敗的短期記錄，避免每次刷新都重試同一個 APK；過期後再試。 */
    fun probeFailed(pkg: String, md5: String): Boolean {
        val raw = meta["pf:$pkg"] ?: return false
        if (raw.substringBefore('|') != md5) return false
        val at = raw.substringAfter('|').toLongOrNull() ?: return false
        return System.currentTimeMillis() - at < PROBE_FAIL_TTL_MS
    }

    fun markProbeFailed(pkg: String, md5: String) =
        meta.put("pf:$pkg", "$md5|${System.currentTimeMillis()}")

    /** 已探測的商店建置號，綁定 APK 的 MD5：檔案換了就作廢。 */
    fun buildProbe(pkg: String, md5: String): Long? {
        val value = meta["bp:$pkg"] ?: return null
        if (value.substringBefore('|') != md5) return null
        return value.substringAfter('|').toLongOrNull()?.takeIf { it > 0 }
    }

    fun putBuildProbe(pkg: String, md5: String, code: Long) = meta.put("bp:$pkg", "$md5|$code")

    var checkedAt: Long
        get() = meta["checked_at"]?.toLongOrNull() ?: 0L
        set(value) = meta.put("checked_at", value.toString())

    fun flush() {
        apps.flush()
        meta.flush()
    }

    private companion object {
        const val NEGATIVE_TTL_MS = 3L * 24 * 60 * 60 * 1000
        const val PROBE_FAIL_TTL_MS = 6L * 60 * 60 * 1000
    }
}
