package dev.yyblite.data

import android.content.Context
import dev.yyblite.core.store.LookupResult
import dev.yyblite.core.store.StoreApp
import dev.yyblite.core.update.GitHubRelease
import dev.yyblite.core.update.GitHubReleases
import dev.yyblite.platform.Signatures
import java.util.Locale

/**
 * FlitStore 自己不在應用寶上架，改從 GitHub Releases 檢查新版。
 * 結果包成 [StoreApp]，和其他應用走同一套更新判定、下載與安裝。
 */
class SelfUpdate(private val ctx: Context) {
    val pkg: String = ctx.packageName

    @Volatile private var last: LookupResult.Found? = null
    @Volatile private var lastAt = 0L

    /** 10 分鐘內查過就直接用：GitHub 未登入的 API 每小時只給 60 次。 */
    fun lookup(): LookupResult {
        last?.let { if (System.currentTimeMillis() - lastAt < FRESH_MS) return it }
        return try {
            val release = GitHubReleases.latest(REPO) ?: return LookupResult.NotInStore
            LookupResult.Found(toStoreApp(release)).also {
                last = it
                lastAt = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            LookupResult.Failed(e)
        }
    }

    private fun toStoreApp(release: GitHubRelease) = StoreApp(
        pkg = pkg,
        name = ctx.applicationInfo.loadLabel(ctx.packageManager).toString(),
        versionName = release.versionName,
        versionCode = null,
        apkUrl = release.apkUrl,
        apkUrl64 = null,
        apkMd5 = "",
        // 只接受和目前安裝相同金鑰簽的 APK：下載完會先比對，不符就不安裝。
        signatureMd5 = Signatures.installed(ctx, pkg).firstOrNull(),
        sizeBytes = release.apkSize,
        sizeDesc = null,
        iconUrl = null,
        developer = null,
        downloadDesc = null,
        rating = null,
        screenshots = emptyList(),
        editorIntro = notes(release.notes),
        detailed = true
    )

    /** Release 說明是「中文 --- 英文 + 校驗碼表格」：依介面語言取一段，去掉表格與程式碼標記。 */
    private fun notes(body: String): String? {
        val parts = body.replace("\r\n", "\n").split(Regex("\n-{3,}\n"))
        val chinese = ctx.resources.configuration.locales[0].language == Locale.CHINESE.language
        val section = if (chinese) parts.first() else parts.getOrElse(1) { parts.first() }
        return section.lines()
            .filterNot { it.trimStart().startsWith("|") }
            .joinToString("\n")
            .replace("`", "")
            .trim()
            .ifBlank { null }
    }

    companion object {
        const val REPO = "hot-YUser/FlitStore"
        private const val FRESH_MS = 10 * 60 * 1000L
    }
}
