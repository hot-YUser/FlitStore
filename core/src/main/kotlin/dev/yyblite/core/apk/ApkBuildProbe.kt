package dev.yyblite.core.apk

import dev.yyblite.core.net.Http
import dev.yyblite.core.net.HttpStatusException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * 只抓 APK 的中央目錄與 AndroidManifest.xml，解析 binary XML 取得 versionCode，
 * 不必下載完整 APK。商店頁面的 versionCode 常是 0，更新判定靠這裡拿到真實的建置號。
 * 網路錯誤丟例外，格式看不懂回 null。
 */
object ApkBuildProbe {
    private const val ATTR_VERSION_CODE = 0x0101021b
    private const val ATTR_VERSION_CODE_MAJOR = 0x01010576
    private const val TYPE_INT_FIRST = 0x10
    private const val TYPE_INT_LAST = 0x1f

    fun versionCode(url: String): Long? {
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
            8 -> InflaterInputStream(ByteArrayInputStream(raw), Inflater(true)).use { it.readBytes() }
            else -> return null
        }
        return parseVersionCode(manifest)
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

    /** 和 PackageInfo.getLongVersionCode() 同一種算法：versionCodeMajor 在高 32 位。 */
    internal fun parseVersionCode(xml: ByteArray): Long? {
        var pos = 0
        var resourceMap = IntArray(0)

        while (pos + 8 <= xml.size) {
            val type = leU16(xml, pos)
            val headerSize = leU16(xml, pos + 2)
            val size = leU32(xml, pos + 4).toInt()
            if (size <= 0 || pos + size > xml.size) return null

            when (type) {
                0x0180 -> { // resource map
                    val count = (size - headerSize) / 4
                    resourceMap = IntArray(count) { i -> leU32(xml, pos + headerSize + i * 4).toInt() }
                }
                0x0102 -> { // start element
                    val attrStart = leU16(xml, pos + 24)
                    val attrSize = leU16(xml, pos + 26)
                    val attrCount = leU16(xml, pos + 28)
                    val attrsBase = pos + 16 + attrStart
                    var code: Long? = null
                    var major = 0L
                    for (i in 0 until attrCount) {
                        val a = attrsBase + i * attrSize
                        if (a + 20 > pos + size) break
                        val attrName = leU32(xml, a + 4).toInt()
                        val dataType = xml[a + 15].toInt() and 0xff
                        if (dataType !in TYPE_INT_FIRST..TYPE_INT_LAST) continue
                        val data = leU32(xml, a + 16)
                        when (if (attrName in resourceMap.indices) resourceMap[attrName] else 0) {
                            ATTR_VERSION_CODE -> code = data
                            ATTR_VERSION_CODE_MAJOR -> major = data
                        }
                    }
                    if (code != null) return (major shl 32) or code
                }
            }
            pos += if (type == 0x0003) maxOf(headerSize, 8) else size
        }
        return null
    }

    private fun contentLength(url: String): Long? {
        val conn = Http.open(url, userAgent = Http.DOWNLOAD_UA, range = "bytes=0-0", gzip = false)
        try {
            val cr = conn.getHeaderField("Content-Range") ?: return null
            return cr.substringAfterLast('/').toLongOrNull()
        } finally {
            conn.disconnect()
        }
    }

    private fun range(url: String, start: Long, end: Long): ByteArray {
        if (end < start) throw IOException("invalid range")
        val conn = Http.open(
            url,
            userAgent = Http.DOWNLOAD_UA,
            readTimeoutMs = 25_000,
            range = "bytes=$start-$end",
            gzip = false
        )
        try {
            val code = conn.responseCode
            if (code != 206 && code != 200) throw HttpStatusException(code)
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun leU16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)

    private fun leU32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xff) or
            ((b[off + 1].toLong() and 0xff) shl 8) or
            ((b[off + 2].toLong() and 0xff) shl 16) or
            ((b[off + 3].toLong() and 0xff) shl 24)
}
