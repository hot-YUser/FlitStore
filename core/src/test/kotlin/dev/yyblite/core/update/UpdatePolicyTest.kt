package dev.yyblite.core.update

import dev.yyblite.core.store.StoreApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {

    private fun store(versionName: String, versionCode: String? = null) = StoreApp(
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

    private fun installed(versionName: String, versionCode: Long) =
        InstalledApp("com.example.app", "Example", versionName, versionCode)

    @Test
    fun versionCodeWinsWhenAvailable() {
        assertTrue(UpdatePolicy.isNewer(store("1.0", "120"), "9.9", 100))
        assertFalse(UpdatePolicy.isNewer(store("9.9", "100"), "1.0", 100))
    }

    @Test
    fun fallsBackToVersionNameWhenStoreCodeMissing() {
        assertTrue(UpdatePolicy.isNewer(store("8.0.80", "0"), "8.0.79", 0))
        assertFalse(UpdatePolicy.isNewer(store("8.0.79", null), "8.0.79", 0))
        assertFalse(UpdatePolicy.isNewer(store("8.0.70", null), "8.0.79", 0))
    }

    /** 走遍旗標組合：版本回退、忽略、簽章不符的優先序，以及「只有可更新才算提供」。 */
    @Test
    fun flagMatrix() {
        val cases = listOf(
            Triple(store("2.0"), installed("1.0", 10), null),          // 版本號較新
            Triple(store("1.0", "20"), installed("1.0", 10), null),    // versionCode 較新
            Triple(store("1.0"), installed("1.0", 10), 11L),           // 同版重建
            Triple(store("1.0"), installed("1.0", 10), null),          // 相同
            Triple(store("0.9"), installed("1.0", 10), 99L),           // 商店較舊，探測值不該生效
            Triple(store("1.0"), installed("1.0", 10), 9L)             // 探測到較舊的建置
        )
        val bools = listOf(true, false)
        for ((s, i, probe) in cases) for (certOk in bools) for (regression in bools) for (ignored in bools) {
            val verdict = UpdatePolicy.evaluate(s, i, certOk, regression, ignored, probe)
            val hasUpdate = UpdatePolicy.isNewer(s, i.versionName, i.versionCode) ||
                (probe != null && probe > i.versionCode && VersionOrder.compare(s.versionName, i.versionName) == 0)
            val label = "store=${s.versionName}/${s.versionCode} probe=$probe cert=$certOk reg=$regression ign=$ignored"
            when {
                !hasUpdate -> assertEquals(label, Verdict.UpToDate, verdict)
                regression -> assertEquals(label, Verdict.Regression, verdict)
                ignored -> assertEquals(label, Verdict.Ignored, verdict)
                !certOk -> assertEquals(label, Verdict.CertMismatch, verdict)
                else -> assertTrue(label, verdict.offered)
            }
            if (regression || ignored || !certOk) assertFalse(label, verdict.offered)
        }
    }

    @Test
    fun newBuildCarriesProbedCode() {
        assertEquals(
            Verdict.NewBuild(9160300),
            UpdatePolicy.evaluate(store("9.16.0"), installed("9.16.0", 9160100), true, false, false, 9160300)
        )
        assertEquals(
            Verdict.Available,
            UpdatePolicy.evaluate(store("9.17.0"), installed("9.16.0", 9160100), true, false, false, 9160300)
        )
    }
}
