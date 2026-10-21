package dev.yyblite.install

import android.content.Context

enum class InstallerMode { AUTO, ROOT, SHIZUKU, EXTERNAL }

enum class InstallerKind { ROOT, SHIZUKU, EXTERNAL }

/** 安裝時的 dexopt 策略。FAST 跳過 dexopt（首次啟動改由 JIT），實測可縮短約 3 倍安裝時間。 */
enum class DexoptMode { STANDARD, FAST }

class InstallerPrefs(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("installer", Context.MODE_PRIVATE)

    var dexoptMode: DexoptMode
        get() = runCatching {
            DexoptMode.valueOf(prefs.getString("dexopt", DexoptMode.STANDARD.name)!!)
        }.getOrDefault(DexoptMode.STANDARD)
        set(value) {
            prefs.edit().putString("dexopt", value.name).apply()
        }

    var mode: InstallerMode
        get() = runCatching {
            InstallerMode.valueOf(prefs.getString("mode", InstallerMode.AUTO.name)!!)
        }.getOrDefault(InstallerMode.AUTO)
        set(value) {
            prefs.edit().putString("mode", value.name).apply()
            RootInstaller.invalidate()
        }
}
