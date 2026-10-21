package dev.yyblite.install

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

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
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw IOException("指令逾時：${argv.firstOrNull()}")
            }
            errThread.join(2000)
            return ShellResult(process.exitValue(), out, errBuffer.toString().trim())
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
