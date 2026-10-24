package dev.yyblite.install

import android.content.Context
import android.os.SystemClock
import android.provider.Settings as SystemSettings
import android.util.Log
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import dev.yyblite.data.DexoptMode
import dev.yyblite.data.InstallerKind
import dev.yyblite.data.InstallerMode
import dev.yyblite.data.Settings
import dev.yyblite.platform.AppVisibility
import java.io.File
import java.util.concurrent.ConcurrentHashMap

sealed interface InstallOutcome {
    data class Installed(val via: InstallerKind) : InstallOutcome
    /** 交給外部安裝器（使用者在安裝器畫面確認）。 */
    data object Handoff : InstallOutcome
    data class Failed(val via: InstallerKind, val message: String) : InstallOutcome
    /** 同一個檔案已在安裝中。 */
    data object Busy : InstallOutcome
}

data class DowngradeInfo(
    val targetVersion: String?,
    val installedVersion: String?,
    val targetCode: Long,
    val installedCode: Long
)

data class InstallerStatus(
    val mode: InstallerMode,
    val rootBinary: Boolean,
    val rootGranted: Boolean?,
    val shizukuAlive: Boolean,
    val shizukuGranted: Boolean,
    val effective: InstallerKind
)

/**
 * 安裝優先序：Root → Shizuku → 外部安裝器（InstallerX）。
 * 使用者可在設定指定通道；指定的不可用時依序退回下一個。
 */
class InstallManager(private val ctx: Context, private val settings: Settings) {
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val autoPrefs = ctx.getSharedPreferences("auto_install", Context.MODE_PRIVATE)

    fun resolve(): InstallerKind {
        val mode = settings.installerMode
        if (mode == InstallerMode.EXTERNAL) return InstallerKind.EXTERNAL
        val root = RootInstaller.isGranted()
        val shizuku = ShizukuInstaller.isGranted()
        return pick(mode, root, shizuku)
    }

    /** 阻塞式安裝；請在背景執行緒呼叫。 */
    fun install(file: File): InstallOutcome {
        if (!inFlight.add(file.absolutePath)) return InstallOutcome.Busy
        val started = SystemClock.elapsedRealtime()
        try {
            val kind = resolve()
            val fast = settings.dexoptMode == DexoptMode.FAST
            val outcome = when (kind) {
                InstallerKind.ROOT -> runCatching {
                    RootInstaller.install(listOf(file), fast)
                    InstallOutcome.Installed(kind)
                }.getOrElse { InstallOutcome.Failed(kind, it.message ?: "root install failed") }
                InstallerKind.SHIZUKU -> runCatching {
                    ShizukuInstaller.install(listOf(file), fast)
                    InstallOutcome.Installed(kind)
                }.getOrElse { InstallOutcome.Failed(kind, it.message ?: "shizuku install failed") }
                InstallerKind.EXTERNAL -> InstallOutcome.Handoff
            }
            Log.i("YYBLite", "install ${file.name} via $kind ${SystemClock.elapsedRealtime() - started}ms fast=$fast")
            if (outcome is InstallOutcome.Installed) file.delete()
            return outcome
        } finally {
            inFlight.remove(file.absolutePath)
        }
    }

    /** 目標版本低於已安裝版本時回傳降級資訊；非降級或讀不到回 null。 */
    fun downgradeInfo(file: File): DowngradeInfo? {
        val pm = ctx.packageManager
        val archive = pm.getPackageArchiveInfo(file.absolutePath, 0) ?: return null
        val pkg = archive.packageName ?: return null
        val installed = try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0)
        } catch (_: Exception) {
            return null
        }
        val targetCode = PackageInfoCompat.getLongVersionCode(archive)
        val installedCode = PackageInfoCompat.getLongVersionCode(installed)
        if (targetCode >= installedCode) return null
        return DowngradeInfo(archive.versionName, installed.versionName, targetCode, installedCode)
    }

    /** 不觸發 su 授權視窗的狀態快照，供設定頁顯示；有效通道與實際安裝時的判斷一致。 */
    fun status(): InstallerStatus {
        val mode = settings.installerMode
        val rootBinary = RootInstaller.binaryPresent()
        val shizukuAlive = ShizukuInstaller.binderAlive()
        val shizukuGranted = shizukuAlive && ShizukuInstaller.isGranted()
        val rootUsable = RootInstaller.lastKnown() ?: rootBinary
        return InstallerStatus(
            mode = mode,
            rootBinary = rootBinary,
            rootGranted = RootInstaller.lastKnown(),
            shizukuAlive = shizukuAlive,
            shizukuGranted = shizukuGranted,
            effective = if (mode == InstallerMode.EXTERNAL) InstallerKind.EXTERNAL else pick(mode, rootUsable, shizukuGranted)
        )
    }

    /**
     * 下載完成後自動交給外部安裝器：前景或已授權懸浮視窗時直接開啟；
     * 背景且無授權時 Android 會擋（BAL），改由通知接手。同一版本只自動觸發一次。
     */
    fun tryAutoHandoff(pkg: String, version: String, file: File): Boolean {
        if (!file.exists()) return false
        val key = "$pkg@$version@${file.name}"
        if (autoPrefs.getBoolean(key, false)) return false
        if (!AppVisibility.isVisible() && !SystemSettings.canDrawOverlays(ctx)) return false
        autoPrefs.edit { putBoolean(key, true) }
        InstallHandoff.install(ctx, file)
        return true
    }

    /** 重新下載同一套件時重置自動安裝紀錄，讓新檔案能再次自動觸發。 */
    fun resetAuto(pkg: String) {
        val stale = autoPrefs.all.keys.filter { it.startsWith("$pkg@") }
        if (stale.isNotEmpty()) autoPrefs.edit { stale.forEach(::remove) }
    }

    private fun pick(mode: InstallerMode, root: Boolean, shizuku: Boolean): InstallerKind = when (mode) {
        InstallerMode.EXTERNAL -> InstallerKind.EXTERNAL
        InstallerMode.SHIZUKU -> when {
            shizuku -> InstallerKind.SHIZUKU
            root -> InstallerKind.ROOT
            else -> InstallerKind.EXTERNAL
        }
        InstallerMode.ROOT, InstallerMode.AUTO -> when {
            root -> InstallerKind.ROOT
            shizuku -> InstallerKind.SHIZUKU
            else -> InstallerKind.EXTERNAL
        }
    }
}
