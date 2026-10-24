package dev.yyblite.core.store

import org.json.JSONArray
import org.json.JSONObject

/** StoreApp 與快取用 JSON 互轉。 */
object StoreAppJson {

    fun encode(app: StoreApp): JSONObject = JSONObject().apply {
        put("pkg", app.pkg)
        put("name", app.name)
        put("versionName", app.versionName)
        put("versionCode", app.versionCode ?: JSONObject.NULL)
        put("apkUrl", app.apkUrl)
        put("apkUrl64", app.apkUrl64 ?: JSONObject.NULL)
        put("apkMd5", app.apkMd5)
        put("signatureMd5", app.signatureMd5 ?: JSONObject.NULL)
        put("sizeBytes", app.sizeBytes)
        put("sizeDesc", app.sizeDesc ?: JSONObject.NULL)
        put("iconUrl", app.iconUrl ?: JSONObject.NULL)
        put("developer", app.developer ?: JSONObject.NULL)
        put("downloadDesc", app.downloadDesc ?: JSONObject.NULL)
        put("rating", app.rating ?: JSONObject.NULL)
        put("editorIntro", app.editorIntro ?: JSONObject.NULL)
        put("screenshots", JSONArray(app.screenshots))
        put("official", app.official)
        put("icp", app.icp ?: JSONObject.NULL)
        put("ratingCount", app.ratingCount)
        put("category", app.category ?: JSONObject.NULL)
        put("operator", app.operator ?: JSONObject.NULL)
        put("detailed", app.detailed)
    }

    /** 缺包名、版本或下載連結時回 null（快取只存可直接下載的完整資料）。 */
    fun decode(json: JSONObject): StoreApp? {
        val pkg = json.optString("pkg")
        val versionName = json.optString("versionName")
        val apkUrl = json.optString("apkUrl")
        if (pkg.isBlank() || versionName.isBlank() || apkUrl.isBlank()) return null
        return StoreApp(
            pkg = pkg,
            name = json.optString("name").ifBlank { pkg },
            versionName = versionName,
            versionCode = text(json, "versionCode"),
            apkUrl = apkUrl,
            apkUrl64 = text(json, "apkUrl64"),
            apkMd5 = json.optString("apkMd5"),
            signatureMd5 = text(json, "signatureMd5"),
            sizeBytes = json.optLong("sizeBytes", -1),
            sizeDesc = text(json, "sizeDesc"),
            iconUrl = text(json, "iconUrl"),
            developer = text(json, "developer"),
            downloadDesc = text(json, "downloadDesc"),
            rating = if (json.isNull("rating")) null else json.optDouble("rating").takeIf { !it.isNaN() },
            screenshots = json.optJSONArray("screenshots")?.let { array ->
                (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
            } ?: emptyList(),
            editorIntro = text(json, "editorIntro"),
            official = json.optBoolean("official", false),
            icp = text(json, "icp"),
            ratingCount = json.optLong("ratingCount", 0),
            category = text(json, "category"),
            operator = text(json, "operator"),
            detailed = json.optBoolean("detailed", false)
        )
    }

    private fun text(json: JSONObject, key: String): String? =
        json.optString(key).takeIf { it.isNotBlank() && it != "null" }
}
