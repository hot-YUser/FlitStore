package dev.yyblite.core.net

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** HTTP 狀態碼不在 2xx。 */
class HttpStatusException(val code: Int) : IOException("HTTP $code")

/** 商店回應的結構和預期不符（欄位缺失、找不到資料區塊）。 */
class StoreFormatException(message: String) : IOException(message)

/**
 * 全專案唯一的連線入口，只用 java.net：商店頁、評論、圖示、下載都從這裡開連線，
 * 逾時、UA、gzip 的規則因此只有一份。
 */
object Http {
    const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    /** 下載 APK 不可用瀏覽器 UA：Tencent EdgeOne 會對類瀏覽器流量下發 JS 驗證頁。 */
    const val DOWNLOAD_UA = "AndroidDownloadManager"

    fun open(
        url: String,
        userAgent: String = BROWSER_UA,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 20_000,
        range: String? = null,
        gzip: Boolean = true
    ): HttpURLConnection {
        // URI.toURL() 會拒絕商店偶爾出現的未編碼字元，所以維持寬鬆的 URL 建構子。
        @Suppress("DEPRECATION")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.setRequestProperty("User-Agent", userAgent)
        conn.setRequestProperty("Accept-Encoding", if (gzip) "gzip" else "identity")
        if (userAgent == BROWSER_UA) conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
        if (range != null) conn.setRequestProperty("Range", range)
        return conn
    }

    /** 2xx 才回傳（必要時解壓後的）串流，否則丟 [HttpStatusException]。 */
    fun body(conn: HttpURLConnection): InputStream {
        val code = conn.responseCode
        if (code !in 200..299) throw HttpStatusException(code)
        val gzip = conn.contentEncoding?.contains("gzip", ignoreCase = true) == true
        return if (gzip) GZIPInputStream(conn.inputStream) else conn.inputStream
    }

    fun getText(url: String, readTimeoutMs: Int = 20_000): String {
        val conn = open(url, readTimeoutMs = readTimeoutMs)
        try {
            return body(conn).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    fun getBytes(url: String, readTimeoutMs: Int = 15_000): ByteArray {
        val conn = open(url, readTimeoutMs = readTimeoutMs, gzip = false)
        try {
            return body(conn).use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    fun postJson(url: String, json: String, readTimeoutMs: Int = 25_000): String {
        val conn = open(url, readTimeoutMs = readTimeoutMs)
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            return body(conn).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
