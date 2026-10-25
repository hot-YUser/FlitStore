package dev.yyblite.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.yyblite.download.ApkFiles

/**
 * 從「更新」分頁裝好自己的新版後，行程會被系統直接結束，畫面就這樣消失。
 * 新版第一次被叫起來時清掉安裝檔，並發一則「已更新」通知，點一下就回來。
 */
class SelfUpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pkg = context.packageName
        // 只有這次更新是自己下載安裝的（留有安裝檔）才通知；從別處更新的不打擾。
        val ours = ApkFiles.dir(context, pkg)?.listFiles()?.any { it.name.endsWith(".apk") } == true
        if (!ours) return
        ApkFiles.deleteFor(context, pkg)
        @Suppress("DEPRECATION")
        val version = runCatching { context.packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull() ?: return
        Notifications.selfUpdated(context, version)
    }
}
