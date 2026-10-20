package dev.yyblite.download

import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import dev.yyblite.data.Abi
import dev.yyblite.data.StoreApp
import dev.yyblite.install.AutoInstall
import java.io.File

/** 下載入口共用邏輯：依裝置 ABI 選連結、組出檔案路徑、啟動前景服務。 */
object DownloadStarter {

    fun urlFor(app: StoreApp): String = Abi.chooseUrl(app, Build.SUPPORTED_ABIS.toList())

    fun abiTag(app: StoreApp): String =
        if (Abi.isArm64(app, Build.SUPPORTED_ABIS.toList())) "arm64" else "v7a"

    fun apkFile(ctx: Context, app: StoreApp): File =
        File(ctx.getExternalFilesDir("apk"), "${app.pkg}_${app.versionName}_${abiTag(app)}.apk")

    fun start(ctx: Context, app: StoreApp) {
        AutoInstall.reset(ctx, app.pkg)
        val url = urlFor(app)
        ContextCompat.startForegroundService(
            ctx,
            DownloadService.intent(ctx, app, url, abiTag(app), Abi.md5For(app, url))
        )
    }
}
