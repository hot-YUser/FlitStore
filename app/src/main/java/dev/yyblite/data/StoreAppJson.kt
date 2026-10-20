package dev.yyblite.data

import org.json.JSONArray
import org.json.JSONObject

/** StoreApp 與快取用的 JSON 互轉；不含 Android 依賴，可單測。 */
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
    }

    fun decode(json: JSONObject): StoreApp? {
        val pkg = json.optString("pkg")
        val versionName = json.optString("versionName")
        val apkUrl = json.optString("apkUrl")
        if (pkg.isBlank() || versionName.isBlank() || apkUrl.isBlank()) return null
        return StoreApp(
            pkg = pkg,
            name = json.optString("name").ifBlank { pkg },
            versionName = versionName,
            versionCode = json.optString("versionCode").takeIf { it.isNotBlank() && it != "null" },
            apkUrl = apkUrl,
            apkUrl64 = json.optString("apkUrl64").takeIf { it.isNotBlank() && it != "null" },
            apkMd5 = json.optString("apkMd5"),
            signatureMd5 = json.optString("signatureMd5").takeIf { it.isNotBlank() && it != "null" },
            sizeBytes = json.optLong("sizeBytes", -1),
            sizeDesc = json.optString("sizeDesc").takeIf { it.isNotBlank() && it != "null" },
            iconUrl = json.optString("iconUrl").takeIf { it.isNotBlank() && it != "null" },
            developer = json.optString("developer").takeIf { it.isNotBlank() && it != "null" },
            downloadDesc = json.optString("downloadDesc").takeIf { it.isNotBlank() && it != "null" },
            rating = if (json.isNull("rating")) null else json.optDouble("rating"),
            screenshots = json.optJSONArray("screenshots")?.let { array ->
                (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
            } ?: emptyList(),
            editorIntro = json.optString("editorIntro").takeIf { it.isNotBlank() && it != "null" }
        )
    }
}
