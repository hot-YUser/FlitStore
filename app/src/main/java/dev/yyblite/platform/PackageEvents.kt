package dev.yyblite.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import dev.yyblite.YybApp

/**
 * App 層級的安裝／移除監聽，跟著行程而不是跟著畫面：
 * 不論刷新是否正在進行、畫面是否開著，事件都會送進 repository，不再被丟掉。
 */
object PackageEvents {
    fun register(app: YybApp) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(app, Receiver(app), filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private class Receiver(private val app: YybApp) : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart ?: return
            if (pkg == context.packageName) return
            val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            when (intent.action) {
                Intent.ACTION_PACKAGE_REMOVED -> if (!replacing) app.graph.updates.onPackageRemoved(pkg)
                Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REPLACED -> {
                    // REPLACED 之前會先收到一次 replacing 的 ADDED，只處理一次即可。
                    if (intent.action == Intent.ACTION_PACKAGE_ADDED && replacing) return
                    app.graph.downloads.onPackageInstalled(pkg)
                    app.graph.updates.onPackageChanged(pkg)
                }
            }
        }
    }
}
