package dev.yyblite.core.update

import dev.yyblite.core.live
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseTest {

    private fun release(tag: String, vararg assets: Pair<String, Long>): String = JSONObject()
        .put("tag_name", tag)
        .put("body", "notes")
        .put("html_url", "https://github.com/o/r/releases/tag/$tag")
        .put("assets", JSONArray().apply {
            for ((name, size) in assets) {
                put(JSONObject().put("name", name).put("size", size).put("browser_download_url", "https://github.com/o/r/releases/download/$tag/$name"))
            }
        })
        .toString()

    @Test
    fun picksApkAsset() {
        val parsed = GitHubReleases.parse(release("v1.2.3", "checksums.txt" to 10L, "FlitStore-v1.2.3.apk" to 2_072_888L))
        assertEquals("1.2.3", parsed?.versionName)
        assertEquals("v1.2.3", parsed?.tag)
        assertEquals(2_072_888L, parsed?.apkSize)
        assertTrue(parsed!!.apkUrl.endsWith("/FlitStore-v1.2.3.apk"))
    }

    @Test
    fun releaseWithoutApkIsIgnored() {
        assertNull(GitHubReleases.parse(release("v2.0.0", "source.zip" to 1L)))
        assertNull(GitHubReleases.parse(release("", "FlitStore.apk" to 1L)))
    }

    /** 真的 Releases：最新版（Beta、RC 也會標為 Latest）要能解析出版本號與 APK。 */
    @Test
    fun latestFromGitHub() {
        val latest = live { GitHubReleases.latest("hot-YUser/FlitStore") } ?: error("no release")
        assertTrue(latest.versionName, Regex("\\d+(\\.\\d+)+(-(alpha|beta|rc)\\.\\d+)?").matches(latest.versionName))
        assertTrue(latest.apkUrl, latest.apkUrl.startsWith("https://github.com/hot-YUser/FlitStore/releases/download/"))
        assertTrue(latest.apkSize > 0)
    }
}
