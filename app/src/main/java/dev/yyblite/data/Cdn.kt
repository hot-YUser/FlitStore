package dev.yyblite.data

/**
 * 應用寶 APK 由 `imtt.dd.qq.com` / `imtt2.dd.qq.com` 等鏡像同時提供。
 * 某一台回 EdgeOne 驗證頁時，換另一台通常可取得同一路徑。
 */
object Cdn {
    private val HOST = Regex("//(imtt\\d*\\.dd\\.qq\\.com)")
    private val MIRRORS = listOf("imtt.dd.qq.com", "imtt2.dd.qq.com")

    fun candidates(url: String): List<String> {
        val match = HOST.find(url) ?: return listOf(url)
        val current = match.groupValues[1]
        val out = ArrayList<String>(MIRRORS.size)
        out.add(url)
        for (mirror in MIRRORS) {
            if (mirror != current) out.add(url.replaceRange(match.range, "//$mirror"))
        }
        return out
    }
}
