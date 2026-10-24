package dev.yyblite.core.store

import dev.yyblite.core.net.Http
import dev.yyblite.core.net.StoreFormatException
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

/**
 * 詳情資料來源：
 * - sj.qq.com/appdetail SSR 頁：運營商、更新時間、分類、簡介、同開發者清單
 * - yybadaccess.3g.qq.com 動態卡片：評論分頁（每次 10 則）與評分分佈
 */
object DetailClient {
    private const val CARD_URL = "https://yybadaccess.3g.qq.com/v2/dc_pcyyb_official"
    private const val REVIEW_LAYOUT =
        "YYB_HOME_GAME_DETAIL_CONTAINER|yybn_game_basic_info|yybn_game_related_game|" +
            "YYB_HOME_GAME_DETAIL_DEVELOPER_APP_LIST|YYB_HOME_GAME_DETAIL_HOT_CATEGORY|" +
            "YYB_HOME_GAME_DETAIL_HOT_APP|YYB_HOME_GAME_DETAIL_MORE_DETAIL"

    /** 評論排序與篩選，對應商店的 TagType / SortType。 */
    enum class ReviewFilter(val tagType: String, val sortType: String) {
        ALL("0", "commentListByHot"),
        LATEST("10000", "commentListByTime"),
        POSITIVE("10001", "commentListByHot"),
        NEUTRAL("10002", "commentListByHot"),
        NEGATIVE("10003", "commentListByHot")
    }

    private val guid: String by lazy { UUID.randomUUID().toString() }

    fun fetch(pkg: String): StoreDetail {
        val root = StoreJson.nextData(Http.getText("https://sj.qq.com/appdetail/" + encode(pkg), 25_000))
            ?: throw StoreFormatException("detail page without __NEXT_DATA__")
        val components = StoreJson.components(root) ?: throw StoreFormatException("detail page without components")

        var item: JSONObject? = null
        var category: String? = null
        var sameDeveloper: List<StoreApp> = emptyList()

        for (i in 0 until components.length()) {
            val component = components.optJSONObject(i) ?: continue
            val data = component.optJSONObject("data") ?: continue
            val items = data.optJSONArray("itemData") ?: continue
            when (data.optString("name")) {
                "SameDeveloper" -> sameDeveloper = StoreJson.items(component).filter { it.pkg != pkg }
                "HotCategory" -> if (category == null) {
                    category = items.optJSONObject(0)?.let { StoreJson.text(it, "category_name") }
                }
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

        val own = item
        return StoreDetail(
            appId = own?.let { StoreJson.text(it, "app_id") },
            operator = own?.let { StoreJson.text(it, "operator") },
            updateTimeSec = own?.optString("update_time")?.toLongOrNull(),
            category = own?.let { StoreJson.text(it, "cate_name_new") ?: StoreJson.text(it, "cate_name") } ?: category,
            description = own?.let { StoreJson.text(it, "description") },
            sameDeveloper = sameDeveloper,
            reviewPage = runCatching { fetchReviews(pkg, 0) }.getOrNull()
        )
    }

    /** 讀取一頁評論（[page] 為頁索引，每頁 [pageSize] 則）。 */
    fun fetchReviews(
        pkg: String,
        page: Int,
        filter: ReviewFilter = ReviewFilter.ALL,
        pageSize: Int = 10
    ): ReviewPage {
        val inner = JSONObject()
            .put("AppID", "")
            .put("TagType", filter.tagType)
            .put("SortType", filter.sortType)
            .put("PhoneGUID", guid)
            .put("UserID", "")
            .put("StartPage", page)
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

        val json = JSONObject(Http.postJson(CARD_URL, JSONObject().put("head", head).put("body", body).toString()))
        if (json.optInt("ret") != 0) throw StoreFormatException("review ret=${json.optInt("ret")}")

        var reviews: JSONArray? = null
        var hasNext = false
        var total = 0L
        var score: Double? = null
        val distribution = LinkedHashMap<Int, Long>()

        val components = json.optJSONObject("data")?.optJSONArray("components")
            ?: throw StoreFormatException("review response without components")
        for (i in 0 until components.length()) {
            val items = components.optJSONObject(i)?.optJSONObject("data")?.optJSONArray("itemData") ?: continue
            for (j in 0 until items.length()) {
                val entry = items.optJSONObject(j) ?: continue
                val commentsArray = when (val raw = entry.opt("comments")) {
                    is JSONArray -> raw
                    is String -> runCatching { JSONArray(raw) }.getOrNull()
                    else -> null
                }
                if (commentsArray != null) {
                    reviews = commentsArray
                    hasNext = entry.optBoolean("hasNext")
                    total = entry.optString("total").toLongOrNull() ?: entry.optLong("total")
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
            val comment = node.optJSONObject("comment") ?: continue
            list.add(
                Review(
                    user = node.optJSONObject("user")?.optString("nickName").orEmpty(),
                    content = comment.optString("content"),
                    createdAt = comment.optLong("createTime"),
                    stars = comment.optJSONObject("mulDimensionScore")?.optInt("total") ?: 0,
                    likes = comment.optString("praiseNum").toLongOrNull() ?: 0
                )
            )
        }
        return ReviewPage(list, hasNext, total, score, distribution)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
