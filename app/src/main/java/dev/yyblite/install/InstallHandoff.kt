package dev.yyblite.install

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import dev.yyblite.R
import java.io.File

/** 交給外部安裝器：優先指定 InstallerX，找不到時讓系統選擇。 */
object InstallHandoff {
    private const val MIME = "application/vnd.android.package-archive"

    fun install(ctx: Context, apk: File) {
        if (!apk.exists()) {
            Toast.makeText(ctx, R.string.error_file_missing, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = buildIntent(ctx, apk)
        try {
            if (intent.component != null) {
                ctx.startActivity(intent)
            } else {
                ctx.startActivity(
                    Intent.createChooser(intent, ctx.getString(R.string.action_install))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(ctx, R.string.error_installer_missing, Toast.LENGTH_SHORT).show()
        }
    }

    /** 建好已指定 InstallerX 元件的安裝意圖；找不到時 component 為 null。 */
    fun buildIntent(ctx: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val installer = ctx.packageManager
            .queryIntentActivities(intent, 0)
            .firstOrNull { it.activityInfo.packageName.startsWith("com.rosan.installer") }
        if (installer != null) {
            intent.component = ComponentName(installer.activityInfo.packageName, installer.activityInfo.name)
        }
        return intent
    }
}
