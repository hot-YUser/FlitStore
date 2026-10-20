package dev.yyblite.download

import dev.yyblite.data.LookupResult
import dev.yyblite.data.StoreClient
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class SegmentDownloaderTest {

    @Test
    fun rangeAssembly() {
        val result = StoreClient.lookup("com.tencent.mm")
        if (result !is LookupResult.Found) fail("lookup 失敗：$result")
        val url = (result as LookupResult.Found).app.apkUrl
        val length = 4L * 1024 * 1024
        val temp = File.createTempFile("seg", ".bin")
        try {
            SegmentDownloader(32).downloadRange(url, 0, length - 1, temp)
            assertEquals(length, temp.length())
            assertEquals(sha256OfRange(url, 0, length - 1), sha256OfFile(temp))
        } finally {
            temp.delete()
        }
    }

    private fun sha256OfRange(url: String, start: Long, end: Long): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("Range", "bytes=$start-$end")
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
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
