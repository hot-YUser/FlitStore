package dev.yyblite.install

import java.io.File
import java.io.IOException

/**
 * 以 root（su）執行 `pm install`。APK 位於 App 的 external files 目錄，
 * root 可直接讀取，不需要複製到 /data/local/tmp。
 */
object RootInstaller {
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

    fun invalidate() {
        granted = null
        checkedAt = 0L
    }

    fun install(apk: File): ShellResult {
        if (!apk.exists()) throw IOException("APK 不存在：${apk.name}")
        val path = apk.absolutePath.replace("'", "'\\''")
        val result = ShellExec.exec(
            listOf("su", "-c", "pm install -r -d --user 0 '$path'"),
            timeoutMs = 300_000
        )
        if (!result.ok || !result.out.contains("Success", ignoreCase = true)) {
            throw IOException(
                result.err.ifBlank { result.out }.ifBlank { "pm install 失敗（exit ${result.code}）" }
            )
        }
        return result
    }
}
