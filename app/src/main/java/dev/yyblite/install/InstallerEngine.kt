package dev.yyblite.install

import android.content.Context
import java.io.File

sealed interface InstallOutcome {
    data class Installed(val via: InstallerKind, val detail: String = "") : InstallOutcome
    data object Handoff : InstallOutcome
    data class Failed(val via: InstallerKind, val message: String) : InstallOutcome
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
 * 使用者可在「安裝方式」手動指定；指定的通道不可用時依序退回下一個。
 */
object InstallerEngine {
    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun resolve(ctx: Context): InstallerKind {
        val mode = InstallerPrefs(ctx).mode
        if (mode == InstallerMode.EXTERNAL) return InstallerKind.EXTERNAL
        val root = RootInstaller.isGranted()
        val shizuku = ShizukuInstaller.isGranted()
        return when (mode) {
            InstallerMode.EXTERNAL -> InstallerKind.EXTERNAL
            InstallerMode.ROOT ->
                if (root) InstallerKind.ROOT
                else if (shizuku) InstallerKind.SHIZUKU
                else InstallerKind.EXTERNAL
            InstallerMode.SHIZUKU ->
                if (shizuku) InstallerKind.SHIZUKU
                else if (root) InstallerKind.ROOT
                else InstallerKind.EXTERNAL
            InstallerMode.AUTO ->
                if (root) InstallerKind.ROOT
                else if (shizuku) InstallerKind.SHIZUKU
                else InstallerKind.EXTERNAL
        }
    }

    /** 阻塞式安裝；請在背景執行緒呼叫。files 可包含 base APK 與 splits。 */
    fun install(ctx: Context, files: List<File>): InstallOutcome {
        val key = files.firstOrNull()?.nameWithoutExtension ?: "unknown"
        if (!inFlight.add(key)) {
            return InstallOutcome.Failed(InstallerKind.ROOT, "已在安裝中")
        }
        try {
            val kind = resolve(ctx)
            val outcome = when (kind) {
            InstallerKind.ROOT -> runCatching {
                RootInstaller.install(files)
                InstallOutcome.Installed(InstallerKind.ROOT)
            }.getOrElse { e ->
                InstallOutcome.Failed(InstallerKind.ROOT, e.message ?: "Root 安裝失敗")
            }
            InstallerKind.SHIZUKU -> runCatching {
                ShizukuInstaller.install(files)
                InstallOutcome.Installed(InstallerKind.SHIZUKU)
            }.getOrElse { e ->
                InstallOutcome.Failed(InstallerKind.SHIZUKU, e.message ?: "Shizuku 安裝失敗")
            }
            InstallerKind.EXTERNAL -> InstallOutcome.Handoff
            }
            if (outcome is InstallOutcome.Installed) ApkStore.delete(files)
            return outcome
        } finally {
            inFlight.remove(key)
        }
    }

    /** 回傳降級資訊（目標版本低於已安裝版本）；非降級回 null。 */
    fun downgradeInfo(ctx: Context, files: List<File>): DowngradeInfo? {
        val apk = files.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) } ?: return null
        val pm = ctx.packageManager
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, 0) ?: return null
        val pkg = archive.packageName ?: return null
        val installed = try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0)
        } catch (_: Exception) {
            return null
        }
        val targetCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
            archive.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            archive.versionCode.toLong()
        }
        val installedCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
            installed.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            installed.versionCode.toLong()
        }
        if (targetCode >= installedCode) return null
        return DowngradeInfo(
            targetVersion = archive.versionName,
            installedVersion = installed.versionName,
            targetCode = targetCode,
            installedCode = installedCode
        )
    }

    /** 不觸發 su 授權視窗的狀態快照，供設定頁顯示。 */
    fun status(ctx: Context): InstallerStatus {
        val mode = InstallerPrefs(ctx).mode
        val rootBinary = RootInstaller.binaryPresent()
        val shizukuAlive = ShizukuInstaller.binderAlive()
        val shizukuGranted = shizukuAlive && ShizukuInstaller.isGranted()
        val effective = when {
            rootBinary -> InstallerKind.ROOT
            shizukuGranted -> InstallerKind.SHIZUKU
            else -> InstallerKind.EXTERNAL
        }
        return InstallerStatus(
            mode = mode,
            rootBinary = rootBinary,
            rootGranted = RootInstaller.lastKnown(),
            shizukuAlive = shizukuAlive,
            shizukuGranted = shizukuGranted,
            effective = effective
        )
    }
}
