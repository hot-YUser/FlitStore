package dev.yyblite.data

import android.content.Context
import android.content.pm.ApplicationInfo

data class InstalledApp(
    val pkg: String,
    val label: String,
    val versionName: String,
    val versionCode: Long
)

object InstalledScanner {
    fun scan(ctx: Context): List<InstalledApp> {
        val pm = ctx.packageManager
        val out = ArrayList<InstalledApp>()
        @Suppress("DEPRECATION")
        val packages = pm.getInstalledPackages(0)
        for (info in packages) {
            val app = info.applicationInfo ?: continue
            if (info.packageName == ctx.packageName) continue
            val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val updatedSystem = (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (isSystem && !updatedSystem) continue
            if (pm.getLaunchIntentForPackage(info.packageName) == null) continue
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
