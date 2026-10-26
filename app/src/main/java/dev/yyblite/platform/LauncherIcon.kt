package dev.yyblite.platform

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageManager.ComponentEnabledSetting
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import dev.yyblite.R

/**
 * 桌面圖示樣式：每種是 manifest 裡的一個 activity-alias，同一時間只啟用一個。
 * 目前用哪一種直接問系統，不另外存設定。
 */
enum class LauncherIcon(
    private val alias: String,
    @StringRes val label: Int,
    @DrawableRes val background: Int,
    @DrawableRes val foreground: Int,
    val classic: Boolean = false
) {
    DEFAULT("MainActivity", R.string.icon_default, R.drawable.ic_launcher_background, R.drawable.ic_launcher_foreground),
    INK("IconInk", R.string.icon_ink, R.drawable.ic_launcher_background, R.drawable.ic_launcher_ink_foreground),
    FROST("IconFrost", R.string.icon_frost, R.drawable.ic_launcher_frost_background, R.drawable.ic_launcher_frost_foreground),
    MARK("IconMark", R.string.icon_mark, R.drawable.ic_launcher_background, R.drawable.ic_flit_mark),
    MINIMAL("IconMinimal", R.string.icon_minimal, R.drawable.ic_launcher_background, R.drawable.ic_launcher_minimal_foreground),

    // 曾經用過的圖示。
    V01("IconV01", R.string.icon_v01, R.drawable.ic_launcher_v01_background, R.drawable.ic_launcher_v01_foreground, classic = true),
    V02("IconV02", R.string.icon_v02, R.drawable.ic_launcher_v02_background, R.drawable.ic_launcher_v02_foreground, classic = true),
    V021("IconV021", R.string.icon_v021, R.drawable.ic_launcher_v021_background, R.drawable.ic_launcher_v021_foreground, classic = true);

    // 別名宣告在 manifest 的 dev.yyblite 命名空間下。
    private fun component(ctx: Context) = ComponentName(ctx.packageName, "dev.yyblite.$alias")

    companion object {
        // 選好的圖示等離開 App 才套用：停用目前的入口時，系統會一併關掉從它開啟的畫面。
        @Volatile
        private var pending: LauncherIcon? = null

        fun current(ctx: Context): LauncherIcon = pending ?: enabled(ctx)

        fun choose(ctx: Context, icon: LauncherIcon) {
            pending = icon.takeIf { it != enabled(ctx) }
        }

        fun applyPending(ctx: Context) {
            val target = pending ?: return
            pending = null
            apply(ctx, target)
        }

        private fun enabled(ctx: Context): LauncherIcon {
            val pm = ctx.packageManager
            return entries.firstOrNull { icon ->
                when (pm.getComponentEnabledSetting(icon.component(ctx))) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon == DEFAULT
                    else -> false
                }
            } ?: DEFAULT
        }

        /** 先啟用新的再停用其餘，桌面入口不會有一刻全部消失；各別名都盡量回到 manifest 的原狀。 */
        private fun apply(ctx: Context, target: LauncherIcon) {
            val changes = entries.sortedByDescending { it == target }.map { icon ->
                val state = when {
                    icon == target && icon == DEFAULT -> PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    icon == target -> PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    icon == DEFAULT -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    else -> PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                }
                icon.component(ctx) to state
            }
            val pm = ctx.packageManager
            if (Build.VERSION.SDK_INT >= 33) {
                pm.setComponentEnabledSettings(changes.map { (cn, state) -> ComponentEnabledSetting(cn, state, PackageManager.DONT_KILL_APP) })
            } else {
                changes.forEach { (cn, state) -> pm.setComponentEnabledSetting(cn, state, PackageManager.DONT_KILL_APP) }
            }
        }
    }
}
