package dev.yyblite.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StoreClientTest {

    @Test
    fun lookupWechat() {
        val result = StoreClient.lookup("com.tencent.mm")
        if (result !is LookupResult.Found) fail("lookup 失敗：$result")
        val app = (result as LookupResult.Found).app
        assertEquals("微信", app.name)
        assertEquals("com.tencent.mm", app.pkg)
        assertTrue(app.apkUrl.startsWith("https://"))
        assertTrue(app.apkUrl.contains(app.apkMd5))
        assertTrue("應提供 64 位連結", app.apkUrl64?.startsWith("https://") == true)
        assertTrue("應帶檔案大小", app.sizeBytes > 0)
        assertTrue(Regex("[0-9A-Fa-f]{32}").matches(app.apkMd5))
        assertFalse(app.versionName.isBlank())
    }

    @Test
    fun lookupMissing() {
        assertEquals(LookupResult.NotInStore, StoreClient.lookup("com.nonexist.fakepkg12345"))
    }

    @Test
    fun searchWechat() {
        val results = StoreClient.search("微信")
        assertTrue("搜尋應有結果", results.isNotEmpty())
        val wechat = results.firstOrNull { it.pkg == "com.tencent.mm" }
            ?: error("搜尋結果缺少微信：${results.map { it.pkg }}")
        assertTrue(wechat.versionName.isNotBlank())
        assertTrue(wechat.apkUrl.startsWith("https://"))
    }
}
