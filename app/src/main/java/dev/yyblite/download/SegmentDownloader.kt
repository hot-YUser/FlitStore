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

class ThrottledException(message: String) : IOException(message)

/**
 * 以 HTTP Range 多段並行下載單一檔案。
 * 每個執行緒各自持有 RandomAccessFile，避免共享檔案指標競態。
 */
class SegmentDownloader(private val segments: Int = 32) {

    fun totalSize(url: String): Long {
        val conn = open(url, range = "bytes=0-0")
        try {
            val code = conn.responseCode
            if (code != 206) throw IOException("不支援分段下載（HTTP $code）")
            val contentRange = conn.getHeaderField("Content-Range")
                ?: throw IOException("缺少 Content-Range")
            val total = contentRange.substringAfterLast('/').trim().toLongOrNull() ?: -1
            if (total <= 0) throw IOException("無法取得檔案大小")
            return total
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
        val count = segments.coerceAtLeast(1)
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

        if (failure.get() != null) {
            dest.delete()
            throw IOException(failure.get()!!.message ?: "下載失敗", failure.get())
        }
        if (throttleHits.get() >= 3) {
            dest.delete()
            throw ThrottledException("CDN 限流（$throttleHits 次）")
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
        val total = try {
            totalSize(url)
        } catch (e: IOException) {
            // Range 探測失敗（CDN 回 200 或限流）→ 直接走單連線。
            dest.parentFile?.mkdirs()
            try {
                return downloadSequential(url, expectedMd5, dest, onProgress)
            } catch (fallback: IOException) {
                dest.delete()
                throw fallback
            }
        }
        dest.parentFile?.mkdirs()
        var lastError: IOException? = null
        for (attempt in intArrayOf(this.segments, 8)) {
            try {
                dest.delete()
                RandomAccessFile(dest, "rw").use { it.setLength(total) }
                downloadRange(url, 0, total - 1, dest, attempt, onProgress)
                verifyMd5(dest, expectedMd5)
                return dest
            } catch (e: ThrottledException) {
                lastError = e
            } catch (e: IOException) {
                dest.delete()
                throw e
            }
        }
        try {
            return downloadSequential(url, expectedMd5, dest, onProgress)
        } catch (e: IOException) {
            dest.delete()
            throw e
        }
    }

    /** 多段被 CDN 拒絕時的最後手段：單連線循序下載（無 Range）。 */
    private fun downloadSequential(
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
                return downloadSequentialOnce(url, expectedMd5, dest, onProgress)
            } catch (e: IOException) {
                dest.delete()
                if (attempt >= 3) throw e
                try {
                    Thread.sleep(1000L * attempt)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
        }
    }

    private fun downloadSequentialOnce(
        url: String,
        expectedMd5: String?,
        dest: File,
        onProgress: (done: Long, total: Long) -> Unit
    ): File {
        val conn = open(url)
        try {
            val code = conn.responseCode
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
                if (code == 403 || code == 429 || code == 200) {
                    throttleHits.incrementAndGet()
                    throw ThrottledException("HTTP $code（Range 未生效或被限流）")
                }
                if (code != 206) throw IOException("HTTP $code")

                RandomAccessFile(dest, "rw").use { raf ->
                    raf.seek(start)
                    val buffer = ByteArray(64 * 1024)
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
        const val UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    }
}
