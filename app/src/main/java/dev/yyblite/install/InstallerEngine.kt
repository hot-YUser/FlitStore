package dev.yyblite.install

import android.content.Context
import java.io.File

sealed interface InstallOutcome {
    data class Installed(val via: InstallerKind, val detail: String = "") : InstallOutcome
    data object Handoff : InstallOutcome
    data class Failed(val via: InstallerKind, val message: String) : InstallOutcome
}

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
            return when (kind) {
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
        } finally {
            inFlight.remove(key)
        }
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
