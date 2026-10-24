package dev.yyblite.install

import android.os.SystemClock
import java.io.File
import java.io.IOException

data class ShellResult(val code: Int, val out: String, val err: String) {
    val ok: Boolean get() = code == 0
}

object ShellExec {
    /**
     * 執行外部指令並收集輸出。stdin 必須在讀取 stdout 之前寫完，
     * 否則 `pm install-write` 會與我方互等 pipe。
     */
    fun exec(argv: List<String>, timeoutMs: Long, stdin: File? = null): ShellResult {
        val process = ProcessBuilder(argv).start()
        val errBuffer = StringBuilder()
        val errThread = Thread {
            runCatching {
                process.errorStream.bufferedReader().use { errBuffer.append(it.readText()) }
            }
        }.apply { start() }
        try {
            if (stdin != null) {
                stdin.inputStream().use { input ->
                    process.outputStream.use { output -> input.copyTo(output) }
                }
            } else {
                runCatching { process.outputStream.close() }
            }
            val out = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val code = waitFor(process, timeoutMs) ?: throw IOException("command timed out: ${argv.firstOrNull()}")
            errThread.join(2000)
            return ShellResult(code, out, errBuffer.toString().trim())
        } finally {
            if (exitCode(process) == null) process.destroy()
        }
    }

    /** `Process.waitFor(timeout)` 要 API 26；minSdk 24 以輪詢 exitValue 實作，逾時回 null。 */
    private fun waitFor(process: Process, timeoutMs: Long): Int? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            exitCode(process)?.let { return it }
            if (SystemClock.elapsedRealtime() >= deadline) return null
            Thread.sleep(20)
        }
    }

    private fun exitCode(process: Process): Int? = try {
        process.exitValue()
    } catch (_: IllegalThreadStateException) {
        null
    }
}
