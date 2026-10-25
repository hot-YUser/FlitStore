package dev.yyblite.core.download

import dev.yyblite.core.net.Http
import dev.yyblite.core.net.HttpStatusException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException
import kotlin.concurrent.thread

/**
 * 一次下載的經過：用了哪台 CDN、被限流幾次、併發升到多少又被砍到多少，以及每台 CDN 的實際表現。
 * 下載慢或失敗時寫進診斷記錄，使用者複製回報就看得出卡在哪裡。
 */
class DownloadReport {
    @Volatile var host = ""
    @Volatile var address = ""
    @Volatile var server = ""
    @Volatile var trace = ""
    @Volatile var segments = 0
    @Volatile var peakParallel = 0
    @Volatile var cutParallel = 0
    @Volatile var sequential = false
    val throttled = AtomicInteger()
    val retries = AtomicInteger()
    val switches = AtomicInteger()
    private val mirrors = ConcurrentHashMap<String, Mirror>()

    /** 單台 CDN 的表現：拿到的位元組、連線實際傳輸的時間（換算單條連線速度）、各種錯誤次數。 */
    class Mirror {
        val bytes = AtomicLong()
        val busyMs = AtomicLong()
        val failures = AtomicInteger()
        val errors = ConcurrentHashMap<String, AtomicInteger>()

        /** 連續失敗而且一個位元組都沒拿到：這個網路連不上它，之後不再把分段換過去。 */
        val unusable: Boolean get() = bytes.get() == 0L && failures.get() >= 3

        fun error(kind: String) {
            errors.getOrPut(kind) { AtomicInteger() }.incrementAndGet()
        }
    }

    fun mirror(name: String): Mirror = mirrors.getOrPut(name) { Mirror() }

    val degraded: Boolean get() = sequential || throttled.get() > 0

    override fun toString(): String = buildString {
        append("host=").append(host)
        if (address.isNotEmpty()) append(" ip=").append(address)
        if (server.isNotEmpty()) append(" server=").append(server)
        append(" seg=").append(segments)
        if (peakParallel > 0) append(" par=").append(peakParallel)
        if (cutParallel > 0) append("->").append(cutParallel)
        append(" throttled=").append(throttled.get())
        append(" retries=").append(retries.get())
        if (switches.get() > 0) append(" mirror=").append(switches.get())
        if (sequential) append(" sequential")
        if (trace.isNotEmpty()) append(" trace=").append(trace)
        for ((name, m) in mirrors) {
            if (m.bytes.get() == 0L && m.errors.isEmpty()) continue
            append(" | ").append(name.removeSuffix(".dd.qq.com")).append(' ')
            append(String.format(Locale.US, "%.1fMB", m.bytes.get() / 1048576.0))
            val ms = m.busyMs.get()
            if (ms > 0) append(' ').append(m.bytes.get() * 1000 / ms / 1024).append("KB/s/conn")
            for ((kind, n) in m.errors) append(' ').append(kind).append('x').append(n.get())
            if (m.unusable) append(" unusable")
        }
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
                report.mirror(authority(url)).error(kindOf(e))
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
        /** 上一次嘗試用的鏡像與拿到的位元組，結算時記到該鏡像頭上。 */
        var used = 0
        var lastBytes = 0L
    }

