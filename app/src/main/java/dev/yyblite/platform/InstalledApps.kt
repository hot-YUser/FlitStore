package dev.yyblite.platform

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import dev.yyblite.core.update.InstalledApp

object InstalledApps {
    /** 原版應用寶本身：商店頁對它不是一般 App 頁，會拖慢並拉高失敗數，直接排除。 */
    private val EXCLUDED = setOf("com.tencent.android.qqdownloader")

    /** 使用者裝的、有桌面入口的應用（含已更新過的系統應用），依名稱排序。 */
    fun scan(ctx: Context): List<InstalledApp> {
        val pm = ctx.packageManager
        // 一次查詢所有桌面入口，避免逐一 getLaunchIntentForPackage。
        val launchers = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).mapNotNull { it.activityInfo?.packageName }.toSet()

        @Suppress("DEPRECATION")
        val packages = pm.getInstalledPackages(0)
        return packages.asSequence()
            .filter { it.packageName in launchers && eligible(it) }
            .map { toInstalled(pm, it) }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /** 單一套件，套用與 [scan] 相同的篩選；不符合或未安裝回 null。 */
    fun read(ctx: Context, pkg: String): InstalledApp? {
        val pm = ctx.packageManager
        val info = info(pm, pkg) ?: return null
        if (!eligible(info) || pm.getLaunchIntentForPackage(pkg) == null) return null
        return toInstalled(pm, info)
    }

    /** 任何已安裝套件（含系統應用）；搜尋結果判斷「已安裝」用。 */
    fun any(ctx: Context, pkg: String): InstalledApp? {
        val pm = ctx.packageManager
        return info(pm, pkg)?.let { toInstalled(pm, it) }
    }

    fun launchIntent(ctx: Context, pkg: String): Intent? = ctx.packageManager.getLaunchIntentForPackage(pkg)

    private fun info(pm: PackageManager, pkg: String): PackageInfo? = try {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(pkg, 0)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun eligible(info: PackageInfo): Boolean {
        val app = info.applicationInfo ?: return false
        if (info.packageName in EXCLUDED) return false
        if (!app.enabled) return false
        val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val updatedSystem = (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        return !isSystem || updatedSystem
    }

    private fun toInstalled(pm: PackageManager, info: PackageInfo) = InstalledApp(
        pkg = info.packageName,
        label = info.applicationInfo?.loadLabel(pm)?.toString() ?: info.packageName,
        versionName = info.versionName.orEmpty(),
        versionCode = PackageInfoCompat.getLongVersionCode(info)
    )
}
