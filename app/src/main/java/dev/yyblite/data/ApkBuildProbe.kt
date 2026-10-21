package dev.yyblite.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 只抓 APK 的中央目錄與 AndroidManifest.xml，解析 binary XML 取得 versionCode。
 * 用於偵測「versionName 相同、建置號不同」的商店重建（例如 B 站 9160100 → 9160300），
 * 不必下載完整 APK。
 */
object ApkBuildProbe {
    private const val UA = "AndroidDownloadManager"
    private val ATTR_VERSION_CODE = 0x0101021b

    fun versionCode(url: String): Long? {
        return try {
            val total = contentLength(url) ?: return null
            val tailSize = minOf(total, 512L * 1024)
            val tailStart = total - tailSize
            val tail = range(url, tailStart, total - 1)

            val eocd = findEocd(tail) ?: return null
            val cdSize = leU32(tail, eocd + 12)
            val cdOffset = leU32(tail, eocd + 16)
            if (cdOffset <= 0 || cdSize <= 0) return null

            val cd = if (cdOffset >= tailStart && cdOffset + cdSize <= total) {
                tail.copyOfRange((cdOffset - tailStart).toInt(), (cdOffset - tailStart + cdSize).toInt())
            } else {
                range(url, cdOffset, minOf(total, cdOffset + cdSize) - 1)
            }
            val entry = findEntry(cd, "AndroidManifest.xml") ?: return null
            val header = range(url, entry.localOffset, entry.localOffset + 29)
            if (header.size < 30 || header[0] != 'P'.code.toByte()) return null
            val nameLen = leU16(header, 26)
            val extraLen = leU16(header, 28)
            val dataStart = entry.localOffset + 30 + nameLen + extraLen
            val dataEnd = dataStart + entry.compressedSize - 1
            if (dataEnd < dataStart) return null
            val raw = range(url, dataStart, dataEnd)
            val manifest = when (entry.method) {
                0 -> raw
                8 -> inflate(raw)
                else -> return null
            }
            parseVersionCode(manifest)
        } catch (e: Exception) {
            android.util.Log.w("YYBLite", "probe failed ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private data class Entry(val localOffset: Long, val compressedSize: Long, val method: Int)

    private fun findEntry(cd: ByteArray, name: String): Entry? {
        var pos = 0
        while (pos + 46 <= cd.size && cd[pos] == 'P'.code.toByte() && cd[pos + 1] == 'K'.code.toByte() &&
            cd[pos + 2] == 1.toByte() && cd[pos + 3] == 2.toByte()
        ) {
            val method = leU16(cd, pos + 10)
            val compressed = leU32(cd, pos + 20)
            val nameLen = leU16(cd, pos + 28)
            val extraLen = leU16(cd, pos + 30)
            val commentLen = leU16(cd, pos + 32)
            val localOffset = leU32(cd, pos + 42)
            val entryName = String(cd, pos + 46, nameLen, Charsets.UTF_8)
            if (entryName == name) return Entry(localOffset, compressed, method)
            pos += 46 + nameLen + extraLen + commentLen
        }
        return null
    }

    private fun findEocd(tail: ByteArray): Int? {
        for (i in tail.size - 22 downTo 0) {
            if (tail[i] == 'P'.code.toByte() && tail[i + 1] == 'K'.code.toByte() &&
                tail[i + 2] == 5.toByte() && tail[i + 3] == 6.toByte()
            ) {
                return i
            }
        }
        return null
    }

    private fun parseVersionCode(xml: ByteArray): Long? {
        var pos = 0
        var stringPool: ByteArray? = null
        var stringStart = 0
        var stringCount = 0
        var utf8 = false
        var resourceMap = IntArray(0)

        while (pos + 8 <= xml.size) {
            val type = leU16(xml, pos)
            val headerSize = leU16(xml, pos + 2)
            val size = leU32(xml, pos + 4).toInt()
            if (size <= 0 || pos + size > xml.size) return null

            when (type) {
                0x0001 -> { // string pool
                    stringCount = leU32(xml, pos + 8).toInt()
                    val flags = leU32(xml, pos + 16)
                    utf8 = (flags and 0x100L) != 0L
                    stringStart = pos + leU32(xml, pos + 20).toInt()
                    stringPool = xml
                }
                0x0180 -> { // resource map
                    val count = (size - headerSize) / 4
                    resourceMap = IntArray(count) { i -> leU32(xml, pos + headerSize + i * 4).toInt() }
                }
                0x0102 -> { // start element
                    val ns = leU32(xml, pos + 16).toInt()
                    val nameIdx = leU32(xml, pos + 20).toInt()
                    val attrStart = leU16(xml, pos + 24)
                    val attrSize = leU16(xml, pos + 26)
                    val attrCount = leU16(xml, pos + 28)
                    val attrsBase = pos + 16 + attrStart
                    for (i in 0 until attrCount) {
                        val a = attrsBase + i * attrSize
                        if (a + 20 > pos + size) break
                        val attrName = leU32(xml, a + 4).toInt()
                        val rawValue = leU32(xml, a + 8).toInt()
                        val dataType = xml[a + 15].toInt() and 0xff
                        val data = leU32(xml, a + 16)
                        val resId = if (attrName in resourceMap.indices) resourceMap[attrName] else 0
                        if (resId == ATTR_VERSION_CODE) {
                            return when (dataType) {
                                0x10, 0x11 -> data
                                0x03 -> rawValue.toLong() // string fallback
                                else -> data
                            }
                        }
                    }
                }
            }
            pos += if (type == 0x0003) maxOf(headerSize, 8) else size
        }
        return null
    }

    // ---- HTTP range ------------------------------------------------------

    private fun contentLength(url: String): Long? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Range", "bytes=0-0")
            val cr = conn.getHeaderField("Content-Range") ?: return null
            return cr.substringAfterLast('/').toLongOrNull()
        } finally {
            conn.disconnect()
        }
    }

    private fun range(url: String, start: Long, end: Long): ByteArray {
        if (end < start) throw IOException("invalid range")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 25_000
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Range", "bytes=$start-$end")
            conn.setRequestProperty("Accept-Encoding", "identity")
            val code = conn.responseCode
            if (code != 206 && code != 200) throw IOException("HTTP $code")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun inflate(data: ByteArray): ByteArray =
        java.util.zip.InflaterInputStream(
            java.io.ByteArrayInputStream(data),
            java.util.zip.Inflater(true)
        ).use { it.readBytes() }

    private fun leU16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)

    private fun leU32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xff) or
            ((b[off + 1].toLong() and 0xff) shl 8) or
            ((b[off + 2].toLong() and 0xff) shl 16) or
            ((b[off + 3].toLong() and 0xff) shl 24)
}
