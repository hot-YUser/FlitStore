package dev.yyblite.data

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 應用寶公開網頁端點客戶端。僅用 java.net + org.json，可在 JVM 單測中直接執行。
 *
 * 兩個外部契約：
 * - simple.jsp 內 `window.systemData` 的 `appDetail`
 * - sj.qq.com/search 內 `__NEXT_DATA__` 的 `components[*].data.itemData[*]`
 */
object StoreClient {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    private const val LOOKUP_URL = "https://a.app.qq.com/o/simple.jsp?pkgname="
    private const val SEARCH_URL = "https://sj.qq.com/search?q="
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000

    private val NEXT_DATA_RE =
        Regex("<script id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)

    fun lookup(pkg: String): LookupResult {
        if (pkg.isBlank()) return LookupResult.Failed("包名為空")
        return try {
            val html = fetch(LOOKUP_URL + encode(pkg))
            val json = balancedJson(html) ?: return LookupResult.NotInStore
            val root = JSONObject(json)
            val detail = root.optJSONObject("appDetail") ?: return LookupResult.NotInStore
            if (detail.optString("packageName") != pkg) return LookupResult.NotInStore
            val app = fromLookup(detail, pkg) ?: return LookupResult.Failed("欄位缺失")
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

    private fun fromLookup(detail: JSONObject, pkg: String): StoreApp? {
        val version = detail.optString("versionName")
        val url = https(detail.optString("apkUrl"))
        val md5 = detail.optString("apkMd5").trim()
        if (version.isBlank() || url.isBlank() || md5.isBlank()) return null
        return StoreApp(
            pkg = pkg,
            name = detail.optString("appName").ifBlank { pkg },
            versionName = version,
            apkUrl = url,
            apkMd5 = md5,
            sizeBytes = -1,
            iconUrl = https(detail.optString("iconUrl")).ifBlank { null },
            developer = detail.optString("developer").ifBlank { detail.optString("author") }.ifBlank { null }
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
            apkUrl = url,
            apkMd5 = item.optString("md_5").trim(),
            sizeBytes = item.optString("apk_size").toLongOrNull() ?: -1,
            iconUrl = https(item.optString("icon")).ifBlank { null },
            developer = item.optString("developer").ifBlank { null }
        )
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
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** 取出 `window.systemData={` 後的平衡大括號 JSON；無此標記回 null。 */
    private fun balancedJson(html: String): String? {
        val marker = html.indexOf("window.systemData={")
        if (marker < 0) return null
        val start = html.indexOf('{', marker)
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until html.length) {
            val c = html[i]
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
                        if (depth == 0) return html.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }
}
