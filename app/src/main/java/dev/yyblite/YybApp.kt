package dev.yyblite

import android.app.Application
import android.content.Context
import dev.yyblite.data.ErrorLog
import dev.yyblite.data.IgnoreStore
import dev.yyblite.data.SelfUpdate
import dev.yyblite.data.Settings
import dev.yyblite.data.StoreRepository
import dev.yyblite.data.UpdateCache
import dev.yyblite.data.UpdateRepository
import dev.yyblite.download.ApkFiles
import dev.yyblite.download.DownloadManager
import dev.yyblite.install.InstallManager
import dev.yyblite.platform.Notifications
import dev.yyblite.platform.PackageEvents
import dev.yyblite.ui.image.ThumbCache
import dev.yyblite.work.UpdateWorker
import java.util.concurrent.Executors

class YybApp : Application() {
    val graph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        ErrorLog.init(this)
        Notifications.ensureChannels(this)
        PackageEvents.register(this)
        UpdateWorker.schedule(this)
        // 快取檔在背景先載入，第一次刷新與畫面讀取時就不必在主執行緒等磁碟。
        Executors.newSingleThreadExecutor().apply {
            execute {
                graph.cache.warmUp()
                ThumbCache.pruneIfDue(this@YybApp)
                ApkFiles.removeLegacy(this@YybApp)
            }
            shutdown()
        }
    }
}

/** 手寫的依賴圖：每個元件都是 lazy 單例，第一次用到才建立。 */
class AppGraph(app: Application) {
    val settings by lazy { Settings(app) }
    val ignores by lazy { IgnoreStore(app) }
    val cache by lazy { UpdateCache(app) }
    val selfUpdate by lazy { SelfUpdate(app) }
    val updates by lazy { UpdateRepository(app, cache, ignores, settings, selfUpdate) }
    val store by lazy { StoreRepository(app, selfUpdate) }
    val installer by lazy { InstallManager(app, settings) }
    val downloads by lazy { DownloadManager(app, installer, cache, updates, settings) }
}

val Context.graph: AppGraph get() = (applicationContext as YybApp).graph
