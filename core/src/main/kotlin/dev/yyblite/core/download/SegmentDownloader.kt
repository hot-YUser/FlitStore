package dev.yyblite.core.download

import dev.yyblite.core.net.Http
import dev.yyblite.core.net.HttpStatusException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * 一次下載的經過：用了哪台 CDN、被限流幾次、併發降到多少。
 * 下載慢或失敗時寫進診斷記錄，使用者複製回報就看得出卡在哪裡。
 */
class DownloadReport {
    @Volatile var host = ""
    @Volatile var address = ""
    @Volatile var server = ""
    @Volatile var trace = ""
    @Volatile var segments = 0
    @Volatile var lowestParallel = 0
    @Volatile var sequential = false
    val throttled = AtomicInteger()
    val retries = AtomicInteger()
    val switches = AtomicInteger()

    val degraded: Boolean get() = sequential || throttled.get() > 0

    override fun toString(): String = buildString {
        append("host=").append(host)
        if (address.isNotEmpty()) append(" ip=").append(address)
        if (server.isNotEmpty()) append(" server=").append(server)
        append(" seg=").append(segments)
        if (lowestParallel in 1 until segments) append(" par>=").append(lowestParallel)
        append(" throttled=").append(throttled.get())
        append(" retries=").append(retries.get())
        if (switches.get() > 0) append(" mirror=").append(switches.get())
        if (sequential) append(" sequential")
        if (trace.isNotEmpty()) append(" trace=").append(trace)
    }
}

/**
 * 以 HTTP Range 多段並行下載單一檔案。
 * 每段各自持有 RandomAccessFile；斷線從斷點續傳，被限流時降低併發並換鏡像重試，
 * 已下載的部分都保留。
 */
class SegmentDownloader(private val segments: Int = 0) {

    /** 0 = 依檔案大小自動決定：約每 2.4MB 一段，32–96 段（多輪實測 96 段最穩）。 */
    private fun autoSegments(total: Long): Int =
        (total / 2_400_000L).toInt().coerceIn(32, 96)

    fun totalSize(url: String, token: CancelToken = CancelToken(), report: DownloadReport? = null): Long {
        val conn = open(url, range = "bytes=0-0")
        token.track(conn)
        try {
            val code = conn.responseCode
            report?.let { describe(conn, url, it) }
            if (code == 206) {
                val contentRange = conn.getHeaderField("Content-Range")
                    ?: throw IOException("missing Content-Range")
                val total = contentRange.substringAfterLast('/').trim().toLongOrNull() ?: -1
                if (total <= 0) throw IOException("unknown file size")
                return total
            }
            if (isChallenge(conn, code)) throw ThrottledException("CDN challenge page (HTTP $code)")
            if (code in 200..299) throw RangeUnsupportedException("CDN ignored Range (HTTP $code)")
            if (code == 403 || code == 429) throw ThrottledException("HTTP $code")
            throw HttpStatusException(code)
        } finally {
            token.untrack(conn)
            conn.disconnect()
        }
    }

