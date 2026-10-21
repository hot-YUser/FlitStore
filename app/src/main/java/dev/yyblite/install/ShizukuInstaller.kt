package dev.yyblite.install

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.File
import java.io.IOException

/**
 * 透過 Shizuku server 以 shell uid 執行 `pm install-*`。
 * APK 以 stdin 串流給 `pm install-write`，因為 shell 讀不到 App 的私有目錄。
 */
object ShizukuInstaller {
    const val REQUEST_CODE = 4213

    /** 與 Aurora 相同：安裝來源標記為 Play。 */
    private const val INSTALLER_PACKAGE = "com.android.vending"

    fun binderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun isGranted(): Boolean =
        binderAlive() && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    fun requestPermission() {
        if (binderAlive()) Shizuku.requestPermission(REQUEST_CODE)
    }

    fun install(files: List<File>): String {
        val apks = files.filter { it.exists() && it.name.endsWith(".apk", ignoreCase = true) }
        if (apks.isEmpty()) throw IOException("找不到 APK")
        val service = IShizukuService.Stub.asInterface(Shizuku.getBinder())
            ?: throw IOException("Shizuku 未連線")
        if (service.uid != 2000 && service.uid != 0) {
            // shell 或 root 皆可；其他 uid 代表授權異常。
            throw IOException("Shizuku 權限異常（uid=${service.uid}）")
        }

        val total = apks.sumOf { it.length() }
        val userId = (android.os.Process.myUid() / 100_000).toString()
        val created = exec(
            service,
            arrayOf(
                "pm", "install-create", "-i", INSTALLER_PACKAGE,
                "--user", userId, "-r", "-S", total.toString()
            )
        )
        val sessionId = Regex("""\[(\d+)]""").find(created)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw IOException("建立安裝工作階段失敗：$created")

        try {
            for (apk in apks) {
                exec(
                    service,
                    arrayOf(
                        "pm", "install-write", "-S", apk.length().toString(),
                        sessionId.toString(), apk.name, "-"
                    ),
                    stdin = apk
                )
            }
            exec(service, arrayOf("pm", "install-commit", sessionId.toString()))
        } catch (e: Exception) {
            runCatching { exec(service, arrayOf("pm", "install-abandon", sessionId.toString())) }
            throw e
        }
        return "Success"
    }

    private fun exec(service: IShizukuService, command: Array<String>, stdin: File? = null): String {
        val process: IRemoteProcess = service.newProcess(command, null, null)
        val errBuffer = StringBuilder()
        val errThread = Thread {
            runCatching {
                ParcelFileDescriptor.AutoCloseInputStream(process.errorStream)
                    .bufferedReader().use { errBuffer.append(it.readText()) }
            }
        }.apply { start() }

        try {
            if (stdin != null) {
                ParcelFileDescriptor.AutoCloseOutputStream(process.outputStream).use { output ->
                    stdin.inputStream().use { input -> input.copyTo(output) }
                }
            } else {
                runCatching {
                    ParcelFileDescriptor.AutoCloseOutputStream(process.outputStream).close()
                }
            }

            val out = ParcelFileDescriptor.AutoCloseInputStream(process.inputStream)
                .bufferedReader().use { it.readText() }.trim()
            val exit = process.waitFor()
            errThread.join(2000)
            val err = errBuffer.toString().trim()
            if (exit != 0) {
                throw IOException(err.ifBlank { out }.ifBlank { "pm 結束碼 $exit" })
            }
            return out.ifBlank { err }
        } finally {
            runCatching { process.destroy() }
        }
    }
}
