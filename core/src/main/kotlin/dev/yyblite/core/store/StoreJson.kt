package dev.yyblite.core.store

import org.json.JSONArray
import org.json.JSONObject

/** sj.qq.com 各頁共用的解析：`__NEXT_DATA__` 取出、清單項目轉 [StoreApp]。 */
internal object StoreJson {
    private val NEXT_DATA =
        Regex("<script id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

    fun nextData(html: String): JSONObject? =
        NEXT_DATA.find(html)?.let { runCatching { JSONObject(it.groupValues[1]) }.getOrNull() }

    fun components(root: JSONObject): JSONArray? =
        root.optJSONObject("props")
            ?.optJSONObject("pageProps")
            ?.optJSONObject("dynamicCardResponse")
            ?.optJSONObject("data")
            ?.optJSONArray("components")

    fun https(url: String): String =
        if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url

    fun text(json: JSONObject, key: String): String? =
        json.optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }

    fun strings(array: JSONArray?, limit: Int): List<String> {
        if (array == null) return emptyList()
        val out = ArrayList<String>(minOf(array.length(), limit))
        for (i in 0 until minOf(array.length(), limit)) {
            val value = https(array.optString(i))
            if (value.isNotBlank()) out.add(value)
        }
        return out
    }

    /**
     * 清單項目（搜尋、探索、排行、同開發者）轉 [StoreApp]。
     * 裝不了的項目回 null：微信小遊戲（game_type 2、包名不含點）、PC 遊戲（game_type 7）、預約中的遊戲。
     */
    fun item(raw: JSONObject): StoreApp? {
        val json = raw.optJSONObject("data")?.takeIf { it.has("pkg_name") } ?: raw
        val pkg = json.optString("pkg_name").trim()
        if (!PACKAGE.matches(pkg)) return null
        if (json.optString("is_booking") == "1") return null
        when (json.optString("game_type")) {
            "2", "7" -> return null
        }
        val url = https(json.optString("download_url")).ifBlank { https(json.optString("apk_url")) }
        return StoreApp(
            pkg = pkg,
            name = text(json, "name") ?: pkg,
            versionName = text(json, "version_name").orEmpty(),
            versionCode = null,
            apkUrl = url,
            apkUrl64 = null,
            apkMd5 = json.optString("md_5").trim(),
            signatureMd5 = null,
            sizeBytes = json.optString("apk_size").toLongOrNull()?.takeIf { it > 0 } ?: -1,
            sizeDesc = null,
            iconUrl = text(json, "icon")?.let(::https),
            developer = text(json, "developer"),
            downloadDesc = null,
            rating = json.optString("average_rating").toDoubleOrNull()?.takeIf { it > 0 },
            screenshots = emptyList(),
            editorIntro = text(json, "editor_intro"),
            category = text(json, "cate_name_new") ?: text(json, "cate_name"),
            operator = text(json, "operator")
        )
    }

    /** 元件內的 itemData 逐一轉換，依包名去重。 */
    fun items(component: JSONObject?, limit: Int = Int.MAX_VALUE): List<StoreApp> {
        val array = component?.optJSONObject("data")?.optJSONArray("itemData") ?: return emptyList()
        val out = LinkedHashMap<String, StoreApp>()
        for (i in 0 until array.length()) {
            val app = array.optJSONObject(i)?.let(::item) ?: continue
            out.putIfAbsent(app.pkg, app)
            if (out.size >= limit) break
        }
        return out.values.toList()
    }
}
