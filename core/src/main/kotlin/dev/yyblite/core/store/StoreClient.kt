package dev.yyblite.core.store

import dev.yyblite.core.net.Http
import dev.yyblite.core.net.StoreFormatException
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 應用寶公開網頁端點：版本查詢與搜尋。
 *
 * 外部契約：
 * - simple.jsp 內 `window.systemData` 的 `appDetail`（含 apkUrl / apkUrl64 / fileSize）
 * - sj.qq.com/search 內 `__NEXT_DATA__` 的 `components[*].data.itemData[*]`
 */
object StoreClient {
    private const val LOOKUP_URL = "https://a.app.qq.com/o/simple.jsp?pkgname="
    private const val SEARCH_URL = "https://sj.qq.com/search?q="
    private const val MARKER = "window.systemData={"
    private const val SEARCH_LIMIT = 60

    fun lookup(pkg: String): LookupResult {
        if (pkg.isBlank()) return LookupResult.Failed(IllegalArgumentException("blank package name"))
        return try {
            val root = fetchLookupRoot(LOOKUP_URL + encode(pkg), pkg)
                ?: return LookupResult.NotInStore
            val detail = root.optJSONObject("appDetail") ?: return LookupResult.NotInStore
            if (detail.optString("packageName") != pkg) return LookupResult.NotInStore
            val app = fromLookup(detail, pkg)
                ?: return LookupResult.Failed(StoreFormatException("lookup fields missing"))
            LookupResult.Found(app)
        } catch (e: Exception) {
            LookupResult.Failed(e)
        }
    }

    /** 失敗時丟例外，讓呼叫端分得出「沒有結果」和「搜尋失敗」。 */
    fun search(keyword: String): List<StoreApp> {
        if (keyword.isBlank()) return emptyList()
        val root = StoreJson.nextData(Http.getText(SEARCH_URL + encode(keyword)))
            ?: throw StoreFormatException("search page without __NEXT_DATA__")
        val components = StoreJson.components(root) ?: return emptyList()
        val out = LinkedHashMap<String, StoreApp>()
        for (i in 0 until components.length()) {
            StoreJson.items(components.optJSONObject(i)).forEach { out.putIfAbsent(it.pkg, it) }
            if (out.size >= SEARCH_LIMIT) break
        }
        return out.values.take(SEARCH_LIMIT)
    }

    /**
     * 應用寶內部編號換套件名稱：`sj.qq.com/appdetail/<編號>` 會 308 轉到 `/appdetail/<套件名>`，
     * 只讀轉址標頭、不下載頁面。查不到回 null。
     */
    fun packageForAppId(appId: String): String? {
        if (!appId.all(Char::isDigit) || appId.isEmpty()) return null
        val conn = Http.open("https://sj.qq.com/appdetail/$appId")
        conn.instanceFollowRedirects = false
        try {
            if (conn.responseCode !in 300..399) return null
            val location = conn.getHeaderField("Location") ?: return null
            return StoreLink.packageName(if (location.startsWith("/")) "https://sj.qq.com$location" else location)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 邊讀邊找：`window.systemData` 約在第 50KB，找到完整 JSON 即斷線，
     * 不必下載整頁 364KB。找不到才讀到 96KB（未收錄頁面）。
     */
    private fun fetchLookupRoot(url: String, pkg: String): JSONObject? {
        val conn = Http.open(url)
        try {
            Http.body(conn).bufferedReader(Charsets.UTF_8).use { reader ->
                val sb = StringBuilder(128 * 1024)
                val buffer = CharArray(8 * 1024)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    sb.append(buffer, 0, read)
                    val at = sb.indexOf(MARKER)
                    if (at >= 0) {
                        val json = balancedObject(sb, at + MARKER.length - 1)
                        if (json != null) {
                            val root = runCatching { JSONObject(json) }.getOrNull()
                            val detail = root?.optJSONObject("appDetail")
                            if (detail != null && detail.optString("packageName") == pkg) return root
                        }
                    }
                    // 未收錄頁面沒有 marker，讀到 96KB 即可判定，無須下載完整 187KB。
                    if (sb.length > 96_000) break
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun fromLookup(detail: JSONObject, pkg: String): StoreApp? {
        val version = detail.optString("versionName")
        val url = StoreJson.https(detail.optString("apkUrl"))
        val md5 = detail.optString("apkMd5").trim()
        if (version.isBlank() || url.isBlank() || md5.isBlank()) return null

        val fileSize = detail.optJSONObject("fileSize")
        val downCount = detail.optJSONObject("downCount")
        val rating = detail.optJSONObject("rating")

        return StoreApp(
            pkg = pkg,
            name = detail.optString("appName").ifBlank { pkg },
            versionName = version,
            versionCode = detail.optString("versionCode").takeIf { it.isNotBlank() },
            apkUrl = url,
            apkUrl64 = StoreJson.https(detail.optString("apkUrl64")).ifBlank { null },
            apkMd5 = md5,
            signatureMd5 = detail.optString("signatureMd5").trim().ifBlank { null },
            sizeBytes = fileSize?.optString("bytes")?.toLongOrNull() ?: -1,
            sizeDesc = fileSize?.optString("desc")?.takeIf { it.isNotBlank() },
            iconUrl = StoreJson.https(detail.optString("iconUrl")).ifBlank { null },
            developer = detail.optString("developer").ifBlank { detail.optString("author") }.ifBlank { null },
            downloadDesc = downCount?.optString("desc")?.takeIf { it.isNotBlank() },
            rating = rating?.optDouble("averageRating")?.takeIf { !it.isNaN() && it > 0 },
            screenshots = StoreJson.strings(detail.optJSONArray("snapshots"), 8),
            editorIntro = detail.optString("editorIntro").takeIf { it.isNotBlank() },
            official = detail.optJSONObject("flag")?.optInt("official") == 1,
            icp = detail.optString("icpNumber").takeIf { it.isNotBlank() },
            ratingCount = rating?.optString("ratingCount")?.toLongOrNull() ?: 0,
            detailed = true
        )
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** 從 `{` 起取出平衡的大括號物件；字串內的括號與轉義皆跳過。 */
    private fun balancedObject(text: CharSequence, start: Int): String? {
        if (start < 0 || start >= text.length) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.subSequence(start, i + 1).toString()
                    }
                }
            }
        }
        return null
    }
}
