package dev.yyblite.core.store

import java.net.URLDecoder

/**
 * 從「打開應用商店」的連結取出套件名稱：應用寶網頁「安裝」按鈕送出的 tmast://appdetails、
 * tmast://gamedetail、tmast://appdemo，Android 通用的 market://details，以及 sj.qq.com/appdetail/… 與
 * a.app.qq.com/o/simple.jsp 網址。認不得就回 null。
 */
object StoreLink {
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    private val APP_ID = Regex("^\\d{1,12}$")
    private val TMAST_HOSTS = setOf("appdetails", "gamedetail", "appdemo", "found")

    fun packageName(link: String): String? {
        val (scheme, authority, path, query) = split(link) ?: return null
        val pkg = when (scheme) {
            "tmast" -> when (authority) {
                "appdetails", "gamedetail" -> query["pname"] ?: query["pkgname"]
                // 網頁在瀏覽器裡用的格式：params 是逗號分隔的 key:value，其中一項是 pkgName。
                "appdemo" -> query["params"]?.split(',')
                    ?.firstOrNull { it.trim().startsWith("pkgName:", ignoreCase = true) }
                    ?.substringAfter(':')
                else -> null
            }
            "market" -> if (authority == "details") query["id"] else null
            "http", "https" -> when {
                authority == "sj.qq.com" && path.startsWith("/appdetail/") -> path.removePrefix("/appdetail/").substringBefore('/')
                authority == "a.app.qq.com" && path == "/o/simple.jsp" -> query["pkgname"]
                else -> null
            }
            else -> null
        }
        return pkg?.trim()?.takeIf { PACKAGE.matches(it) }
    }

    /**
     * 連結裡沒有套件名稱、只有應用寶內部編號時取出編號，例如網頁「官方下载」實際送出的
     * `tmast://found?…&target_param_appid=12127266`。交給 [StoreClient.packageForAppId] 換成套件名稱。
     */
    fun appId(link: String): String? {
        val (scheme, authority, _, query) = split(link) ?: return null
        if (scheme != "tmast" || authority !in TMAST_HOSTS) return null
        return (query["target_param_appid"] ?: query["appid"])?.trim()?.takeIf { APP_ID.matches(it) }
    }

    private data class Parts(val scheme: String, val authority: String, val path: String, val query: Map<String, String>)

    private fun split(link: String): Parts? {
        val text = link.trim()
        val scheme = text.substringBefore("://", "").lowercase()
        if (scheme.isEmpty()) return null
        val rest = text.substringAfter("://").substringBefore('#')
        val authority = rest.substringBefore('?').substringBefore('/').lowercase()
        val path = "/" + rest.substringBefore('?').substringAfter('/', "")
        return Parts(scheme, authority, path, params(rest.substringAfter('?', "")))
    }

    /** 查詢字串轉成小寫鍵的 Map；同名參數取第一個。 */
    private fun params(query: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val key = pair.substringBefore('=').lowercase()
            val value = runCatching { URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") }.getOrNull() ?: continue
            out.putIfAbsent(key, value)
        }
        return out
    }
}
