package dev.yyblite.core.store

import dev.yyblite.core.net.Http
import dev.yyblite.core.net.StoreFormatException
import org.json.JSONArray
import org.json.JSONObject

/**
 * 探索資料：sj.qq.com 首頁、應用庫、遊戲庫、分類頁、排行頁的 SSR 內容。
 * 每頁一次請求即可取得首屏資料，不需要額外 API。
 */
object DiscoverClient {
    private const val BASE = "https://sj.qq.com/"
    private const val BANNER = "yybn_home_banner_v2"

    /** 首頁卡片對應的完整排行頁。 */
    private val RANK_PAGES = mapOf(
        "yybn_home_editor_choice" to "recommend-list",
        "yybn_home_hotgame_rank" to "hot-game-list",
        "yybn_home_popapp_rank" to "popular-app-list",
        "yybn_home_newgame_rank" to "new-game-list"
    )

    /** 首頁上不是 Android 應用的卡片：PC 版、小遊戲、書籍、專題、預約。 */
    private val SKIPPED = Regex("^(exe_|YYB_HOME_WECHAT|YYB_HOME_POPULARITY_BOOK|YYB_HOME_HOME_TOPIC|yybn_home_reservegame)")

    private const val MIN_SECTION = 3

    fun feed(): Feed {
        val components = components(BASE)
        val banners = ArrayList<Banner>()
        val sections = ArrayList<Section>()
        for (i in 0 until components.length()) {
            val component = components.optJSONObject(i) ?: continue
            val key = component.optString("cardId")
            if (key == BANNER) {
                banners += banners(component)
                continue
            }
            if (SKIPPED.containsMatchIn(key)) continue
            val apps = StoreJson.items(component)
            if (apps.size < MIN_SECTION) continue
            sections += Section(
                key = key,
                title = component.optJSONObject("data")?.let { StoreJson.text(it, "title") }.orEmpty(),
                apps = apps,
                more = RANK_PAGES[key]?.let(ListSource::Rank),
                ranked = key.endsWith("_rank")
            )
        }
        return Feed(banners, sections)
    }

    /** 應用庫或遊戲庫；[categoryId] 為 null 時是「全部」。 */
    fun library(game: Boolean, categoryId: String? = null): Library {
        val path = (if (game) "game" else "app") + (categoryId?.let { "/$it" } ?: "")
        val components = components(BASE + path)
        var categories: List<Category> = emptyList()
        var apps: List<StoreApp> = emptyList()
        for (i in 0 until components.length()) {
            val component = components.optJSONObject(i) ?: continue
            val items = component.optJSONObject("data")?.optJSONArray("itemData") ?: continue
            val first = items.optJSONObject(0) ?: continue
            if (first.has("category_id")) {
                categories = categories(items)
            } else if (apps.isEmpty()) {
                apps = StoreJson.items(component)
            }
        }
        return Library(categories, apps)
    }

    fun list(source: ListSource): List<StoreApp> = when (source) {
        is ListSource.InCategory -> library(source.game, source.categoryId).apps
        is ListSource.Rank -> {
            val components = components(BASE + source.path)
            (0 until components.length())
                .asSequence()
                .map { StoreJson.items(components.optJSONObject(it)) }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
        }
    }

    private fun components(url: String): JSONArray {
        val root = StoreJson.nextData(Http.getText(url, 25_000))
            ?: throw StoreFormatException("page without __NEXT_DATA__")
        return StoreJson.components(root) ?: throw StoreFormatException("page without components")
    }

    private fun banners(component: JSONObject): List<Banner> {
        val items = component.optJSONObject("data")?.optJSONArray("itemData") ?: return emptyList()
        val out = ArrayList<Banner>(items.length())
        for (i in 0 until items.length()) {
            val raw = items.optJSONObject(i) ?: continue
            val app = StoreJson.item(raw) ?: continue
            val image = StoreJson.text(raw, "banner_image")?.let(StoreJson::https) ?: continue
            out += Banner(app, image, StoreJson.text(raw, "banner_title"))
        }
        return out
    }

    private fun categories(items: JSONArray): List<Category> {
        val out = ArrayList<Category>(items.length())
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = StoreJson.text(item, "category_id") ?: continue
            out += Category(id, StoreJson.text(item, "category_name") ?: continue)
        }
        return out
    }
}
