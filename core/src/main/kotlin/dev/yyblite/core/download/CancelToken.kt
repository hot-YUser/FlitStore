package dev.yyblite.core.download

import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap

/** CDN 要求先通過 EdgeOne JS 驗證，或直接限流。 */
class ThrottledException(message: String) : IOException(message)

/** CDN 不理會 Range，只能單連線下載。 */
class RangeUnsupportedException(message: String) : IOException(message)

class ChecksumException(val actual: String, val expected: String, val bytes: Long) :
    IOException("MD5 mismatch ($bytes bytes): $actual != $expected")

class DownloadCancelledException : IOException("cancelled")

/**
 * 取消下載：設旗標、立刻斷開所有登記中的連線（卡在 read 的執行緒會馬上拋例外），
 * 並喚醒正在退避等待的執行緒。
 */
class CancelToken {
    @Volatile
    var isCancelled = false
        private set

    private val lock = Object()
    private val open = ConcurrentHashMap.newKeySet<HttpURLConnection>()

    fun cancel() {
        isCancelled = true
        open.forEach { runCatching { it.disconnect() } }
        synchronized(lock) { lock.notifyAll() }
    }

    fun check() {
        if (isCancelled) throw DownloadCancelledException()
    }

    fun track(conn: HttpURLConnection) {
        open.add(conn)
        if (isCancelled) {
            open.remove(conn)
            conn.disconnect()
            throw DownloadCancelledException()
        }
    }

    fun untrack(conn: HttpURLConnection) {
        open.remove(conn)
    }

    /** 可被取消打斷的退避等待。 */
    fun sleep(ms: Long) {
        synchronized(lock) {
            if (!isCancelled) lock.wait(ms)
        }
        check()
    }
}
