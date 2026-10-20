package dev.yyblite.install

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import dev.yyblite.R
import java.io.File

object InstallHandoff {
    private const val MIME = "application/vnd.android.package-archive"

    fun install(ctx: Context, apk: File) {
        if (!apk.exists()) {
            Toast.makeText(ctx, "檔案不存在：${apk.name}", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = buildIntent(ctx, apk)
        try {
            if (intent.component != null) {
                ctx.startActivity(intent)
            } else {
                ctx.startActivity(Intent.createChooser(intent, "安裝"))
            }
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(ctx, R.string.installer_missing, Toast.LENGTH_SHORT).show()
        }
    }

    /** 建好已指定 InstallerX 元件的安裝意圖；找不到時 component 為 null，由呼叫端決定 chooser 或通知。 */
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
            intent.component = ComponentName(
                installer.activityInfo.packageName,
                installer.activityInfo.name
            )
        }
        return intent
    }
}
