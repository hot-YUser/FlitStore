package dev.yyblite.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/**
 * 應用寶公開網頁端點客戶端。僅用 java.net + org.json，可在 JVM 單測中直接執行。
 *
 * 外部契約：
 * - simple.jsp 內 `window.systemData` 的 `appDetail`（含 apkUrl / apkUrl64 / fileSize）
 * - sj.qq.com/search 內 `__NEXT_DATA__` 的 `components[*].data.itemData[*]`
 */
object StoreClient {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    private const val LOOKUP_URL = "https://a.app.qq.com/o/simple.jsp?pkgname="
    private const val SEARCH_URL = "https://sj.qq.com/search?q="
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val MARKER = "window.systemData={"

    private val NEXT_DATA_RE =
        Regex("<script id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)

    fun lookup(pkg: String): LookupResult {
        if (pkg.isBlank()) return LookupResult.Failed("包名為空")
        return try {
            val root = fetchLookupRoot(LOOKUP_URL + encode(pkg), pkg)
                ?: return LookupResult.NotInStore
            val detail = root.optJSONObject("appDetail") ?: return LookupResult.NotInStore
            if (detail.optString("packageName") != pkg) return LookupResult.NotInStore
            val app = fromLookup(detail, pkg)
                ?: return LookupResult.Failed("欄位缺失")
            LookupResult.Found(app)
        } catch (e: Exception) {
            LookupResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    fun search(keyword: String): List<StoreApp> {
        if (keyword.isBlank()) return emptyList()
        return try {
            val html = fetch(SEARCH_URL + encode(keyword))
            val match = NEXT_DATA_RE.find(html) ?: return emptyList()
            val root = JSONObject(match.groupValues[1])
            val components = root.optJSONObject("props")
                ?.optJSONObject("pageProps")
                ?.optJSONObject("dynamicCardResponse")
                ?.optJSONObject("data")
                ?.optJSONArray("components")
                ?: return emptyList()

            val out = LinkedHashMap<String, StoreApp>()
            for (i in 0 until components.length()) {
                val data = components.optJSONObject(i)?.optJSONObject("data") ?: continue
                val items = data.optJSONArray("itemData") ?: continue
                for (j in 0 until items.length()) {
                    val raw = items.optJSONObject(j) ?: continue
                    val item = raw.optJSONObject("data")?.takeIf { it.has("pkg_name") } ?: raw
                    val app = fromSearch(item) ?: continue
                    out.putIfAbsent(app.pkg, app)
                    if (out.size >= 60) break
                }
                if (out.size >= 60) break
            }
            out.values.toList()
        } catch (e: Exception) {
            System.err.println("StoreClient.search failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * 邊讀邊找：`window.systemData` 約在第 50KB，找到完整 JSON 即斷線，
     * 不必下載整頁 364KB。找不到才讀到 EOF（未收錄頁面）。
     */
    private fun fetchLookupRoot(url: String, pkg: String): JSONObject? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            conn.setRequestProperty("Accept-Encoding", "gzip")
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")

            val gzip = conn.contentEncoding?.contains("gzip", ignoreCase = true) == true
            val stream = if (gzip) GZIPInputStream(conn.inputStream) else conn.inputStream
            stream.bufferedReader(Charsets.UTF_8).use { reader ->
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
        val url = https(detail.optString("apkUrl"))
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
            apkUrl64 = https(detail.optString("apkUrl64")).ifBlank { null },
            apkMd5 = md5,
            signatureMd5 = detail.optString("signatureMd5").trim().ifBlank { null },
            sizeBytes = fileSize?.optString("bytes")?.toLongOrNull() ?: -1,
            sizeDesc = fileSize?.optString("desc")?.takeIf { it.isNotBlank() },
            iconUrl = https(detail.optString("iconUrl")).ifBlank { null },
            developer = detail.optString("developer").ifBlank { detail.optString("author") }.ifBlank { null },
            downloadDesc = downCount?.optString("desc")?.takeIf { it.isNotBlank() },
            rating = rating?.optDouble("averageRating")?.takeIf { !it.isNaN() && it > 0 },
            screenshots = strings(detail.optJSONArray("snapshots"), 5),
            editorIntro = detail.optString("editorIntro").takeIf { it.isNotBlank() },
            official = detail.optJSONObject("flag")?.optInt("official") == 1,
            icp = detail.optString("icpNumber").takeIf { it.isNotBlank() },
            ratingCount = rating?.optString("ratingCount")?.toLongOrNull() ?: 0
        )
    }

    private fun fromSearch(item: JSONObject): StoreApp? {
        val pkg = item.optString("pkg_name")
        val version = item.optString("version_name")
        val url = https(item.optString("download_url"))
        if (pkg.isBlank() || version.isBlank() || url.isBlank()) return null
        return StoreApp(
            pkg = pkg,
            name = item.optString("name").ifBlank { pkg },
            versionName = version,
            versionCode = null,
            apkUrl = url,
            apkUrl64 = null,
            apkMd5 = item.optString("md_5").trim(),
            signatureMd5 = null,
            sizeBytes = item.optString("apk_size").toLongOrNull() ?: -1,
            sizeDesc = null,
            iconUrl = https(item.optString("icon")).ifBlank { null },
            developer = item.optString("developer").ifBlank { null },
            downloadDesc = null,
            rating = null,
            screenshots = emptyList(),
            editorIntro = null
        )
    }

    private fun strings(array: JSONArray?, limit: Int): List<String> {
        if (array == null) return emptyList()
        val out = ArrayList<String>(limit)
        for (i in 0 until minOf(array.length(), limit)) {
            val value = https(array.optString(i))
            if (value.isNotBlank()) out.add(value)
        }
        return out
    }

    private fun https(url: String): String =
        if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            conn.setRequestProperty("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
            conn.setRequestProperty("Accept-Encoding", "gzip")
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val gzip = conn.contentEncoding?.contains("gzip", ignoreCase = true) == true
            val stream = if (gzip) GZIPInputStream(conn.inputStream) else conn.inputStream
            return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

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
