package dev.yyblite.core.update

import dev.yyblite.core.net.Http
import dev.yyblite.core.net.HttpStatusException
import org.json.JSONObject

/** GitHub 上最新的正式版（GitHub 的 latest 已排除草稿與預覽版）。 */
data class GitHubRelease(
    val tag: String,
    val versionName: String,
    val apkUrl: String,
    val apkSize: Long,
    val notes: String,
    val pageUrl: String
)

object GitHubReleases {

    /** [repo] 形如 `owner/name`；還沒發過正式版，或最新版沒有附 APK 時回 null。 */
    fun latest(repo: String): GitHubRelease? {
        val text = try {
            Http.getText("https://api.github.com/repos/$repo/releases/latest", readTimeoutMs = 15_000)
        } catch (e: HttpStatusException) {
            if (e.code == 404) return null
            throw e
        }
        return parse(text)
    }

    internal fun parse(json: String): GitHubRelease? {
        val root = JSONObject(json)
        val tag = root.optString("tag_name").ifBlank { return null }
        val assets = root.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (!asset.optString("name").endsWith(".apk", ignoreCase = true)) continue
            val url = asset.optString("browser_download_url").ifBlank { continue }
            return GitHubRelease(
                tag = tag,
                versionName = tag.removePrefix("v").removePrefix("V"),
                apkUrl = url,
                apkSize = asset.optLong("size"),
                notes = root.optString("body"),
                pageUrl = root.optString("html_url")
            )
        }
        return null
    }
}
