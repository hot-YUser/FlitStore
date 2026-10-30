package dev.yyblite.core.apk

import dev.yyblite.core.net.Http
import dev.yyblite.core.net.HttpStatusException
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
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
    /** ZIP 結尾記錄 22 位元組，加上最長 65535 位元組的註解。 */
    private const val EOCD_MAX_BYTES = 65557L
    private val MANIFEST_NAME = "AndroidManifest.xml".toByteArray()

    fun versionCode(url: String): Long? {
        val total = contentLength(url) ?: return null
        val tailStart = total - minOf(total, EOCD_MAX_BYTES)
        val tail = range(url, tailStart, total - 1)

        val eocd = findEocd(tail) ?: return null
        val cdSize = leU32(tail, eocd + 12)
        val cdOffset = leU32(tail, eocd + 16)
        if (cdOffset <= 0 || cdSize <= 0) return null

        // 中央目錄可達數 MB，而且第一次檢查每個應用都要探測：讀到 Manifest 那一筆就斷線。
        val entry = if (cdOffset >= tailStart && cdOffset + cdSize <= total) {
            findManifestEntry(ByteArrayInputStream(tail, (cdOffset - tailStart).toInt(), cdSize.toInt()))
        } else {
            rangeStream(url, cdOffset, minOf(total, cdOffset + cdSize) - 1, ::findManifestEntry)
        }
        if (entry == null) return null
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

    internal data class Entry(val localOffset: Long, val compressedSize: Long, val method: Int)

    /** 逐筆讀中央目錄，找到就返回，後面的內容不必傳輸。 */
    internal fun findManifestEntry(raw: InputStream): Entry? {
        val input = DataInputStream(raw.buffered())
        val header = ByteArray(46)
        try {
            while (true) {
                input.readFully(header)
                if (header[0] != 'P'.code.toByte() || header[1] != 'K'.code.toByte() ||
                    header[2] != 1.toByte() || header[3] != 2.toByte()
                ) return null
                val name = ByteArray(leU16(header, 28)).also(input::readFully)
                if (name.contentEquals(MANIFEST_NAME)) {
                    return Entry(leU32(header, 42), leU32(header, 20), leU16(header, 10))
                }
                input.readFully(ByteArray(leU16(header, 30) + leU16(header, 32)))
            }
        } catch (e: EOFException) {
            return null
        }
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

    private fun range(url: String, start: Long, end: Long): ByteArray =
        rangeStream(url, start, end) { it.readBytes() }

    private fun <T> rangeStream(url: String, start: Long, end: Long, read: (InputStream) -> T): T {
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
            return read(conn.inputStream)
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