    fun downloadRange(
        url: String,
        start: Long,
        endInclusive: Long,
        dest: File,
        segments: Int = this.segments,
        token: CancelToken = CancelToken(),
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }
    ): File {
        if (endInclusive < start) throw IOException("invalid range")
        val count = (if (segments > 0) segments else autoSegments(endInclusive - start + 1)).coerceAtLeast(1)
        dest.parentFile?.mkdirs()
        fetchParts(listOf(url), start, endInclusive, dest, count, token, DownloadReport(), onProgress)
        return dest
    }

    fun downloadFull(
        url: String,
        expectedMd5: String?,
        dest: File,
        token: CancelToken = CancelToken(),
        report: DownloadReport = DownloadReport(),
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }
    ): File = download(Cdn.candidates(url), expectedMd5, dest, token, report, onProgress)

    /** [urls] 是同一個檔案的所有鏡像，第一個優先。 */
    internal fun download(
        urls: List<String>,
        expectedMd5: String?,
        dest: File,
        token: CancelToken,
        report: DownloadReport,
        onProgress: (done: Long, total: Long) -> Unit
    ): File {
        dest.parentFile?.mkdirs()
        var throttled: ThrottledException? = null
        for ((index, url) in urls.withIndex()) {
            val total = try {
                probe(url, token, report)
            } catch (e: ThrottledException) {
                throttled = e
                continue
            } catch (e: RangeUnsupportedException) {
                return sequential(url, expectedMd5, dest, token, report, onProgress)
            }
            // 探測成功的那台排第一，其餘留給被限流的分段輪換。
            val ordered = urls.drop(index) + urls.take(index)
            dest.delete()
            RandomAccessFile(dest, "rw").use { it.setLength(total) }
            try {
                fetchParts(ordered, 0, total - 1, dest, if (segments > 0) segments else autoSegments(total), token, report, onProgress)
            } catch (e: ThrottledException) {
                return sequential(url, expectedMd5, dest, token, report, onProgress)
            } catch (e: RangeUnsupportedException) {
                return sequential(url, expectedMd5, dest, token, report, onProgress)
            }
            verifyMd5(dest, expectedMd5, token)
            return dest
        }
        throw throttled ?: ThrottledException("store CDN is blocking requests")
    }

    private fun probe(url: String, token: CancelToken, report: DownloadReport): Long {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return totalSize(url, token, report)
            } catch (e: ThrottledException) {
                report.throttled.incrementAndGet()
                if (attempt >= 3) throw ThrottledException("store CDN is blocking requests")
                token.sleep(700L * attempt)
            }
        }
    }

    private class Part(val start: Long, val end: Long) {
        /** 下一個要抓的位元組；重試從這裡續傳。 */
        var pos = start
        var mirror = 0
        var throttles = 0
        var failures = 0
        var notBefore = 0L
    }

    /**
     * 分段排進佇列，[count] 條執行緒輪流取用。被限流時併發每次砍到四分之一（兩秒內只砍一次），
     * 多出來的執行緒做完手上那段就退場；該段退避後換下一台鏡像重排。
     */
    private fun fetchParts(
        urls: List<String>,
        start: Long,
        endInclusive: Long,
        dest: File,
        count: Int,
        token: CancelToken,
        report: DownloadReport,
        onProgress: (done: Long, total: Long) -> Unit
    ) {
        val length = endInclusive - start + 1
        val partLen = length / count
        if (partLen <= 0) throw IOException("range too small to split")

        val queue = ArrayDeque<Part>(count)
        for (i in 0 until count) {
            val segStart = start + i * partLen
            queue.add(Part(segStart, if (i == count - 1) endInclusive else segStart + partLen - 1))
        }
        val lock = Object()
        var remaining = queue.size
        var running = 0
        var limit = queue.size
        var lastCut = 0L
        var failure: Throwable? = null
        val written = AtomicLong(0)
        val lastEmit = AtomicLong(0)
        report.segments = count
        report.lowestParallel = count

        fun take(): Part? = synchronized(lock) {
            var next: Part? = null
            while (failure == null && !token.isCancelled && remaining > 0 && running < limit) {
                val now = System.currentTimeMillis()
                val ready = queue.firstOrNull { it.notBefore <= now }
                if (ready != null) {
                    queue.remove(ready)
                    running++
                    next = ready
                    break
                }
                val wait = queue.minOfOrNull { it.notBefore - now } ?: 200L
                lock.wait(wait.coerceIn(10L, 200L))
            }
            next
        }

        fun settle(part: Part, error: Throwable?) = synchronized(lock) {
            running--
            when (error) {
                null -> remaining--
                is ThrottledException -> {
                    report.throttled.incrementAndGet()
                    part.throttles++
                    if (part.throttles > MAX_THROTTLES) {
                        if (failure == null) failure = ThrottledException("CDN throttled (${report.throttled.get()} hits)")
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - lastCut >= 2000 && limit > 1) {
                            limit = maxOf(1, limit / 4)
                            lastCut = now
                            report.lowestParallel = minOf(report.lowestParallel, limit)
                        }
                        rotate(part, urls, report)
                        part.notBefore = now + minOf(600L * part.throttles, 5000L)
                        queue.add(part)
                    }
                }
                is RangeUnsupportedException, is DownloadCancelledException -> if (failure == null) failure = error
                is IOException -> {
                    report.retries.incrementAndGet()
                    if (part.failures >= MAX_FAILURES) {
                        if (failure == null) failure = IOException("segment ${part.start}-${part.end} failed after retries: ${error.message}", error)
                    } else {
                        if (part.failures >= 2) rotate(part, urls, report)
                        part.notBefore = System.currentTimeMillis() + 500L * part.failures
                        queue.add(part)
                    }
                }
                else -> if (failure == null) failure = error
            }
            lock.notifyAll()
        }

        val workers = ArrayList<Thread>(count)
        repeat(count) { i ->
            val worker = thread(start = false, isDaemon = true, name = "seg-$i") {
                while (true) {
                    val part = take() ?: break
                    val error = try {
                        fetch(part, urls[part.mirror % urls.size], dest, written, lastEmit, length, onProgress, token)
                        null
                    } catch (t: Throwable) {
                        t
                    }
                    if (error is DownloadCancelledException) token.cancel()
                    settle(part, error)
                }
            }
            workers.add(worker)
            worker.start()
        }
        workers.forEach { it.join() }

        if (token.isCancelled) {
            dest.delete()
            throw DownloadCancelledException()
        }
        failure?.let {
            dest.delete()
            throw it as? IOException ?: IOException(it.message ?: "download failed", it)
        }
        if (written.get() != length) throw IOException("byte count mismatch ${written.get()}/$length")
    }

    private fun rotate(part: Part, urls: List<String>, report: DownloadReport) {
        if (urls.size < 2) return
        part.mirror++
        report.switches.incrementAndGet()
    }

    /** 抓 [part] 從 pos 到結尾；中途斷線時 pos 停在已寫入的位置，下次續傳。 */
    private fun fetch(
        part: Part,
        url: String,
        dest: File,
        written: AtomicLong,
        lastEmit: AtomicLong,
        total: Long,
        onProgress: (Long, Long) -> Unit,
        token: CancelToken
    ) {
        token.check()
        val from = part.pos
        val conn = open(url, range = "bytes=$from-${part.end}")
        token.track(conn)
        try {
            val code = conn.responseCode
            if (isChallenge(conn, code)) throw ThrottledException("CDN challenge page (HTTP $code)")
            if (code == 403 || code == 429) throw ThrottledException("HTTP $code")
            if (code !in 200..299) throw HttpStatusException(code)
            if (code != 206) throw RangeUnsupportedException("CDN ignored Range (HTTP $code)")
            RandomAccessFile(dest, "rw").use { raf ->
                raf.seek(from)
                val buffer = ByteArray(256 * 1024)
                conn.inputStream.use { input ->
                    while (part.pos <= part.end) {
                        token.check()
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), part.end - part.pos + 1).toInt())
                        if (read < 0) break
                        raf.write(buffer, 0, read)
                        part.pos += read
                        val done = written.addAndGet(read.toLong())
                        val now = System.currentTimeMillis()
                        if (now - lastEmit.get() >= 250) {
                            lastEmit.set(now)
                            onProgress(done, total)
                        }
                    }
                }
            }
            if (part.pos <= part.end) throw IOException("segment ended early, ${part.end - part.pos + 1} bytes missing")
        } catch (e: IOException) {
            if (part.pos > from) part.failures = 0
            if (e !is ThrottledException && e !is RangeUnsupportedException && e !is DownloadCancelledException) {
                if (token.isCancelled) throw DownloadCancelledException()
                part.failures++
            }
            throw e
        } finally {
            token.untrack(conn)
            conn.disconnect()
        }
    }

    /** CDN 不支援 Range，或多段一直被限流時的最後手段：單連線循序下載。 */
    private fun sequential(
        url: String,
        expectedMd5: String?,
        dest: File,
        token: CancelToken,
        report: DownloadReport,
        onProgress: (done: Long, total: Long) -> Unit
    ): File {
        report.sequential = true
        var attempt = 0
        while (true) {
            attempt++
            dest.delete()
            dest.parentFile?.mkdirs()
            try {
                return sequentialOnce(url, expectedMd5, dest, token, onProgress)
            } catch (e: IOException) {
                dest.delete()
                if (token.isCancelled) throw DownloadCancelledException()
                if (attempt >= 3) throw e
                report.retries.incrementAndGet()
                token.sleep(1000L * attempt)
            }
        }
    }

    private fun sequentialOnce(
        url: String,
        expectedMd5: String?,
        dest: File,
        token: CancelToken,
        onProgress: (done: Long, total: Long) -> Unit
    ): File {
        val conn = open(url)
        token.track(conn)
        try {
            val code = conn.responseCode
            if (isChallenge(conn, code)) throw ThrottledException("CDN challenge page")
            if (code !in 200..299) throw HttpStatusException(code)
            val total = conn.contentLengthLong
            var done = 0L
            var lastEmit = 0L
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        token.check()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= 250) {
                            lastEmit = now
                            onProgress(done, total)
                        }
                    }
                }
            }
            if (done == 0L) throw IOException("empty response (HTTP $code)")
            if (total > 0 && done != total) throw IOException("byte count mismatch $done/$total")
            onProgress(done, total)
            verifyMd5(dest, expectedMd5, token)
            return dest
        } finally {
            token.untrack(conn)
            conn.disconnect()
        }
    }

    /** 記下回應的伺服器、快取狀態與追蹤編號，回報時能請 CDN 端查同一筆請求。 */
    private fun describe(conn: HttpURLConnection, url: String, report: DownloadReport) {
        val host = runCatching { URL(url).host }.getOrDefault("")
        report.host = host
        report.address = runCatching { InetAddress.getByName(host).hostAddress }.getOrNull().orEmpty()
        report.server = listOfNotNull(
            conn.getHeaderField("Server"),
            conn.getHeaderField("X-Cache-Lookup") ?: conn.getHeaderField("EO-Cache-Status")
        ).joinToString("/")
        report.trace = conn.getHeaderField("X-NWS-LOG-UUID") ?: conn.getHeaderField("EO-LOG-UUID") ?: ""
    }

    private fun open(url: String, range: String? = null): HttpURLConnection =
        Http.open(
            url,
            userAgent = Http.DOWNLOAD_UA,
            connectTimeoutMs = 15_000,
            readTimeoutMs = 30_000,
            range = range,
            gzip = false
        )

    private fun isChallenge(conn: HttpURLConnection, code: Int): Boolean =
        code in 200..299 && conn.contentType?.contains("text/html", ignoreCase = true) == true

    private fun verifyMd5(file: File, expected: String?, token: CancelToken) {
        if (expected.isNullOrBlank()) return
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                token.check()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expected, ignoreCase = true)) {
            val bytes = file.length()
            file.delete()
            throw ChecksumException(actual, expected, bytes)
        }
    }

    private companion object {
        /** 同一段最多被限流幾次才放棄多段、改走單連線。 */
        const val MAX_THROTTLES = 6

        /** 同一段連續幾次斷線且毫無進度才算失敗；有進度就歸零。 */
        const val MAX_FAILURES = 4
    }
}
