package dev.yyblite.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

data class AppRef(
    val pkg: String,
    val name: String,
    val iconUrl: String?,
    val developer: String?,
    val operator: String?
)

data class Review(
    val user: String,
    val content: String,
    val date: String,
    val stars: Int
)

data class StoreDetail(
    val appId: String? = null,
    val operator: String? = null,
    val updateTimeSec: Long? = null,
    val category: String? = null,
    val sameDeveloper: List<AppRef> = emptyList(),
    val reviews: List<Review> = emptyList()
)

/**
 * 詳情資料只從 sj.qq.com 的 SSR 頁面取得：appdetail 提供運營商、更新時間、
 * 分類與同開發者清單；review 頁提供用戶評論。皆為免費公開頁面。
 */
object StoreDetailClient {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    private val NEXT_DATA = Regex(
        "<script id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>",
        RegexOption.DOT_MATCHES_ALL
    )

    fun fetch(pkg: String): StoreDetail? {
        return try {
            val page = httpGet("https://sj.qq.com/appdetail/" + encode(pkg))
            val root = nextData(page) ?: return null
            val components = root.optJSONObject("props")
                ?.optJSONObject("pageProps")
                ?.optJSONObject("dynamicCardResponse")
                ?.optJSONObject("data")
                ?.optJSONArray("components")
                ?: return null

            var item: JSONObject? = null
            var category: String? = null
            val sameDeveloper = ArrayList<AppRef>()

            for (i in 0 until components.length()) {
                val component = components.optJSONObject(i) ?: continue
                val data = component.optJSONObject("data") ?: continue
                val name = data.optString("name")
                val items = data.optJSONArray("itemData") ?: continue

                if (name == "SameDeveloper") {
                    for (j in 0 until items.length()) {
                        val ref = items.optJSONObject(j) ?: continue
                        val refPkg = ref.optString("pkg_name")
                        if (refPkg.isBlank()) continue
                        sameDeveloper.add(
                            AppRef(
                                pkg = refPkg,
                                name = ref.optString("name").ifBlank { refPkg },
                                iconUrl = https(ref.optString("icon")).ifBlank { null },
                                developer = ref.optString("developer").ifBlank { null },
                                operator = ref.optString("operator").ifBlank { null }
                            )
                        )
                    }
                } else if (name == "HotCategory" && category == null) {
                    category = items.optJSONObject(0)?.optString("category_name")?.takeIf { it.isNotBlank() }
                }

                if (item == null) {
                    for (j in 0 until items.length()) {
                        val candidate = items.optJSONObject(j) ?: continue
                        if (candidate.optString("pkg_name") == pkg) {
                            item = candidate
                            break
                        }
                    }
                }
            }

            val reviews = runCatching { fetchReviews(pkg) }.getOrDefault(emptyList())

            StoreDetail(
                appId = item?.optString("app_id")?.takeIf { it.isNotBlank() },
                operator = item?.optString("operator")?.takeIf { it.isNotBlank() },
                updateTimeSec = item?.optString("update_time")?.toLongOrNull(),
                category = category,
                sameDeveloper = sameDeveloper.distinctBy { it.pkg },
                reviews = reviews
            )
        } catch (e: Exception) {
            System.err.println("StoreDetailClient failed: ${e.message}")
            null
        }
    }

    private fun fetchReviews(pkg: String): List<Review> {
        val html = httpGet("https://sj.qq.com/appdetail/" + encode(pkg) + "/review")
        val blocks = html.split("GameReview_commentCard__").drop(1)
        val out = ArrayList<Review>()
        for (block in blocks) {
            val name = Regex("<cite class=\"UserInfo_userName[^\"]*\">(.*?)</cite>", RegexOption.DOT_MATCHES_ALL)
                .find(block)?.groupValues?.get(1)
            val content = Regex("<[a-z]+ class=\"[^\"]*CommentCard_commentContent[^\"]*\"[^>]*>(.*?)</[a-z]+>", RegexOption.DOT_MATCHES_ALL)
                .find(block)?.groupValues?.get(1)
            val date = Regex("<[a-z]+ class=\"[^\"]*CommentCard_commentDate[^\"]*\"[^>]*>(.*?)</[a-z]+>", RegexOption.DOT_MATCHES_ALL)
                .find(block)?.groupValues?.get(1)
            val stars = Regex("CommentCard_commentStar[^\"]*CommentCard_active").findAll(block).count()
            if (name == null || content == null) continue
            out.add(
                Review(
                    user = clean(name),
                    content = clean(content),
                    date = clean(date ?: ""),
                    stars = stars
                )
            )
            if (out.size >= 12) break
        }
        return out
    }

    private fun clean(html: String): String = html
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#x27;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
        .trim()

    private fun nextData(html: String): JSONObject? {
        val match = NEXT_DATA.find(html) ?: return null
        return runCatching { JSONObject(match.groupValues[1]) }.getOrNull()
    }

    private fun https(url: String): String =
        if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 25_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
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
}
