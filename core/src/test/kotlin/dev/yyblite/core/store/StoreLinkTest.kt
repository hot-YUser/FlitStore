package dev.yyblite.core.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoreLinkTest {

    @Test
    fun recognizedLinks() {
        val pkg = "com.tencent.tmgp.sgame"
        val links = listOf(
            // sj.qq.com「安裝」按鈕實際送出的格式（含編碼過的返回網址）
            "tmast://appdetails?pname=$pkg&oplist=1;2&channelid=000116083&via=ANDROID.WEB&back_jump_url=tmast%3A%2F%2Ffound&",
            "tmast://gamedetail?appid=12127266&pkgname=$pkg&oplist=1;2&back_jump_url=tmast%3A%2F%2Ffound&",
            // 網頁下載元件在瀏覽器裡組的格式：params 經 encodeURIComponent，內含 pkgName
            "tmast://appdemo?photonid=301539742450752&params=" +
                java.net.URLEncoder.encode("via:ANDROID.WEB,pkgName:$pkg,appName:王者荣耀,iconUrl://pp.myapp.com/x.png,autoOpen:true", "UTF-8") +
                "&via=ANDROID.WEB&recommendId=",
            "market://details?id=$pkg&referrer=utm_source%3Dweb",
            "https://sj.qq.com/appdetail/$pkg",
            "http://sj.qq.com/appdetail/$pkg?from=share#top",
            "https://a.app.qq.com/o/simple.jsp?pkgname=$pkg&fromcase=40003",
            "  HTTPS://SJ.QQ.COM/appdetail/$pkg  "
        )
        for (link in links) assertEquals(link, pkg, StoreLink.packageName(link))
    }

    /** 網頁「官方下载」在瀏覽器裡實際送出的連結只有內部編號，沒有套件名稱。 */
    @Test
    fun appIdOnlyLinks() {
        val found = "tmast://found?target_page_tmast=tmast%3A%2F%2Fappdemo%3Fphotonid%3D301728572630501%26scene%3D10864" +
            "%26back_jump_url%3Dtmast%3A%2F%2Ffound&target_param_channelid=1178424&target_param_appid=12127266&"
        assertNull(StoreLink.packageName(found))
        assertEquals("12127266", StoreLink.appId(found))
        assertEquals("12127266", StoreLink.appId("tmast://gamedetail?appid=12127266&oplist=1;2"))
        assertNull(StoreLink.appId("tmast://found"))
        assertNull(StoreLink.appId("tmast://webview?appid=12127266"))
        assertNull(StoreLink.appId("market://details?appid=12127266"))
        assertNull(StoreLink.appId("tmast://found?target_param_appid=abc"))
    }

    @Test
    fun ignoredLinks() {
        val links = listOf(
            "tmast://webview?url=https%3A%2F%2Fsj.qq.com&back_jump_url=tmast%3A%2F%2Ffound",
            "tmast://plugincommon?packagename=com.tencent.assistant.plugin.cloudgame",
            "market://search?q=wechat",
            "https://sj.qq.com/",
            "https://sj.qq.com/appdetail/",
            "https://example.com/appdetail/com.tencent.mm",
            "https://a.app.qq.com/o/simple.jsp?pkgname=not%20a%20package",
            "tmast://appdetails?pname=..",
            "com.tencent.mm",
            ""
        )
        for (link in links) assertNull(link, StoreLink.packageName(link))
    }
}
