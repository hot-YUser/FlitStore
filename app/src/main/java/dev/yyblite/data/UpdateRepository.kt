package dev.yyblite.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.yyblite.core.apk.Abi
import dev.yyblite.core.apk.ApkBuildProbe
import dev.yyblite.core.store.LookupResult
import dev.yyblite.core.store.StoreApp
import dev.yyblite.core.store.StoreClient
import dev.yyblite.core.update.InstalledApp
import dev.yyblite.core.update.UpdatePolicy
import dev.yyblite.core.update.Verdict
import dev.yyblite.core.update.VersionOrder
import dev.yyblite.platform.Device
import dev.yyblite.platform.InstalledApps
import dev.yyblite.platform.Signatures
import dev.yyblite.ui.image.ThumbCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class EntryStatus { PENDING, CACHED, LIVE, FAILED, NOT_IN_STORE }

/** 一個已安裝應用的檢查狀態。[verdict] 只在有商店資料時存在。 */
data class UpdateEntry(
    val installed: InstalledApp,
    val store: StoreApp? = null,
    val verdict: Verdict? = null,
    val status: EntryStatus = EntryStatus.PENDING,
    val error: Throwable? = null
)

data class UpdatesState(
    val loaded: Boolean = false,
    val scanning: Boolean = false,
    val checking: Boolean = false,
    val progress: Int = 0,
    val total: Int = 0,
    val entries: Map<String, UpdateEntry> = emptyMap(),
    val checkedAt: Long = 0
) {
    val offered: List<UpdateEntry> by lazy { sorted { it.verdict?.offered == true } }
    val blocked: List<UpdateEntry> by lazy { sorted { it.verdict == Verdict.CertMismatch } }
    val failed: List<UpdateEntry> by lazy { sorted { it.status == EntryStatus.FAILED } }
    val all: List<UpdateEntry> by lazy { sorted { true } }

    private inline fun sorted(predicate: (UpdateEntry) -> Boolean): List<UpdateEntry> =
        entries.values.filter(predicate).sortedBy { it.installed.label.lowercase() }
}

/** 任一商店應用相對於本機的狀態（探索、搜尋、詳情頁的按鈕據此決定）。 */
sealed interface AppStatus {
    data object NotInstalled : AppStatus
    data class Installed(val app: InstalledApp, val verdict: Verdict) : AppStatus
}

/**
 * 更新狀態的唯一來源：前景刷新、套件事件、背景 Worker 都改這一份，
 * 每一筆結論都經過 [UpdatePolicy]，所以各條路徑的判定不會再分歧。
 */