    /**
     * 分段排進佇列，[count] 條執行緒輪流取用。併發從 [START_PARALLEL] 起跳，沒被擋、而且這一批大多已經在傳資料時，
     * 每 [RAMP_MS] 加倍一次，
     * 不一次開滿：CDN 對同一個 IP 同時的連線數有上限，一口氣開滿會被擋一大片。
     * 被限流就停止加速，砍到當下真正在傳資料的連線數（兩秒內只砍一次），多出來的執行緒做完手上那段就退場；
     * 該段退避後換下一台鏡像重排，但連不上的鏡像不再換過去。
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
        val mirrors = urls.map { report.mirror(authority(it)) }
        val lock = Object()
        var remaining = queue.size
        var running = 0
        var limit = minOf(count, START_PARALLEL)
        var ramping = limit < count
        var lastRaise = System.currentTimeMillis()
        var lastCut = 0L
        var failure: Throwable? = null
        val written = AtomicLong(0)
        val lastEmit = AtomicLong(0)
        val streaming = AtomicInteger(0)
        report.segments = count
        report.peakParallel = limit

        fun take(): Part? = synchronized(lock) {
            var next: Part? = null
            while (failure == null && !token.isCancelled && remaining > 0) {
                val now = System.currentTimeMillis()
                // 加倍前要先看到這一批大多真的開始傳資料：延遲高的網路自然等回應回來才加速，不會衝過頭。
                if (ramping && now - lastRaise >= RAMP_MS && streaming.get() * 4 >= limit * 3) {
                    limit = minOf(count, limit * 2)
                    lastRaise = now
                    ramping = limit < count
                    report.peakParallel = maxOf(report.peakParallel, limit)
                }
                if (running >= limit) {
                    // 還在加速就等下一次加倍；併發已被砍或已到上限，多出來的執行緒退場。
                    if (!ramping) break
                    lock.wait((RAMP_MS - (now - lastRaise)).coerceIn(10L, RAMP_MS))
                    continue
                }
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
            val mirror = mirrors[part.used]
            when (error) {
                null -> remaining--
                is ThrottledException -> {
                    report.throttled.incrementAndGet()
                    mirror.error(kindOf(error))
                    part.throttles++
                    if (part.throttles > MAX_THROTTLES) {
                        if (failure == null) failure = ThrottledException("CDN throttled (${report.throttled.get()} hits)")
                    } else {
                        val now = System.currentTimeMillis()
                        ramping = false
                        if (now - lastCut >= 2000 && limit > 1) {
                            // 當下真的在傳資料的連線數就是 CDN 肯放行的數量；加速過頭也能一次砍回來。
                            limit = maxOf(1, minOf(limit / 2, streaming.get()))
                            lastCut = now
                            report.cutParallel = limit
                        }
                        rotate(part, mirrors, report)
                        part.notBefore = now + minOf(600L * part.throttles, 5000L)
                        queue.add(part)
                    }
                }
                is RangeUnsupportedException, is DownloadCancelledException -> if (failure == null) failure = error
                is IOException -> {
                    report.retries.incrementAndGet()
                    mirror.error(kindOf(error))
                    if (part.lastBytes == 0L) mirror.failures.incrementAndGet()
                    if (part.failures >= MAX_FAILURES) {
                        if (failure == null) failure = IOException("segment ${part.start}-${part.end} failed after retries: ${error.message}", error)
                    } else {
                        if (part.failures >= 2 || mirror.unusable) rotate(part, mirrors, report)
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
                    // 分到的鏡像已確定連不上時，改用還能用的那台。
                    if (mirrors[part.mirror % mirrors.size].unusable) {
                        mirrors.indexOfFirst { !it.unusable }.takeIf { it >= 0 }?.let { part.mirror = it }
                    }
                    part.used = part.mirror % mirrors.size
                    val error = try {
                        fetch(part, urls[part.used], mirrors[part.used], streaming, dest, written, lastEmit, length, onProgress, token)
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
        // 卡在建立連線的執行緒打不斷（disconnect 對 connect 無效），取消後最多再等一秒就回報；
        // 它們醒來時 token.track 會直接丟出取消，不會再寫檔。
        var graceUntil = 0L
        for (worker in workers) {
            while (worker.isAlive) {
                if (!token.isCancelled) {
                    worker.join(200)
                    continue
                }
                if (graceUntil == 0L) graceUntil = System.currentTimeMillis() + 1000
                val left = graceUntil - System.currentTimeMillis()
                if (left <= 0) break
                worker.join(left)
            }
        }

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

    /** 換到下一台鏡像；下一台已確定連不上就留在原地。 */
    private fun rotate(part: Part, mirrors: List<DownloadReport.Mirror>, report: DownloadReport) {
        if (mirrors.size < 2) return
        val next = (part.mirror + 1) % mirrors.size
        if (mirrors[next].unusable) return
        part.mirror = next
        report.switches.incrementAndGet()
    }

    /** 抓 [part] 從 pos 到結尾；中途斷線時 pos 停在已寫入的位置，下次續傳。 */
    private fun fetch(
        part: Part,
        url: String,
        mirror: DownloadReport.Mirror,
        streaming: AtomicInteger,
        dest: File,
        written: AtomicLong,
        lastEmit: AtomicLong,
        total: Long,
        onProgress: (Long, Long) -> Unit,
        token: CancelToken
    ) {
        token.check()
        val from = part.pos
        var startedAt = 0L
        val conn = open(url, range = "bytes=$from-${part.end}")
        token.track(conn)
        try {
            val code = conn.responseCode
            if (isChallenge(conn, code)) throw ThrottledException("CDN challenge page (HTTP $code)")
            if (code == 403 || code == 429) throw ThrottledException("HTTP $code")
            if (code !in 200..299) throw HttpStatusException(code)
            if (code != 206) throw RangeUnsupportedException("CDN ignored Range (HTTP $code)")
            startedAt = System.nanoTime()
            streaming.incrementAndGet()
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
            part.lastBytes = part.pos - from
            mirror.bytes.addAndGet(part.lastBytes)
            if (startedAt > 0) {
                streaming.decrementAndGet()
                mirror.busyMs.addAndGet((System.nanoTime() - startedAt) / 1_000_000)
            }
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

    /** 逾時刻意偏短：斷線會從斷點續傳，早點放棄一條卡住的連線比空等便宜。 */
    private fun open(url: String, range: String? = null): HttpURLConnection =
        Http.open(
            url,
            userAgent = Http.DOWNLOAD_UA,
            connectTimeoutMs = 8_000,
            readTimeoutMs = 15_000,
            range = range,
            gzip = false
        )

    private fun authority(url: String): String = runCatching { URL(url).authority }.getOrNull() ?: url

    /** 錯誤歸類成短代號，寫進診斷記錄。 */
    private fun kindOf(e: Throwable): String {
        val message = e.message.orEmpty()
        return when (e) {
            is ThrottledException -> if ("challenge" in message) "html" else message.substringAfter("HTTP ").take(3)
            is HttpStatusException -> "http${e.code}"
            is SocketTimeoutException -> if (message.contains("connect", ignoreCase = true)) "connect-timeout" else "read-timeout"
            is UnknownHostException -> "dns"
            is SSLException -> "tls"
            is SocketException -> "reset"
            else -> if (message.startsWith("segment ended early")) "short" else e.javaClass.simpleName
        }
    }

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

        /** 起跳併發：實測 CDN 對同一個 IP 約只放行 10 條同時連線，8 條不會被擋。 */
        const val START_PARALLEL = 8

        /**
         * 加倍的最短間隔：8 → 16 → 32 → 64 → 96 不到一秒到頂。真正的節奏由「這一批大多已在傳資料」決定，
         * 延遲高的網路會自動放慢，間隔短也不會衝過頭。
         */
        const val RAMP_MS = 150L
    }
}
