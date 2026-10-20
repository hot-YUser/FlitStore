package dev.yyblite.data

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
            rating = null,
            screenshots = emptyList(),
            editorIntro = null
        )
        val decoded = StoreAppJson.decode(StoreAppJson.encode(app))!!
        assertEquals(app.pkg, decoded.pkg)
        assertEquals(app.versionName, decoded.versionName)
        assertEquals(app.apkUrl, decoded.apkUrl)
        assertEquals(app.signatureMd5, decoded.signatureMd5)
        assertEquals(app.sizeBytes, decoded.sizeBytes)
        assertNull(decoded.versionCode)
        assertNull(decoded.iconUrl)
    }

    @Test
    fun rejectIncomplete() {
        val json = StoreAppJson.encode(
            StoreApp("p", "n", "", null, "", null, "", null, -1, null, null, null, null, null, emptyList(), null)
        )
        assertNull(StoreAppJson.decode(json))
    }
}
