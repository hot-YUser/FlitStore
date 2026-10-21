package dev.yyblite.download

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import dev.yyblite.data.Cdn

/** CDN 要求先通過 EdgeOne JS 驗證或直接限流。 */
class ThrottledException(message: String) : IOException(message)

/** CDN 不理會 Range，只能單連線下載。 */
class RangeUnsupportedException(message: String) : IOException(message)

/**
 * 以 HTTP Range 多段並行下載單一檔案。
 * 每個執行緒各自持有 RandomAccessFile，避免共享檔案指標競態。
 */
class SegmentDownloader(private val segments: Int = 0) {

    /** 0 = 依檔案大小自動決定：約每 2.4MB 一段，32–96 段（多輪實測 96 段最穩）。 */
    private fun autoSegments(total: Long): Int =
        (total / 2_400_000L).toInt().coerceIn(32, 96)

    fun totalSize(url: String): Long {
        val conn = open(url, range = "bytes=0-0")
        try {
            val code = conn.responseCode
            if (code == 206) {
                val contentRange = conn.getHeaderField("Content-Range")
                    ?: throw IOException("缺少 Content-Range")
                val total = contentRange.substringAfterLast('/').trim().toLongOrNull() ?: -1
                if (total <= 0) throw IOException("無法取得檔案大小")
                return total
            }
            if (isChallenge(conn, code)) throw ThrottledException("CDN 驗證頁（HTTP $code）")
            if (code in 200..299) throw RangeUnsupportedException("CDN 忽略 Range（HTTP $code）")
            if (code == 403 || code == 429) throw ThrottledException("HTTP $code")
            throw IOException("HTTP $code")
        } finally {
            conn.disconnect()
        }
    }

