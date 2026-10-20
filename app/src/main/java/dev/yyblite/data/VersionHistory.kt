package dev.yyblite.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 商店網頁沒有版本歷史端點，改由本機記錄「首次看到某商店版本的時間」，
 * 每次查詢成功時累積，形成可追溯的版本時間線。
 */
class VersionHistory(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("version_history", Context.MODE_PRIVATE)

    fun record(pkg: String, version: String, at: Long = System.currentTimeMillis()) {
        if (version.isBlank()) return
        val list = read(pkg).toMutableList()
        if (list.any { it.first == version }) return
        list.add(0, version to at)
        val trimmed = list.take(40)
        val array = JSONArray()
        trimmed.forEach { (v, t) ->
            array.put(JSONObject().put("v", v).put("t", t))
        }
        prefs.edit().putString(pkg, array.toString()).apply()
    }

    fun history(pkg: String): List<Pair<String, Long>> = read(pkg)

    private fun read(pkg: String): List<Pair<String, Long>> {
        val raw = prefs.getString(pkg, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Pair<String, Long>>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val v = obj.optString("v")
            if (v.isNotBlank()) out.add(v to obj.optLong("t", 0L))
        }
        return out
    }
}
