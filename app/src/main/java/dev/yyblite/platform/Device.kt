package dev.yyblite.platform

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import dev.yyblite.core.apk.Abi
import dev.yyblite.core.store.StoreApp
import java.util.concurrent.atomic.AtomicInteger

object Device {
    private val abis: List<String> = Build.SUPPORTED_ABIS.toList()

    fun urlFor(app: StoreApp): String = Abi.chooseUrl(app, abis)

    fun abiTag(app: StoreApp): String = if (Abi.isArm64(app, abis)) "arm64" else "v7a"

    fun isDebuggable(ctx: Context): Boolean =
        (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** 除錯版可用 `adb shell settings put global <key> <value>` 覆寫並發參數，方便實機壓測。 */
    fun debugInt(ctx: Context, key: String, default: Int): Int {
        if (!isDebuggable(ctx)) return default
        return runCatching { android.provider.Settings.Global.getInt(ctx.contentResolver, key, default) }
            .getOrDefault(default)
    }

    fun debugString(ctx: Context, key: String): String {
        if (!isDebuggable(ctx)) return ""
        return runCatching { android.provider.Settings.Global.getString(ctx.contentResolver, key) }
            .getOrNull().orEmpty()
    }
}

/** 是否有畫面在前景：前景時可直接啟動安裝器，背景需懸浮視窗授權。 */
object AppVisibility {
    private val active = AtomicInteger(0)

    fun onStart() {
        active.incrementAndGet()
    }

    fun onStop() {
        active.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    fun isVisible(): Boolean = active.get() > 0
}
