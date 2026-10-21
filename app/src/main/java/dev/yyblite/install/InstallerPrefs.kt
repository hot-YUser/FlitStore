package dev.yyblite.install

import android.content.Context

enum class InstallerMode { AUTO, ROOT, SHIZUKU, EXTERNAL }

enum class InstallerKind { ROOT, SHIZUKU, EXTERNAL }

class InstallerPrefs(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("installer", Context.MODE_PRIVATE)

    var mode: InstallerMode
        get() = runCatching {
            InstallerMode.valueOf(prefs.getString("mode", InstallerMode.AUTO.name)!!)
        }.getOrDefault(InstallerMode.AUTO)
        set(value) {
            prefs.edit().putString("mode", value.name).apply()
            RootInstaller.invalidate()
        }
}
