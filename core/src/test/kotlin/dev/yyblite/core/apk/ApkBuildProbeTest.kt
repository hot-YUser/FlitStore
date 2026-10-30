package dev.yyblite.core.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ApkBuildProbeTest {

    private class Attr(val resId: Int, val type: Int, val raw: Int, val data: Int)

    /** 組出只含 XML 標頭、資源對應表和 <manifest> 起始標籤的 binary XML。 */
    private fun manifest(vararg attrs: Attr): ByteArray {
        val ids = ByteBuffer.allocate(8 + attrs.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x0180).putShort(8).putInt(capacity())
            attrs.forEach { putInt(it.resId) }
        }
        val element = ByteBuffer.allocate(36 + attrs.size * 20).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x0102).putShort(16).putInt(capacity())
            putInt(1).putInt(-1) // 行號、註解
            putInt(-1).putInt(0) // 命名空間、標籤名
            putShort(20).putShort(20).putShort(attrs.size.toShort())
            putShort(0).putShort(0).putShort(0)
            attrs.forEachIndexed { index, attr ->
                putInt(-1).putInt(index).putInt(attr.raw)
                putShort(8).put(0).put(attr.type.toByte()).putInt(attr.data)
            }
        }
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x0003).putShort(8).putInt(8 + ids.capacity() + element.capacity())
        }
        return ByteArrayOutputStream().apply {
            write(header.array())
            write(ids.array())
            write(element.array())
        }.toByteArray()
    }

    @Test
    fun readsVersionCode() {
        val xml = manifest(Attr(CODE, TYPE_DEC, -1, 528))
        assertEquals(528L, ApkBuildProbe.parseVersionCode(xml))
    }

    @Test
    fun combinesMajorLikeGetLongVersionCode() {
        val xml = manifest(Attr(MAJOR, TYPE_DEC, -1, 2), Attr(CODE, TYPE_DEC, -1, 7))
        assertEquals((2L shl 32) or 7L, ApkBuildProbe.parseVersionCode(xml))
    }

    @Test
    fun keepsHighBitUnsigned() {
        val xml = manifest(Attr(CODE, TYPE_HEX, -1, 0x80000000.toInt()))
        assertEquals(0x80000000L, ApkBuildProbe.parseVersionCode(xml))
    }

    /** 字串型別的值是字串表索引，不是版本號：當作看不懂，交給 versionName 判定。 */
    @Test
    fun rejectsStringTypedValue() {
        val xml = manifest(Attr(CODE, TYPE_STRING, 5, 5))
        assertNull(ApkBuildProbe.parseVersionCode(xml))
    }

    @Test
    fun missingAttributeIsNull() {
        assertNull(ApkBuildProbe.parseVersionCode(manifest(Attr(0x01010001, TYPE_DEC, -1, 9))))
    }

    private companion object {
        const val CODE = 0x0101021b
        const val MAJOR = 0x01010576
        const val TYPE_STRING = 0x03
        const val TYPE_DEC = 0x10
        const val TYPE_HEX = 0x11
    }
}
