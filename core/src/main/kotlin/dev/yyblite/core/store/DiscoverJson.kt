package dev.yyblite.core.store

import org.json.JSONArray
import org.json.JSONObject

/** 探索資料的磁碟快取格式：冷啟動先顯示上次內容，再背景刷新。 */
object DiscoverJson {

    fun encodeApps(apps: List<StoreApp>): String = appsArray(apps).toString()

    fun decodeApps(text: String): List<StoreApp> = apps(JSONArray(text))

    fun encodeFeed(feed: Feed): String = JSONObject()
        .put("banners", JSONArray().apply {
            feed.banners.forEach {
                put(JSONObject().put("app", StoreAppJson.encode(it.app)).put("image", it.image).put("title", it.title ?: ""))
            }
        })
        .put("sections", JSONArray().apply {
            feed.sections.forEach { section ->
                put(
                    JSONObject()
                        .put("key", section.key)
                        .put("title", section.title)
                        .put("ranked", section.ranked)
                        .put("more", (section.more as? ListSource.Rank)?.path ?: "")
                        .put("apps", appsArray(section.apps))
                )
            }
        })
        .toString()

    fun decodeFeed(text: String): Feed {
        val json = JSONObject(text)
        val banners = json.optJSONArray("banners").objects().mapNotNull { item ->
            val app = item.optJSONObject("app")?.let { StoreAppJson.decode(it, requireDownload = false) }
                ?: return@mapNotNull null
            Banner(app, item.optString("image"), item.optString("title").ifBlank { null })
        }
        val sections = json.optJSONArray("sections").objects().map { item ->
            Section(
                key = item.optString("key"),
                title = item.optString("title"),
                apps = apps(item.optJSONArray("apps")),
                more = item.optString("more").ifBlank { null }?.let(ListSource::Rank),
                ranked = item.optBoolean("ranked")
            )
        }
        return Feed(banners, sections)
    }

    fun encodeLibrary(library: Library): String = JSONObject()
        .put("categories", JSONArray().apply {
            library.categories.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
        })
        .put("apps", appsArray(library.apps))
        .toString()

    fun decodeLibrary(text: String): Library {
        val json = JSONObject(text)
        val categories = json.optJSONArray("categories").objects().map { Category(it.optString("id"), it.optString("name")) }
        return Library(categories, apps(json.optJSONArray("apps")))
    }

    private fun appsArray(apps: List<StoreApp>) = JSONArray().apply { apps.forEach { put(StoreAppJson.encode(it)) } }

    private fun apps(array: JSONArray?): List<StoreApp> =
        array.objects().mapNotNull { StoreAppJson.decode(it, requireDownload = false) }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
