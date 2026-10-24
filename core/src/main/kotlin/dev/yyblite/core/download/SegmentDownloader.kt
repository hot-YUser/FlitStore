package dev.yyblite.core.download

import dev.yyblite.core.net.Http
import dev.yyblite.core.net.HttpStatusException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * 以 HTTP Range 多段並行下載單一檔案。
 * 每個執行緒各自持有 RandomAccessFile，避免共享檔案指標競態。
 */
class SegmentDownloader(private val segments: Int = 0) {

    /** 0 = 依檔案大小自動決定：約每 2.4MB 一段，32–96 段（多輪實測 96 段最穩）。 */
    private fun autoSegments(total: Long): Int =
        (total / 2_400_000L).toInt().coerceIn(32, 96)

    fun totalSize(url: String, token: CancelToken = CancelToken()): Long {
        val conn = open(url, range = "bytes=0-0")
        token.track(conn)
        try {
            val code = conn.responseCode
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
        val length = endInclusive - start + 1
        val effective = if (segments > 0) segments else autoSegments(length)
        val count = effective.coerceAtLeast(1)
        val partLen = length / count
        if (partLen <= 0) throw IOException("range too small to split")

        val written = AtomicLong(0)
        val throttleHits = AtomicInteger(0)
        val failure = AtomicReference<Throwable?>()
        val lastEmit = AtomicLong(0)

        dest.parentFile?.mkdirs()

        val workers = ArrayList<Thread>(count)
        for (i in 0 until count) {
            val segStart = start + i * partLen
            val segEnd = if (i == count - 1) endInclusive else segStart + partLen - 1
            if (segStart > segEnd) continue
            val worker = thread(start = false, isDaemon = true, name = "seg-$i") {
                try {
                    fetchInto(url, segStart, segEnd, dest, written, lastEmit, length, onProgress, throttleHits, token)
                } catch (e: ThrottledException) {
                    throttleHits.incrementAndGet()
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                    if (t is DownloadCancelledException) token.cancel()
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
        val cause = failure.get()
        if (cause is RangeUnsupportedException) {
            dest.delete()
            throw cause
        }
        if (cause != null) {
            dest.delete()
            throw cause as? IOException ?: IOException(cause.message ?: "download failed", cause)
        }
        if (throttleHits.get() >= 3) {
            dest.delete()
            throw ThrottledException("CDN throttled (${throttleHits.get()} hits)")
        }
        if (written.get() != length) throw IOException("byte count mismatch ${written.get()}/$length")
        return dest
    }

    fun downloadFull(
        url: String,
        expectedMd5: String?,
        dest: File,
        token: CancelToken = CancelToken(),
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }
    ): File {
        var throttled: ThrottledException? = null
        for (candidate in Cdn.candidates(url)) {
            try {
                return downloadFrom(candidate, expectedMd5, dest, token, onProgress)
            } catch (e: ThrottledException) {
                throttled = e
            }
        }
        throw throttled ?: ThrottledException("store CDN is blocking requests")
    }

    private fun downloadFrom(
        url: String,
        expectedMd5: String?,
        dest: File,
        token: CancelToken,
        onProgress: (done: Long, total: Long) -> Unit
    ): File {
        dest.parentFile?.mkdirs()
        val total = try {
            probe(url, token)
        } catch (e: RangeUnsupportedException) {
            return sequential(url, expectedMd5, dest, token, onProgress)
        }

        val primary = if (segments > 0) segments else autoSegments(total)
        for (attempt in intArrayOf(primary, 8)) {
            try {
                dest.delete()
                RandomAccessFile(dest, "rw").use { it.setLength(total) }
                downloadRange(url, 0, total - 1, dest, attempt, token, onProgress)
                verifyMd5(dest, expectedMd5, token)
                return dest
            } catch (e: RangeUnsupportedException) {
                return sequential(url, expectedMd5, dest, token, onProgress)
            } catch (e: ThrottledException) {
                token.sleep(1000)
            } catch (e: IOException) {
                dest.delete()
                throw e
            }
        }
        return sequential(url, expectedMd5, dest, token, onProgress)
    }

    private fun probe(url: String, token: CancelToken): Long {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return totalSize(url, token)
            } catch (e: ThrottledException) {
                if (attempt >= 3) throw ThrottledException("store CDN is blocking requests")
                token.sleep(700L * attempt)
            }
        }
    }

    /** 多段被 CDN 拒絕時的最後手段：單連線循序下載（無 Range）。 */
    private fun sequential(
        url: String,
        expectedMd5: String?,
        dest: File,
        token: CancelToken,
        onProgress: (done: Long, total: Long) -> Unit
    ): File {
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

    private fun fetchInto(
        url: String,
        start: Long,
        end: Long,
        dest: File,
        written: AtomicLong,
        lastEmit: AtomicLong,
        total: Long,
        onProgress: (Long, Long) -> Unit,
        throttleHits: AtomicInteger,
        token: CancelToken
    ) {
        var attempt = 0
        while (true) {
            attempt++
            var conn: HttpURLConnection? = null
            var produced = 0L
            try {
                token.check()
                conn = open(url, range = "bytes=$start-$end")
                token.track(conn)
                val code = conn.responseCode
                if (isChallenge(conn, code)) {
                    throttleHits.incrementAndGet()
                    throw ThrottledException("CDN challenge page (HTTP $code)")
                }
                if (code == 403 || code == 429) {
                    throttleHits.incrementAndGet()
                    throw ThrottledException("HTTP $code")
                }
                if (code != 206) throw RangeUnsupportedException("CDN ignored Range (HTTP $code)")

                RandomAccessFile(dest, "rw").use { raf ->
                    raf.seek(start)
                    val buffer = ByteArray(256 * 1024)
                    conn.inputStream.use { input ->
                        var remaining = end - start + 1
                        while (remaining > 0) {
                            token.check()
                            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (read < 0) break
                            raf.write(buffer, 0, read)
                            remaining -= read
                            produced += read
                            val done = written.addAndGet(read.toLong())
                            val now = System.currentTimeMillis()
                            if (now - lastEmit.get() >= 250) {
                                lastEmit.set(now)
                                onProgress(done, total)
                            }
                        }
                        if (remaining != 0L) throw IOException("segment ended early, $remaining bytes missing")
                    }
                }
                return
            } catch (e: ThrottledException) {
                throw e
            } catch (e: RangeUnsupportedException) {
                throw e
            } catch (e: IOException) {
                written.addAndGet(-produced)
                produced = 0
                if (token.isCancelled) throw DownloadCancelledException()
                if (attempt >= 3) {
                    throw IOException("segment $start-$end failed after retries: ${e.message}", e)
                }
                token.sleep(500L * attempt)
            } finally {
                conn?.let {
                    token.untrack(it)
                    it.disconnect()
                }
            }
        }
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
}
