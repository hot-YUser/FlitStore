package dev.yyblite.install

import java.io.File
import java.io.IOException

/**
 * 以 root（su）執行 `pm install`。APK 位於 App 的 external files 目錄，
 * root 可直接讀取，不需要複製到 /data/local/tmp。
 */
object RootInstaller {
    /** 與 Aurora 相同，把安裝來源標記為 Play，讓套件維持「商店安裝」身分。 */
    private const val INSTALLER_PACKAGE = "com.android.vending"

    private val SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su",
        "/su/bin/su", "/debug_ramdisk/su"
    )

    @Volatile
    private var granted: Boolean? = null
    @Volatile
    private var checkedAt: Long = 0L

    fun binaryPresent(): Boolean =
        SU_PATHS.any { File(it).exists() } ||
            runCatching { ShellExec.exec(listOf("which", "su"), 3000).ok }.getOrDefault(false)

    /**
     * 會觸發 su 授權視窗。KernelSU 會把 su 從 App uid 隱藏，因此失敗屬正常；
     * 結果快取 5 分鐘，讓使用者授權後不用重啟 App。
     */
    fun isGranted(): Boolean {
        val now = System.currentTimeMillis()
        granted?.let { if (now - checkedAt < 300_000) return it }
        synchronized(this) {
            granted?.let { if (now - checkedAt < 300_000) return it }
            val ok = runCatching {
                val result = ShellExec.exec(listOf("su", "-c", "id"), 6000)
                result.ok && result.out.contains("uid=0")
            }.getOrDefault(false)
            granted = ok
            checkedAt = now
            android.util.Log.i("YYBLite", "root granted=$ok")
            return ok
        }
    }

    /** 最近一次 su 檢查結果；null 表示尚未檢查。 */
    fun lastKnown(): Boolean? = granted

    fun invalidate() {
        granted = null
        checkedAt = 0L
    }

    fun install(files: List<File>): ShellResult {
        val apks = files.filter { it.exists() && it.name.endsWith(".apk", ignoreCase = true) }
        if (apks.isEmpty()) throw IOException("找不到 APK")

        // 與 Aurora 相同：以 session 安裝並標記安裝來源，支援多個 split。
        val total = apks.sumOf { it.length() }
        val userId = android.os.Process.myUid() / 100_000
        val created = su(
            "pm install-create -i $INSTALLER_PACKAGE --user $userId -r -d -S $total"
        )
        if (!created.ok) throw IOException(created.err.ifBlank { created.out }.ifBlank { "建立安裝工作階段失敗" })
        val sessionId = Regex("""\[(\d+)]""").find(created.out)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw IOException("無法解析安裝工作階段：${created.out}")

        try {
            for (apk in apks) {
                val path = apk.absolutePath.replace("'", "'\\''")
                val written = su(
                    "cat '$path' | pm install-write -S ${apk.length()} $sessionId '${apk.name}'"
                )
                if (!written.ok) throw IOException(written.err.ifBlank { written.out })
            }
            val committed = su("pm install-commit $sessionId")
            if (!committed.ok || !committed.out.contains("Success", ignoreCase = true)) {
                throw IOException(
                    committed.err.ifBlank { committed.out }
                        .ifBlank { "pm install-commit 失敗（exit ${committed.code}）" }
                )
            }
            return committed
        } catch (e: Exception) {
            runCatching { su("pm install-abandon $sessionId") }
            throw e
        }
    }

    private fun su(command: String): ShellResult =
        ShellExec.exec(listOf("su", "-c", command), timeoutMs = 300_000)

}
