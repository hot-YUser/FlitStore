package dev.yyblite.core.store

import dev.yyblite.core.live
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

class StoreClientTest {

    @Test
    fun lookupWechat() {
        val result = StoreClient.lookup("com.tencent.mm")
        if (result is LookupResult.Failed) Assume.assumeNoException(result.error)
        val app = (result as LookupResult.Found).app
        assertEquals("微信", app.name)
        assertEquals("com.tencent.mm", app.pkg)
        assertTrue(app.detailed)
        assertTrue(app.apkUrl.startsWith("https://"))
        assertTrue(app.apkUrl.contains(app.apkMd5))
        assertTrue("應提供 64 位連結", app.apkUrl64?.startsWith("https://") == true)
        assertTrue("應帶檔案大小", app.sizeBytes > 0)
        assertTrue(Regex("[0-9A-Fa-f]{32}").matches(app.apkMd5))
        assertFalse(app.versionName.isBlank())
    }

    /** 網頁「官方下载」只帶內部編號；王者榮耀是 12127266。 */
    @Test
    fun appIdToPackage() {
        assertEquals("com.tencent.tmgp.sgame", live { StoreClient.packageForAppId("12127266") })
        assertEquals(null, live { StoreClient.packageForAppId("99999999999") })
    }

    @Test
    fun lookupMissing() {
        val result = StoreClient.lookup("com.nonexist.fakepkg12345")
        if (result is LookupResult.Failed) Assume.assumeNoException(result.error)
        assertEquals(LookupResult.NotInStore, result)
    }

    @Test
    fun searchWechat() {
        val results = live { StoreClient.search("微信") }
        assertTrue("搜尋應有結果", results.isNotEmpty())
        val wechat = results.firstOrNull { it.pkg == "com.tencent.mm" }
            ?: error("搜尋結果缺少微信：${results.map { it.pkg }}")
        assertTrue(wechat.versionName.isNotBlank())
        assertTrue(wechat.apkUrl.startsWith("https://"))
        assertFalse(wechat.detailed)
    }
}