class UpdateRepository(
    private val ctx: Context,
    private val cache: UpdateCache,
    private val ignores: IgnoreStore,
    private val settings: Settings,
    private val self: SelfUpdate
) {
    private val _state = MutableStateFlow(UpdatesState())
    val state: StateFlow<UpdatesState> = _state

    private val entries = ConcurrentHashMap<String, UpdateEntry>()
    /** 套件 → (versionCode, 簽章 MD5 串)；版本變了才重讀，避免重複 PackageManager IPC。 */
    private val certs = ConcurrentHashMap<String, Pair<Long, List<String>>>()

    private val io = daemonPool(1, "updates")
    private val events = daemonPool(2, "update-events")
    private val probes = daemonPool(4, "build-probe")
    private val publisher: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "updates-publish").apply { isDaemon = true } }
    private val publishScheduled = AtomicBoolean(false)
    @Volatile private var lastPublish = 0L

    @Volatile private var loaded = false
    @Volatile private var scanning = false
    @Volatile private var checking = false
    @Volatile private var total = 0
    private val progress = AtomicInteger(0)
    private var runLatch: CountDownLatch? = null

    /** 全量檢查；已在跑就不重複開始。 */
    fun refresh() {
        start(Device.debugInt(ctx, "yyb_threads", FOREGROUND_THREADS).coerceIn(1, 512))
    }

    /** 開啟畫面時呼叫：剛檢查過（例如旋轉螢幕重建）就不重複查。 */
    fun refreshIfStale(maxAgeMs: Long = 60_000) {
        // 先判斷 loaded：冷啟動時不必在主執行緒讀快取檔。
        if (!loaded || System.currentTimeMillis() - cache.checkedAt > maxAgeMs) refresh()
    }

    /** 背景 Worker 用：等目前（或新開的一輪）檢查結束，回傳可提供的更新；自己排最後，免得更新自己時中斷其他應用。 */
    fun checkBlocking(): List<StoreApp> {
        start(BACKGROUND_THREADS).await(15, TimeUnit.MINUTES)
        return entries.values
            .filter { it.verdict?.offered == true }
            .mapNotNull { it.store }
            .sortedWith(compareBy({ it.pkg == self.pkg }, { it.name.lowercase() }))
    }

    fun statusOf(store: StoreApp, snapshot: UpdatesState = _state.value): AppStatus {
        val entry = snapshot.entries[store.pkg] ?: return AppStatus.NotInstalled
        val verdict = entry.verdict?.takeIf { entry.store != null && (entry.store.detailed || !store.detailed) }
            ?: verdictFor(entry.installed, store, allowIpc = false)
        return AppStatus.Installed(entry.installed, verdict)
    }

    /** 詳情頁在背景呼叫：允許讀簽章，結果更精確。 */
    fun statusOfBlocking(store: StoreApp): AppStatus {
        val installed = entries[store.pkg]?.installed ?: InstalledApps.read(ctx, store.pkg)
            ?: return AppStatus.NotInstalled
        return AppStatus.Installed(installed, verdictFor(installed, store, allowIpc = true))
    }

    fun onPackageChanged(pkg: String) = events.execute {
        val installed = InstalledApps.read(ctx, pkg)
        certs.remove(pkg)
        if (installed == null) {
            entries.remove(pkg)
            publish()
            return@execute
        }
        val cached = cache.get(pkg)
        entries[pkg] = cached?.let { evaluate(installed, it, EntryStatus.CACHED) } ?: UpdateEntry(installed)
        publish()
        apply(pkg, lookup(pkg))
        publish()
    }

    fun onPackageRemoved(pkg: String) {
        entries.remove(pkg)
        certs.remove(pkg)
        publish()
    }

    fun recheck(pkg: String) = events.execute {
        if (entries[pkg] == null) return@execute
        apply(pkg, lookup(pkg))
        publish()
    }

    /** 忽略規則或版本回退標記改變後，重新判定這一筆。 */
    fun reevaluate(pkg: String) = events.execute {
        val entry = entries[pkg] ?: return@execute
        val store = entry.store ?: return@execute
        entries[pkg] = evaluate(entry.installed, store, entry.status).copy(error = entry.error)
        publish()
    }

    fun ignoreOnce(store: StoreApp) {
        ignores.ignoreOnce(store.pkg, store.versionName, store.apkMd5)
        reevaluate(store.pkg)
    }

    fun ignoreForever(pkg: String) {
        ignores.ignoreForever(pkg)
        reevaluate(pkg)
    }

    fun clearIgnore(pkg: String) {
        ignores.clear(pkg)
        reevaluate(pkg)
    }

    private fun start(threads: Int): CountDownLatch = synchronized(this) {
        runLatch?.takeIf { it.count > 0 }?.let { return it }
        val latch = CountDownLatch(1)
        runLatch = latch
        io.execute {
            try {
                runCheck(threads)
            } catch (t: Throwable) {
                ErrorLog.record("check", "refresh crashed: $t")
            } finally {
                scanning = false
                checking = false
                publish(immediate = true)
                latch.countDown()
            }
        }
        latch
    }

    private fun runCheck(threads: Int) {
        scanning = true
        publish(immediate = true)
        val scanStart = SystemClock.elapsedRealtime()
        val installed = InstalledApps.scan(ctx)
        ThumbCache.preloadInstalled(ctx, installed.map { it.pkg })
        Log.i(TAG, "scan done ${installed.size} in ${SystemClock.elapsedRealtime() - scanStart}ms")

        // 先用快取立刻給出結論，即時查詢的結果再逐筆覆蓋。
        val pkgs = installed.mapTo(HashSet()) { it.pkg }
        entries.keys.retainAll(pkgs)
        for (app in installed) {
            val cached = cache.get(app.pkg)
            entries[app.pkg] = when {
                cached != null -> evaluate(app, cached, EntryStatus.CACHED)
                notFoundFresh(app.pkg) -> UpdateEntry(app, status = EntryStatus.NOT_IN_STORE)
                else -> UpdateEntry(app)
            }
        }
        val toCheck = installed.filterNot { notFoundFresh(it.pkg) }
        loaded = true
        scanning = false
        checking = true
        total = toCheck.size
        progress.set(0)
        publish(immediate = true)

        val checkStart = SystemClock.elapsedRealtime()
        lookupAll(toCheck, threads) { app, result ->
            apply(app.pkg, result)
            progress.incrementAndGet()
            publish()
        }
        cache.checkedAt = System.currentTimeMillis()
        cache.flush()
        checking = false
        publish(immediate = true)
        Log.i(
            TAG,
            "check done: ${toCheck.size}/${installed.size} apps in ${SystemClock.elapsedRealtime() - checkStart}ms, " +
                "${entries.values.count { it.verdict?.offered == true }} offered, " +
                "${entries.values.count { it.status == EntryStatus.FAILED }} failed"
        )
        probeBuilds()
    }

    /** 固定大小的執行緒池同時查詢；失敗的隔 400ms 重試一次。 */
    private fun lookupAll(apps: List<InstalledApp>, threads: Int, onResult: (InstalledApp, LookupResult) -> Unit) {
        if (apps.isEmpty()) return
        val pool = daemonPool(threads, "update-check")
        try {
            val latch = CountDownLatch(apps.size)
            for (app in apps) {
                pool.execute {
                    try {
                        var result = lookup(app.pkg)
                        if (result is LookupResult.Failed) {
                            Thread.sleep(400)
                            result = lookup(app.pkg)
                        }
                        runCatching { onResult(app, result) }
                    } finally {
                        latch.countDown()
                    }
                }
            }
            latch.await(15, TimeUnit.MINUTES)
        } finally {
            pool.shutdownNow()
        }
    }

    /** 自己從 GitHub Releases 查，其他應用查應用寶。 */
    private fun lookup(pkg: String): LookupResult =
        if (pkg == self.pkg) self.lookup() else StoreClient.lookup(pkg)

    private fun notFoundFresh(pkg: String): Boolean = pkg != self.pkg && cache.isNotFoundFresh(pkg)

    private fun apply(pkg: String, result: LookupResult) {
        val installed = entries[pkg]?.installed ?: return
        entries[pkg] = when (result) {
            is LookupResult.Found -> {
                cache.put(result.app)
                cache.clearNotFound(pkg)
                evaluate(installed, result.app, EntryStatus.LIVE)
            }
            LookupResult.NotInStore -> {
                cache.markNotFound(pkg)
                cache.remove(pkg)
                UpdateEntry(installed, status = EntryStatus.NOT_IN_STORE)
            }
            is LookupResult.Failed -> {
                ErrorLog.record("check", "$pkg: ${result.reason}")
                cache.get(pkg)?.let { evaluate(installed, it, EntryStatus.CACHED).copy(error = result.error) }
                    ?: UpdateEntry(installed, status = EntryStatus.FAILED, error = result.error)
            }
        }
    }

    /**
     * 同 versionName 的商店重建（例如 B 站 9160100 → 9160300）無法從公開頁得知，
     * 以 Range 讀取 APK 的 AndroidManifest 取得 versionCode；結果綁定 APK MD5 快取。
     */
    private fun probeBuilds() {
        for (entry in entries.values) {
            val store = entry.store ?: continue
            if (entry.verdict != Verdict.UpToDate || store.pkg == self.pkg) continue
            if (VersionOrder.compare(store.versionName, entry.installed.versionName) != 0) continue
            val url = Device.urlFor(store)
            val md5 = Abi.md5For(store, url)
            if (cache.buildProbe(store.pkg, md5) != null) continue
            probes.execute {
                val code = runCatching { ApkBuildProbe.versionCode(url) }
                    .onFailure { Log.w(TAG, "probe failed ${store.pkg}: ${it.message}") }
                    .getOrNull() ?: return@execute
                cache.putBuildProbe(store.pkg, md5, code)
                reevaluate(store.pkg)
            }
        }
    }

    private fun evaluate(installed: InstalledApp, store: StoreApp, status: EntryStatus) =
        UpdateEntry(installed, store, verdictFor(installed, store, allowIpc = true), status)

    private fun verdictFor(installed: InstalledApp, store: StoreApp, allowIpc: Boolean): Verdict {
        val url = Device.urlFor(store)
        return UpdatePolicy.evaluate(
            store = store,
            installed = installed,
            certOk = certOk(installed, store.signatureMd5, allowIpc),
            regression = cache.isRegression(store.pkg, store.versionName),
            ignored = ignores.isIgnored(store.pkg, store.versionName, store.apkMd5),
            probedBuild = cache.buildProbe(store.pkg, Abi.md5For(store, url))
        )
    }

    private fun certOk(installed: InstalledApp, expected: String?, allowIpc: Boolean): Boolean {
        if (expected.isNullOrBlank()) return true
        val known = certs[installed.pkg]?.takeIf { it.first == installed.versionCode }?.second
            ?: if (allowIpc) {
                Signatures.installed(ctx, installed.pkg).also { certs[installed.pkg] = installed.versionCode to it }
            } else {
                return true
            }
        val ok = Signatures.accepts(known, expected)
        if (!ok) Log.w(TAG, "cert not in history ${installed.pkg} expected=$expected known=$known")
        return ok
    }

    /** 進度回報合併成每 150ms 一次，128 條執行緒同時回來也只重組幾次畫面。 */
    private fun publish(immediate: Boolean = false) {
        if (immediate) {
            publishNow()
            return
        }
        if (publishScheduled.compareAndSet(false, true)) {
            val delay = (PUBLISH_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastPublish)).coerceAtLeast(0)
            publisher.schedule({
                publishScheduled.set(false)
                publishNow()
            }, delay, TimeUnit.MILLISECONDS)
        }
    }

    private fun publishNow() {
        lastPublish = SystemClock.elapsedRealtime()
        _state.value = UpdatesState(
            loaded = loaded,
            scanning = scanning,
            checking = checking,
            progress = progress.get(),
            total = total,
            entries = HashMap(entries),
            checkedAt = cache.checkedAt
        )
    }

    private companion object {
        const val TAG = "YYBLite"
        const val FOREGROUND_THREADS = 128
        const val BACKGROUND_THREADS = 32
        const val PUBLISH_INTERVAL_MS = 150L

        fun daemonPool(threads: Int, name: String) = Executors.newFixedThreadPool(threads) { r ->
            Thread(r, name).apply { isDaemon = true }
        }
    }
}
