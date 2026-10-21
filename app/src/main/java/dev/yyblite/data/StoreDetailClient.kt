package dev.yyblite.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
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
    val stars: Int,
    val likes: Long
)

data class ReviewPage(
    val reviews: List<Review>,
    val hasNext: Boolean,
    val total: Long,
    val score: Double?,
    val distribution: Map<Int, Long>
)

data class StoreDetail(
    val appId: String? = null,
    val operator: String? = null,
    val updateTimeSec: Long? = null,
    val category: String? = null,
    val sameDeveloper: List<AppRef> = emptyList(),
    val reviewPage: ReviewPage? = null
)

/**
 * 詳情資料來源：
 * - sj.qq.com/appdetail SSR 頁：運營商、更新時間、分類、同開發者清單
 * - yybadaccess.3g.qq.com 動態卡片：評論分頁（每次 10 則）與評分分佈
 */
object StoreDetailClient {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    private const val CARD_URL = "https://yybadaccess.3g.qq.com/v2/dc_pcyyb_official"
    private const val REVIEW_LAYOUT =
        "YYB_HOME_GAME_DETAIL_CONTAINER|yybn_game_basic_info|yybn_game_related_game|" +
            "YYB_HOME_GAME_DETAIL_DEVELOPER_APP_LIST|YYB_HOME_GAME_DETAIL_HOT_CATEGORY|" +
            "YYB_HOME_GAME_DETAIL_HOT_APP|YYB_HOME_GAME_DETAIL_MORE_DETAIL"

    private val NEXT_DATA = Regex(
        "<script id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>",
        RegexOption.DOT_MATCHES_ALL
    )
    private val guid: String by lazy { UUID.randomUUID().toString() }

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

            val ownCategory = item?.optString("cate_name_new")?.takeIf { it.isNotBlank() }
                ?: item?.optString("cate_name")?.takeIf { it.isNotBlank() }
            StoreDetail(
                appId = item?.optString("app_id")?.takeIf { it.isNotBlank() },
                operator = item?.optString("operator")?.takeIf { it.isNotBlank() },
                updateTimeSec = item?.optString("update_time")?.toLongOrNull(),
                category = ownCategory ?: category,
                sameDeveloper = sameDeveloper.distinctBy { it.pkg },
                reviewPage = fetchComments(pkg, 0)
            )
        } catch (e: Exception) {
            System.err.println("StoreDetailClient failed: ${e.message}")
            null
        }
    }

    /** 讀取一頁評論（StartPage 為頁索引，每頁 10 則）。 */
    fun fetchComments(
        pkg: String,
        startPage: Int,
        pageSize: Int = 10,
        tagType: String = "0",
        sortType: String = "commentListByHot"
    ): ReviewPage? {
        return try {
            val inner = JSONObject()
                .put("AppID", "")
                .put("TagType", tagType)
                .put("SortType", sortType)
                .put("PhoneGUID", guid)
                .put("UserID", "")
                .put("StartPage", startPage)
                .put("PageSize", pageSize)
                .put("BeginTime", 0)
                .put("EndTime", 0)
                .put("Version", 1)

            val listS = JSONObject()
                .put("region", JSONObject().put("repStr", JSONArray().put("CN")))
                .put("pkgname", JSONObject().put("repStr", JSONArray().put(pkg)))
                .put("req_json", JSONObject().put("repStr", JSONArray().put(inner.toString())))

            val body = JSONObject()
                .put("bid", "yybhome")
                .put("offset", 0)
                .put("size", pageSize)
                .put("preview", false)
                .put("layout", REVIEW_LAYOUT)
                .put("listS", listS)

            val head = JSONObject()
                .put("cmd", "dc_pcyyb_official")
                .put("authInfo", JSONObject().put("businessId", "AuthName"))
                .put("deviceInfo", JSONObject().put("platformType", 2).put("platform", 1))
                .put("userInfo", JSONObject().put("guid", guid))
                .put("expSceneIds", "")
                .put("hostAppInfo", JSONObject().put("scene", "review"))

            val payload = JSONObject().put("head", head).put("body", body)
            val response = httpPostJson(CARD_URL, payload.toString())
            val json = JSONObject(response)
            if (json.optInt("ret") != 0) return null

            var reviews: JSONArray? = null
            var hasNext = false
            var total = 0L
            var score: Double? = null
            val distribution = LinkedHashMap<Int, Long>()

            val components = json.optJSONObject("data")?.optJSONArray("components") ?: return null
            for (i in 0 until components.length()) {
                val comp = components.optJSONObject(i)
                val cdata = comp?.optJSONObject("data")
                val items = cdata?.optJSONArray("itemData") ?: continue
                for (j in 0 until items.length()) {
                    val entry = items.optJSONObject(j) ?: continue
                    val rawComments = entry.opt("comments")
                    val commentsArray = when (rawComments) {
                        is JSONArray -> rawComments
                        is String -> runCatching { JSONArray(rawComments) }.getOrNull()
                        else -> null
                    }
                    if (commentsArray != null) {
                        reviews = commentsArray
                        hasNext = entry.optBoolean("hasNext")
                        total = entry.optString("total").toLongOrNull()
                            ?: entry.optLong("total")
                    }
                    entry.optJSONObject("appScore")?.let { appScore ->
                        score = appScore.optDouble("score").takeIf { !it.isNaN() && it > 0 }
                        val mul = appScore.optJSONObject("mulScore")
                        if (mul != null) {
                            for (star in 5 downTo 1) {
                                val count = mul.optString(star.toString()).toLongOrNull() ?: 0L
                                if (count > 0) distribution[star] = count
                            }
                        }
                    }
                }
            }

            val list = ArrayList<Review>()
            val array = reviews ?: JSONArray()
            for (i in 0 until array.length()) {
                val node = array.optJSONObject(i) ?: continue
                val user = node.optJSONObject("user")
                val comment = node.optJSONObject("comment") ?: continue
                list.add(
                    Review(
                        user = user?.optString("nickName")?.ifBlank { "匿名" } ?: "匿名",
                        content = comment.optString("content"),
                        date = formatDate(comment.optLong("createTime")),
                        stars = comment.optJSONObject("mulDimensionScore")?.optInt("total") ?: 0,
                        likes = comment.optString("praiseNum").toLongOrNull() ?: 0
                    )
                )
            }
            ReviewPage(list, hasNext, total, score, distribution)
        } catch (e: Exception) {
            System.err.println("fetchComments failed: ${e.message}")
            null
        }
    }

    private fun formatDate(epochSeconds: Long): String {
        if (epochSeconds <= 0) return ""
        return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(epochSeconds * 1000))
    }

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

    private fun httpPostJson(url: String, payload: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 25_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept-Encoding", "gzip")
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
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
