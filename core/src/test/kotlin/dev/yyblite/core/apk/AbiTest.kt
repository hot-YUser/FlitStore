package dev.yyblite.core.apk

import dev.yyblite.core.download.Cdn
import dev.yyblite.core.store.StoreApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AbiTest {

    @Test
    fun cdnMirrorRotation() {
        val url = "https://imtt2.dd.qq.com/a/b.apk?fsname=x.apk"
        assertEquals(
            listOf(
                "https://imtt2.dd.qq.com/a/b.apk?fsname=x.apk",
                "https://imtt.dd.qq.com/a/b.apk?fsname=x.apk"
            ),
            Cdn.candidates(url)
        )
        val plain = "https://example.com/a.apk"
        assertEquals(listOf(plain), Cdn.candidates(plain))
        assertEquals("https://imtt9.dd.qq.com/a.apk", Cdn.forceHost("https://imtt.dd.qq.com/a.apk", "imtt9.dd.qq.com"))
    }

    private fun app(url64: String?): StoreApp = StoreApp(
        pkg = "com.example.app",
        name = "Example",
        versionName = "1.0",
        versionCode = "1",
        apkUrl = "https://cdn.example/universal.apk",
        apkUrl64 = url64,
        apkMd5 = "00000000000000000000000000000000",
        signatureMd5 = null,
        sizeBytes = 1,
        sizeDesc = null,
        iconUrl = null,
        developer = null,
        downloadDesc = null,
        rating = null,
        screenshots = emptyList(),
        editorIntro = null
    )

    @Test
    fun arm64DevicePicks64BitUrl() {
        val store = app("https://cdn.example/arm64.apk")
        assertEquals(
            "https://cdn.example/arm64.apk",
            Abi.chooseUrl(store, listOf("arm64-v8a", "armeabi-v7a", "armeabi"))
        )
        assertTrue(Abi.isArm64(store, listOf("arm64-v8a")))
    }

    @Test
    fun thirtyTwoBitDeviceFallsBack() {
        val store = app("https://cdn.example/arm64.apk")
        assertEquals(
            "https://cdn.example/universal.apk",
            Abi.chooseUrl(store, listOf("armeabi-v7a", "armeabi"))
        )
        assertFalse(Abi.isArm64(store, listOf("armeabi-v7a")))
    }

    @Test
    fun md5ComesFromChosenUrl() {
        val store = app("https://cdn.example/apk/40440A50D9B75EEE5AB24491A5E3A18E.apk?fsname=x.apk")
        assertEquals(
            "40440A50D9B75EEE5AB24491A5E3A18E",
            Abi.md5For(store, store.apkUrl64!!)
        )
        assertEquals(store.apkMd5, Abi.md5For(store, store.apkUrl))
    }

    @Test
    fun identical64UrlMeansNoDistinctBuild() {
        val store = app("https://cdn.example/universal.apk")
        assertEquals("https://cdn.example/universal.apk", Abi.chooseUrl(store, listOf("arm64-v8a")))
        assertFalse(Abi.isArm64(store, listOf("arm64-v8a")))
    }

    @Test
    fun missing64UrlFallsBack() {
        val store = app(null)
        assertEquals(
            "https://cdn.example/universal.apk",
            Abi.chooseUrl(store, listOf("arm64-v8a"))
        )
        assertFalse(Abi.isArm64(store, listOf("arm64-v8a")))
    }
}
