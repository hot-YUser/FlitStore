package dev.yyblite.core.update

/**
 * 以數字段比較版本字串，忽略非數字分隔符。
 * "9.04.65" 與 "9.4.65" 等值；"1.10.0" > "1.9.9"。
 * 結尾的 -alpha／-beta／-rc 是預覽版：版號相同時正式版較新，"1.1.1" > "1.1.1-rc.1" > "1.1.1-beta.2" > "1.1.1-beta.1"。
 */
object VersionOrder {
    private val SPLIT = Regex("[^0-9]+")
    private val PRERELEASE = Regex("[-.]?(alpha|beta|rc)[-.]?(\\d*)$", RegexOption.IGNORE_CASE)
    private val STAGES = listOf("alpha", "beta", "rc")

    fun compare(a: String?, b: String?): Int {
        val pa = a?.let { PRERELEASE.find(it) }
        val pb = b?.let { PRERELEASE.find(it) }
        val base = numeric(pa?.let { a.substring(0, it.range.first) } ?: a, pb?.let { b.substring(0, it.range.first) } ?: b)
        if (base != 0 || (pa == null && pb == null)) return base
        if (pa == null) return 1
        if (pb == null) return -1
        val stage = stage(pa).compareTo(stage(pb))
        return if (stage != 0) stage else numeric(pa.groupValues[2], pb.groupValues[2])
    }

    private fun stage(match: MatchResult) = STAGES.indexOf(match.groupValues[1].lowercase())

    private fun numeric(a: String?, b: String?): Int {
        val left = parts(a)
        val right = parts(b)
        val n = maxOf(left.size, right.size)
        for (i in 0 until n) {
            val l = left.getOrElse(i) { 0 }
            val r = right.getOrElse(i) { 0 }
            if (l != r) return if (l > r) 1 else -1
        }
        return 0
    }

    private fun parts(version: String?): List<Int> {
        if (version.isNullOrBlank()) return listOf(0)
        val split = version.split(SPLIT).filter { it.isNotEmpty() }
        if (split.isEmpty()) return listOf(0)
        return split.map { it.toIntOrNull() ?: 0 }
    }
}
