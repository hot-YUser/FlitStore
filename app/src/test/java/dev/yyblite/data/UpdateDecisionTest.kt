package dev.yyblite.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDecisionTest {

    private fun store(versionName: String, versionCode: String?) = StoreApp(
        pkg = "com.example.app",
        name = "Example",
        versionName = versionName,
        versionCode = versionCode,
        apkUrl = "https://cdn.example/app.apk",
        apkUrl64 = null,
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
    fun versionCodeWinsWhenAvailable() {
        assertTrue(UpdateDecision.needsUpdate(store("1.0", "120"), "9.9", 100))
        assertFalse(UpdateDecision.needsUpdate(store("9.9", "100"), "1.0", 100))
    }

    @Test
    fun fallsBackToVersionNameWhenStoreCodeMissing() {
        assertTrue(UpdateDecision.needsUpdate(store("8.0.80", "0"), "8.0.79", 0))
        assertFalse(UpdateDecision.needsUpdate(store("8.0.79", null), "8.0.79", 0))
        assertFalse(UpdateDecision.needsUpdate(store("8.0.70", null), "8.0.79", 0))
    }
}
