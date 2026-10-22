package dev.yyblite.work

import android.content.Context
import android.util.Log
import dev.yyblite.data.ErrorLog
import dev.yyblite.data.StoreApp
import dev.yyblite.data.Abi
import dev.yyblite.download.DownloadStarter
import dev.yyblite.install.RootInstaller
import dev.yyblite.install.ShellExec

/** 背景自動更新的共用入口：逐一啟動下載，失敗只記錄不中斷其他項目。 */
object AutoUpdate {
    fun start(ctx: Context, apps: List<StoreApp>): List<StoreApp> {
        val prefs = AutoUpdatePrefs(ctx)
        val started = ArrayList<StoreApp>(apps.size)
        for (app in apps) {
            try {
                try {
                    DownloadStarter.start(ctx, app)
                } catch (e: Exception) {
                    // Android 12+ 背景禁止啟動前景服務；有 Root 時改由 su 代為啟動。
                    if (!RootInstaller.isGranted() || !startViaRoot(ctx, app)) throw e
                }
                prefs.markAttempt(app.pkg, app.versionName)
                started.add(app)
            } catch (e: Exception) {
                Log.w("YYBLite", "auto update start failed ${app.pkg}", e)
                ErrorLog.record("auto-update", "${app.pkg}: ${e.message}")
            }
        }
        Log.i("YYBLite", "auto update started ${started.size}/${apps.size}")
        return started
    }

    /** 以 su 執行 am start-foreground-service，繞過背景 FGS 限制（僅在已授權 Root 時）。 */
    private fun startViaRoot(ctx: Context, app: StoreApp): Boolean {
        val url = DownloadStarter.urlFor(app)
        val abi = DownloadStarter.abiTag(app)
        val md5 = Abi.md5For(app, url)
        val signature = app.signatureMd5
        fun q(value: String) = "'" + value.replace("'", "'\\''") + "'"
        val command = buildString {
            append("am start-foreground-service -n ")
            append(ctx.packageName).append("/.download.DownloadService")
            append(" --es pkg ").append(q(app.pkg))
            append(" --es name ").append(q(app.name))
            append(" --es version ").append(q(app.versionName))
            append(" --es url ").append(q(url))
            append(" --es md5 ").append(q(md5))
            append(" --es abi ").append(q(abi))
            if (signature != null) append(" --es signature ").append(q(signature))
            append(" --el size ").append(app.sizeBytes)
        }
        val result = ShellExec.exec(listOf("su", "-c", command), 15_000)
        if (!result.ok) Log.w("YYBLite", "root fgs start failed: ${result.err.ifBlank { result.out }}")
        return result.ok
    }
}
