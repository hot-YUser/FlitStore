package dev.yyblite.core.download

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.yyblite.core.net.Http
import dev.yyblite.core.store.LookupResult
import dev.yyblite.core.store.StoreClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.random.Random

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

    /** 前十幾個請求被限流、每第 9 個請求中途斷線：要續傳完成、降低併發，且不退回單連線。 */
    @Test
    fun survivesThrottlingAndDrops() {
        val body = Random(42).nextBytes(3 * 1024 * 1024)
        FakeCdn(body) { n -> if (n in 2..13) Mode.THROTTLE else if (n % 9 == 0) Mode.TRUNCATE else Mode.OK }.use { cdn ->
            val report = DownloadReport()
            val dest = File.createTempFile("flaky", ".apk")
            try {
                SegmentDownloader(16).download(listOf(cdn.url), md5(body), dest, CancelToken(), report) { _, _ -> }
                assertArrayEquals(body, dest.readBytes())
                assertTrue(report.throttled.get() > 0)
                assertTrue(report.retries.get() > 0)
                assertTrue(report.cutParallel in 1 until 16)
                assertFalse(report.sequential)
            } finally {
                dest.delete()
            }
        }
    }

    /** 一台鏡像對分段一律回驗證頁時，被擋的分段要換到另一台完成。 */
    @Test
    fun challengedSegmentsMoveToMirror() {
        val body = Random(7).nextBytes(2 * 1024 * 1024)
        FakeCdn(body) { n -> if (n == 1) Mode.OK else Mode.CHALLENGE }.use { bad ->
            FakeCdn(body) { Mode.OK }.use { good ->
                val report = DownloadReport()
                val dest = File.createTempFile("mirror", ".apk")
                try {
                    SegmentDownloader(8).download(listOf(bad.url, good.url), md5(body), dest, CancelToken(), report) { _, _ -> }
                    assertArrayEquals(body, dest.readBytes())
                    assertTrue(report.switches.get() > 0)
                    assertFalse(report.sequential)
                } finally {
                    dest.delete()
                }
            }
        }
    }

    /**
     * CDN 對同一個 IP 只放行 10 條同時連線（實測 EdgeOne 就是這樣）：
     * 併發要漸進加速，頂多在加倍的那一步被擋幾條，之後穩定在上限以下。
     */
    @Test
    fun rampUpStaysNearConnectionLimit() {
        val body = Random(11).nextBytes(2 * 1024 * 1024)
        FakeCdn(body, concurrencyLimit = 10, delayMs = 300) { Mode.OK }.use { cdn ->
            val report = DownloadReport()
            val dest = File.createTempFile("ramp", ".apk")
            try {
                SegmentDownloader(32).download(listOf(cdn.url), md5(body), dest, CancelToken(), report) { _, _ -> }
                assertArrayEquals(body, dest.readBytes())
                // 一口氣開滿 32 條會被擋 22 條；漸進加速最多衝到 16，被擋後穩定在 10 以下。
                // 假 CDN 的計數在連線收尾時會略為多算，被擋次數只要求遠低於一口氣開滿的情況。
                assertTrue("一次開太多：${cdn.peakActive}", cdn.peakActive <= 16)
                assertTrue("被擋太多次：$report", report.throttled.get() <= 16)
                assertTrue("併發沒砍到上限以下：$report", report.cutParallel in 1..10)
                assertFalse("不該退回單連線：$report", report.sequential)
            } finally {
                dest.delete()
            }
        }
    }

    /** 一台鏡像對分段一律斷線：失敗幾次、一個位元組都拿不到就放棄它，不要來回換。 */
    @Test
    fun deadMirrorIsAbandoned() {
        val body = Random(5).nextBytes(1024 * 1024)
        FakeCdn(body) { n -> if (n == 1) Mode.OK else Mode.DROP }.use { dead ->
            FakeCdn(body) { Mode.OK }.use { good ->
                val report = DownloadReport()
                val dest = File.createTempFile("dead", ".apk")
                try {
                    SegmentDownloader(8).download(listOf(dead.url, good.url), md5(body), dest, CancelToken(), report) { _, _ -> }
                    assertArrayEquals(body, dest.readBytes())
                    val mirror = report.mirror(URL(dead.url).authority)
                    assertTrue(mirror.unusable)
                    // 每段頂多在死鏡像上失敗一次（HttpURLConnection 自己還會悄悄重送一次 GET）。
                    assertTrue("死掉的鏡像被打太多次：$report", mirror.failures.get() <= 8 && dead.requests <= 2 * 8 + 1)
                } finally {
                    dest.delete()
                }
            }
        }
    }

    @Test
    fun ignoredRangeFallsBackToSingleConnection() {
        val body = Random(3).nextBytes(512 * 1024)
        FakeCdn(body) { Mode.IGNORE_RANGE }.use { cdn ->
            val report = DownloadReport()
            val dest = File.createTempFile("norange", ".apk")
            try {
                SegmentDownloader(8).download(listOf(cdn.url), md5(body), dest, CancelToken(), report) { _, _ -> }
                assertArrayEquals(body, dest.readBytes())
                assertTrue(report.sequential)
            } finally {
                dest.delete()
            }
        }
    }

    @Test
    fun persistentThrottlingFails() {
        FakeCdn(ByteArray(1024)) { Mode.THROTTLE }.use { cdn ->
            val dest = File.createTempFile("blocked", ".apk")
            try {
                assertThrows(ThrottledException::class.java) {
                    SegmentDownloader(4).download(listOf(cdn.url), null, dest, CancelToken(), DownloadReport()) { _, _ -> }
                }
            } finally {
                dest.delete()
            }
        }
    }

    private enum class Mode { OK, THROTTLE, CHALLENGE, TRUNCATE, IGNORE_RANGE, DROP }

    /**
     * 本機假 CDN：[mode] 依請求序號（從 1 起算）決定這次怎麼回應。
     * 同時連線超過 [concurrencyLimit] 立刻回 429；[delayMs] 是資料本體分段送完的總時間，
     * 像真的 CDN 一樣標頭很快回來、資料慢慢傳，連線才會真的疊起來。
     */
    private class FakeCdn(
        private val body: ByteArray,
        private val concurrencyLimit: Int = Int.MAX_VALUE,
        private val delayMs: Long = 0,
        private val mode: (Int) -> Mode
    ) : AutoCloseable {
        private val count = AtomicInteger()
        private val active = AtomicInteger()
        private val peak = AtomicInteger()
        private val pool = Executors.newCachedThreadPool()
        val requests: Int get() = count.get()
        val peakActive: Int get() = peak.get()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = pool
            createContext("/") { ex -> respond(ex) }
            start()
        }
        val url = "http://127.0.0.1:${server.address.port}/app.apk"

        private fun respond(ex: HttpExchange) {
            val now = active.incrementAndGet()
            peak.accumulateAndGet(now, ::maxOf)
            try {
                val range = ex.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.split('-')
                val current = if (now > concurrencyLimit) Mode.THROTTLE.also { count.incrementAndGet() } else mode(count.incrementAndGet())
                when (current) {
                    Mode.DROP -> return
                    Mode.THROTTLE -> ex.sendResponseHeaders(429, -1)
                    Mode.CHALLENGE -> send(ex, 200, "text/html", "<html>verify</html>".toByteArray())
                    Mode.IGNORE_RANGE -> send(ex, 200, APK, body)
                    Mode.OK, Mode.TRUNCATE -> {
                        if (range == null) return send(ex, 200, APK, body)
                        val start = range[0].toInt()
                        val end = minOf(range[1].toInt(), body.size - 1)
                        ex.responseHeaders.add("Content-Range", "bytes $start-$end/${body.size}")
                        ex.responseHeaders.add("Content-Type", APK)
                        val length = end - start + 1
                        ex.sendResponseHeaders(206, length.toLong())
                        val sent = if (current == Mode.TRUNCATE) length / 2 else length
                        trickle(ex, start, sent)
                    }
                }
            } catch (_: Exception) {
                // 故意少送位元組時伺服器端會抱怨，客戶端看到的就是斷線。
            } finally {
                active.decrementAndGet()
                runCatching { ex.close() }
            }
        }

        private fun trickle(ex: HttpExchange, start: Int, length: Int) {
            if (delayMs <= 0) return ex.responseBody.write(body, start, length)
            val chunk = (length + 3) / 4
            var offset = 0
            while (offset < length) {
                Thread.sleep(delayMs / 4)
                val n = minOf(chunk, length - offset)
                ex.responseBody.write(body, start + offset, n)
                ex.responseBody.flush()
                offset += n
            }
        }

        private fun send(ex: HttpExchange, code: Int, type: String, bytes: ByteArray) {
            ex.responseHeaders.add("Content-Type", type)
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.write(bytes)
        }

        override fun close() {
            server.stop(0)
            pool.shutdownNow()
        }

        private companion object {
            const val APK = "application/vnd.android.package-archive"
        }
    }

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

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
