package dev.yyblite.data

import android.content.Context
import androidx.core.content.edit
import dev.yyblite.install.RootInstaller

enum class InstallerMode { AUTO, ROOT, SHIZUKU, EXTERNAL }

enum class InstallerKind { ROOT, SHIZUKU, EXTERNAL }

/** 安裝時的 dexopt 策略。FAST 跳過 dexopt（首次啟動改由 JIT），實測可縮短約 3 倍安裝時間。 */
enum class DexoptMode { STANDARD, FAST }

/** 使用者設定。檔名與鍵沿用 v0.1.0，升級後設定不會遺失。 */
class Settings(ctx: Context) {
    private val installer = ctx.getSharedPreferences("installer", Context.MODE_PRIVATE)
    private val auto = ctx.getSharedPreferences("auto_update", Context.MODE_PRIVATE)
    private val ui = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)

    var installerMode: InstallerMode
        get() = enumOr(installer.getString("mode", null), InstallerMode.AUTO)
        set(value) {
            installer.edit { putString("mode", value.name) }
            RootInstaller.invalidate()
        }

    var dexoptMode: DexoptMode
        get() = enumOr(installer.getString("dexopt", null), DexoptMode.STANDARD)
        set(value) = installer.edit { putString("dexopt", value.name) }

    /** 背景自動下載與安裝；關閉時一併清掉嘗試紀錄。 */
    var autoUpdate: Boolean
        get() = auto.getBoolean("enabled", false)
        set(value) {
            if (value) auto.edit { putBoolean("enabled", true) } else auto.edit { clear() }
        }

    /** 同一套件同一版本 12 小時內只自動嘗試一次，避免安裝失敗時每 3 小時重抓。 */
    fun shouldAttemptAuto(pkg: String, version: String): Boolean =
        System.currentTimeMillis() - auto.getLong("$pkg@$version", 0L) > AUTO_COOLDOWN_MS

    fun markAutoAttempt(pkg: String, version: String) {
        auto.edit { putLong("$pkg@$version", System.currentTimeMillis()) }
    }

    /** 開啟時顯示的分頁：分頁名稱，或 [START_LAST] 表示回到上次離開的分頁。 */
    var startTab: String
        get() = ui.getString("start_tab", null) ?: START_DEFAULT
        set(value) = ui.edit { putString("start_tab", value) }

    var lastTab: String?
        get() = ui.getString("last_tab", null)
        set(value) = ui.edit { putString("last_tab", value) }

    var overlayAsked: Boolean
        get() = ui.getBoolean("overlay_asked", false)
        set(value) = ui.edit { putBoolean("overlay_asked", value) }

    val searchHistory: List<String>
        get() = ui.getString("search_history", null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    fun addSearch(query: String) {
        val next = (listOf(query) + searchHistory.filterNot { it == query }).take(HISTORY_SIZE)
        ui.edit { putString("search_history", next.joinToString("\n")) }
    }

    fun clearSearchHistory() {
        ui.edit { remove("search_history") }
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        runCatching { enumValueOf<T>(name!!) }.getOrDefault(default)

    companion object {
        const val START_LAST = "LAST"
        const val START_DEFAULT = "Explore"
        private const val AUTO_COOLDOWN_MS = 12L * 60 * 60 * 1000
        private const val HISTORY_SIZE = 12
    }
}
