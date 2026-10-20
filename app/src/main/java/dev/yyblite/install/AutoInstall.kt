package dev.yyblite.install

import android.content.Context
import android.provider.Settings
import dev.yyblite.ui.AppVisibility
import java.io.File

/**
 * 下載完成後自動交給 InstallerX 安裝。
 *
 * - 前景（有可見 Activity）或已授權懸浮視窗：直接啟動安裝器。
 * - 背景且無授權：Android 會以 BAL 阻擋，改由通知接手（使用者點一下即安裝）。
 * 同一版本只自動觸發一次，取消後可從列表按鈕手動重試。
 */
object AutoInstall {
    private const val PREFS = "auto_install"

    fun tryInstall(ctx: Context, pkg: String, version: String, abi: String): Boolean {
        val file = File(ctx.getExternalFilesDir("apk"), "${pkg}_${version}_${abi}.apk")
        if (!file.exists()) return false

        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "$pkg@$version@$abi"
        if (prefs.getBoolean(key, false)) return false

        if (!AppVisibility.isVisible() && !Settings.canDrawOverlays(ctx)) return false

        prefs.edit().putBoolean(key, true).apply()
        InstallHandoff.install(ctx, file)
        return true
    }
}
