package dev.yyblite.install

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.widget.Toast
import androidx.core.content.FileProvider
import dev.yyblite.R
import java.io.File

/**
 * 交給外部安裝器：優先 InstallerX，其次系統的套件安裝程式。
 * 系統安裝程式會檢查呼叫端有沒有宣告 REQUEST_INSTALL_PACKAGES，沒有就不出畫面直接結束。
 */
object InstallHandoff {
    private const val MIME = "application/vnd.android.package-archive"

    fun install(ctx: Context, apk: File) {
        if (!apk.exists()) {
            Toast.makeText(ctx, R.string.error_file_missing, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            ctx.startActivity(buildIntent(ctx, apk))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(ctx, R.string.error_installer_missing, Toast.LENGTH_SHORT).show()
        }
    }

    /** 建好安裝意圖並指定安裝器；兩者都找不到時不指定，交給系統挑。 */
    fun buildIntent(ctx: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val candidates = ctx.packageManager.queryIntentActivities(intent, 0)
        val installer = candidates.firstOrNull { it.activityInfo.packageName.startsWith("com.rosan.installer") }
            ?: candidates.firstOrNull { it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
        if (installer != null) {
            intent.component = ComponentName(installer.activityInfo.packageName, installer.activityInfo.name)
        }
        return intent
    }
}