    fun downloadRange(
        url: String,
        start: Long,
        endInclusive: Long,
        dest: File,
        segments: Int = this.segments,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }
    ): File {
        if (endInclusive < start) throw IOException("區間無效")
        val length = endInclusive - start + 1
        val effective = if (segments > 0) segments else autoSegments(length)
        val count = effective.coerceAtLeast(1)
        val partLen = length / count
        if (partLen <= 0) throw IOException("區間太小，無法分段")

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
                    fetchInto(url, segStart, segEnd, dest, written, lastEmit, length, onProgress, throttleHits)
                } catch (e: ThrottledException) {
                    throttleHits.incrementAndGet()
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }
            workers.add(worker)
            worker.start()
        }
        workers.forEach { it.join() }

        val cause = failure.get()
        if (cause is RangeUnsupportedException) {
            dest.delete()
            throw cause
        }
        if (cause != null) {
            dest.delete()
            throw IOException(cause.message ?: "下載失敗", cause)
        }
        if (throttleHits.get() >= 3) {
            dest.delete()
            throw ThrottledException("CDN 限流（${throttleHits.get()} 次）")
        }
        if (written.get() != length) throw IOException("位元組數不符 ${written.get()}/$length")
        return dest
    }

    fun downloadFull(
        url: String,
        expectedMd5: String?,
        dest: File,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }
    ): File {
        var throttled: ThrottledException? = null
        for (candidate in Cdn.candidates(url)) {
            try {
                return downloadFrom(candidate, expectedMd5, dest, onProgress)
            } catch (e: ThrottledException) {
                throttled = e
            }
        }
        throw throttled ?: ThrottledException("商店 CDN 暫時阻擋，請稍後重試")
    }

    private fun downloadFrom(
        url: String,
        expectedMd5: String?,
        dest: File,
        onProgress: (done: Long, total: Long) -> Unit
    ): File {
        dest.parentFile?.mkdirs()
        val total = try {
            probe(url)
        } catch (e: RangeUnsupportedException) {
            return sequential(url, expectedMd5, dest, onProgress)
        }

        val primary = if (segments > 0) segments else autoSegments(total)
        for (attempt in intArrayOf(primary, 8)) {
            try {
                dest.delete()
                RandomAccessFile(dest, "rw").use { it.setLength(total) }
                downloadRange(url, 0, total - 1, dest, attempt, onProgress)
                verifyMd5(dest, expectedMd5)
                return dest
            } catch (e: RangeUnsupportedException) {
                return sequential(url, expectedMd5, dest, onProgress)
            } catch (e: ThrottledException) {
                Thread.sleep(1000)
            } catch (e: IOException) {
                dest.delete()
                throw e
            }
        }
        return sequential(url, expectedMd5, dest, onProgress)
    }

    private fun probe(url: String): Long {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return totalSize(url)
            } catch (e: RangeUnsupportedException) {
                throw e
            } catch (e: ThrottledException) {
                if (attempt >= 3) throw ThrottledException("商店 CDN 暫時阻擋，請稍後重試")
                Thread.sleep(700L * attempt)
            }
        }
    }

    /** 多段被 CDN 拒絕時的最後手段：單連線循序下載（無 Range）。 */
    private fun sequential(
        url: String,
        expectedMd5: String?,
        dest: File,
        onProgress: (done: Long, total: Long) -> Unit
    ): File {
        var attempt = 0
        while (true) {
            attempt++
            dest.delete()
            dest.parentFile?.mkdirs()
            try {
                return sequentialOnce(url, expectedMd5, dest, onProgress)
            } catch (e: IOException) {
                dest.delete()
                if (attempt >= 3) throw e
                Thread.sleep(1000L * attempt)
            }
        }
    }

    private fun sequentialOnce(
        url: String,
        expectedMd5: String?,
        dest: File,
        onProgress: (done: Long, total: Long) -> Unit
    ): File {
        val conn = open(url)
        try {
            val code = conn.responseCode
            if (isChallenge(conn, code)) throw ThrottledException("CDN 驗證頁")
            if (code !in 200..299) throw IOException("HTTP $code")
            val total = conn.contentLengthLong
            var done = 0L
            var lastEmit = 0L
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
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
            if (done == 0L) throw IOException("空回應（HTTP $code）")
            if (total > 0 && done != total) throw IOException("位元組數不符 $done/$total")
            onProgress(done, total)
            verifyMd5(dest, expectedMd5)
            return dest
        } finally {
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
        throttleHits: AtomicInteger
    ) {
        var attempt = 0
        while (true) {
            attempt++
            var conn: HttpURLConnection? = null
            var produced = 0L
            try {
                conn = open(url, range = "bytes=$start-$end")
                val code = conn.responseCode
                if (isChallenge(conn, code)) {
                    throttleHits.incrementAndGet()
                    throw ThrottledException("CDN 驗證頁（HTTP $code）")
                }
                if (code == 403 || code == 429) {
                    throttleHits.incrementAndGet()
                    throw ThrottledException("HTTP $code")
                }
                if (code != 206) throw RangeUnsupportedException("CDN 忽略 Range（HTTP $code）")

                RandomAccessFile(dest, "rw").use { raf ->
                    raf.seek(start)
                    val buffer = ByteArray(256 * 1024)
                    conn.inputStream.use { input ->
                        var remaining = end - start + 1
                        while (remaining > 0) {
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
                        if (remaining != 0L) throw IOException("區間提前結束，缺 $remaining 位元組")
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
                if (attempt >= 3) {
                    throw IOException("區間 $start-$end 重試失敗：${e.message}", e)
                }
                try {
                    Thread.sleep(500L * attempt)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun open(url: String, range: String? = null): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept-Encoding", "identity")
        if (range != null) conn.setRequestProperty("Range", range)
        return conn
    }

    private fun isChallenge(conn: HttpURLConnection, code: Int): Boolean =
        code in 200..299 && conn.contentType?.contains("text/html", ignoreCase = true) == true

    private fun verifyMd5(file: File, expected: String?) {
        if (expected.isNullOrBlank()) return
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expected, ignoreCase = true)) {
            file.delete()
            throw IOException("MD5 校驗失敗（${file.length()} bytes）：$actual != $expected")
        }
    }

    private companion object {
        // 不可使用瀏覽器 UA：Tencent EdgeOne 會對類瀏覽器流量下發 JS 驗證頁。
        const val UA = "AndroidDownloadManager"
    }
}
