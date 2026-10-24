package dev.yyblite.core.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoreAppJsonTest {

    @Test
    fun roundTripKeepsFields() {
        val app = StoreApp(
            pkg = "com.example.app",
            name = "Example",
            versionName = "1.2.3",
            versionCode = null,
            apkUrl = "https://cdn.example/apk/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA.apk?fsname=a.apk",
            apkUrl64 = null,
            apkMd5 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            signatureMd5 = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
            sizeBytes = 123,
            sizeDesc = "123 B",
            iconUrl = null,
            developer = "Dev",
            downloadDesc = null,
            rating = 4.5,
            screenshots = listOf("https://a/1.png", "https://a/2.png"),
            editorIntro = null,
            category = "工具",
            detailed = true
        )
        assertEquals(app, StoreAppJson.decode(StoreAppJson.encode(app)))
    }

    @Test
    fun rejectIncomplete() {
        val json = StoreAppJson.encode(
            StoreApp("p", "n", "", null, "", null, "", null, -1, null, null, null, null, null, emptyList(), null)
        )
        assertNull(StoreAppJson.decode(json))
    }
}
