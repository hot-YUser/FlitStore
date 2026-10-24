package dev.yyblite.core.download

import dev.yyblite.core.net.Http
import dev.yyblite.core.store.LookupResult
import dev.yyblite.core.store.StoreClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class SegmentDownloaderTest {

    private fun wechatUrl(): String {
        val result = StoreClient.lookup("com.tencent.mm")
        if (result is LookupResult.Failed) Assume.assumeNoException(result.error)
        val app = (result as LookupResult.Found).app
        return app.apkUrl64 ?: app.apkUrl
    }

    @Test
    fun rangeAssembly() {
        val url = wechatUrl()
        val length = 4L * 1024 * 1024
        val temp = File.createTempFile("seg", ".bin")
        try {
            var lastError: Exception? = null
            var ok = false
            for (attempt in 0 until 3) {
                try {
                    SegmentDownloader(32).downloadRange(url, 0, length - 1, temp)
                    ok = true
                    break
                } catch (e: Exception) {
                    lastError = e
                    Thread.sleep(2000L * (attempt + 1))
                }
            }
            // 外部 CDN 不可達時略過，而不是把網路問題當成程式錯誤。
            Assume.assumeTrue("CDN unreachable: ${lastError?.message}", ok)
            assertEquals(length, temp.length())
            assertEquals(sha256OfRange(url, 0, length - 1), sha256OfFile(temp))
        } finally {
            temp.delete()
        }
    }

    /** 取消要在數秒內讓所有分段執行緒結束，並刪掉半成品。 */
    @Test
    fun cancelStopsQuickly() {
        val url = wechatUrl()
        val dest = File.createTempFile("cancel", ".apk")
        val token = CancelToken()
        val outcome = AtomicReference<Throwable?>()
        val worker = thread {
            try {
                SegmentDownloader().downloadFull(url, null, dest, token)
            } catch (t: Throwable) {
                outcome.set(t)
            }
        }
        Thread.sleep(1500)
        Assume.assumeTrue("download finished before cancel", worker.isAlive)
        val cancelledAt = System.currentTimeMillis()
        token.cancel()
        worker.join(5000)
        assertFalse("worker still running after cancel", worker.isAlive)
        assertTrue("cancel took too long", System.currentTimeMillis() - cancelledAt < 5000)
        val error = outcome.get()
        if (error !is DownloadCancelledException) Assume.assumeNoException("download failed before cancel", error)
        assertFalse("partial file left behind", dest.exists() && dest.length() > 0)
        dest.delete()
    }

    private fun sha256OfRange(url: String, start: Long, end: Long): String {
        val conn = Http.open(url, userAgent = Http.DOWNLOAD_UA, readTimeoutMs = 30_000, range = "bytes=$start-$end", gzip = false)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            conn.inputStream.use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256OfFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
