package dev.yyblite.core.update

/**
 * 以數字段比較版本字串，忽略非數字分隔符。
 * "9.04.65" 與 "9.4.65" 等值；"1.10.0" > "1.9.9"。
 */
object VersionOrder {
    private val SPLIT = Regex("[^0-9]+")

    fun compare(a: String?, b: String?): Int {
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
