package dev.yyblite.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo

data class InstalledApp(
    val pkg: String,
    val label: String,
    val versionName: String,
    val versionCode: Long
)

object InstalledScanner {
    /** 原版應用寶本身：商店頁對它不是一般 App 頁，會拖慢並拉高失敗數，直接排除。 */
    private val EXCLUDED = setOf("com.tencent.android.qqdownloader")

    fun scan(ctx: Context): List<InstalledApp> {
        val pm = ctx.packageManager
        val out = ArrayList<InstalledApp>()
        // 一次查詢所有桌面入口，避免對 259 個套件逐一 getLaunchIntentForPackage。
        val launchers = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).mapNotNull { it.activityInfo?.packageName }.toSet()

        @Suppress("DEPRECATION")
        val packages = pm.getInstalledPackages(0)
        for (info in packages) {
            val app = info.applicationInfo ?: continue
            if (info.packageName == ctx.packageName) continue
            if (info.packageName in EXCLUDED) continue
            if (!app.enabled) continue
            val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val updatedSystem = (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (isSystem && !updatedSystem) continue
            if (info.packageName !in launchers) continue
            out.add(
                InstalledApp(
                    pkg = info.packageName,
                    label = app.loadLabel(pm).toString(),
                    versionName = info.versionName ?: "",
                    versionCode = info.longVersionCode
                )
            )
        }
        return out.sortedBy { it.label.lowercase() }
    }
}
