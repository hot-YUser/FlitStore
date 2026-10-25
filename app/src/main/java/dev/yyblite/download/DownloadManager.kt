package dev.yyblite.download

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import dev.yyblite.R
import dev.yyblite.core.apk.Abi
import dev.yyblite.core.download.CancelToken
import dev.yyblite.core.download.Cdn
import dev.yyblite.core.download.DownloadCancelledException
import dev.yyblite.core.download.DownloadReport
import dev.yyblite.core.download.SegmentDownloader
import dev.yyblite.core.download.TaskGate
import dev.yyblite.core.store.LookupResult
import dev.yyblite.core.store.StoreApp
import dev.yyblite.core.store.StoreClient
import dev.yyblite.data.ErrorLog
import dev.yyblite.data.InstallerKind
import dev.yyblite.data.Settings
import dev.yyblite.data.UpdateCache
import dev.yyblite.data.UpdateRepository
import dev.yyblite.install.InstallManager
import dev.yyblite.install.InstallOutcome
import dev.yyblite.install.RootInstaller
import dev.yyblite.install.ShellExec
import dev.yyblite.platform.Device
import dev.yyblite.platform.Notifications
import dev.yyblite.platform.Problems
import dev.yyblite.platform.Signatures
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SignatureMismatchException(val expected: String, val actual: List<String>) :
    IOException("signature mismatch: store $expected / file $actual")

class SignatureUnreadableException : IOException("cannot read APK signature")

class StorageUnavailableException : IOException("external storage unavailable")

class NoDownloadException : IOException("store has no download for this app")

class InstallFailedException(val via: InstallerKind, message: String) : IOException(message)

enum class DownloadPhase { QUEUED, DOWNLOADING, VERIFYING, INSTALLING, READY, FAILED }

data class DownloadState(
    val pkg: String,
    val name: String,
    val version: String,
    val iconUrl: String?,
    val phase: DownloadPhase,
    val bytes: Long = 0,
    val total: Long = -1,
    val speedBps: Long = 0,
    val sizeHint: Long = -1,
    val error: Throwable? = null,
    val file: File? = null
) {
    val fraction: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
    val active: Boolean get() = phase <= DownloadPhase.INSTALLING
}

/** 前景服務的啟動參數；Root 代為啟動時也走同一組欄位。 */
data class DownloadRequest(
    val pkg: String,
    val name: String,
    val version: String,
    val url: String,
    val md5: String,
    val abi: String,
    val signature: String?,
    val size: Long,
    val icon: String?
) {
    fun toIntent(ctx: Context): Intent = Intent(ctx, DownloadService::class.java)
        .putExtra("pkg", pkg)
        .putExtra("name", name)
        .putExtra("version", version)
        .putExtra("url", url)
        .putExtra("md5", md5)
        .putExtra("abi", abi)
        .putExtra("signature", signature)
        .putExtra("size", size)
        .putExtra("icon", icon)

    /** `am start-foreground-service` 的參數，值一律以單引號跳脫。 */
    fun shellCommand(ctx: Context): String = buildString {
        fun q(value: String) = "'" + value.replace("'", "'\\''") + "'"
        append("am start-foreground-service -n ").append(ctx.packageName).append("/.download.DownloadService")
        append(" --es pkg ").append(q(pkg))
        append(" --es name ").append(q(name))
        append(" --es version ").append(q(version))
        append(" --es url ").append(q(url))
        append(" --es md5 ").append(q(md5))
        append(" --es abi ").append(q(abi))
        signature?.let { append(" --es signature ").append(q(it)) }
        icon?.let { append(" --es icon ").append(q(it)) }
        append(" --el size ").append(size)
    }

    companion object {
        fun from(intent: Intent): DownloadRequest? {
            val pkg = intent.getStringExtra("pkg") ?: return null
            val url = intent.getStringExtra("url") ?: return null
            return DownloadRequest(
                pkg = pkg,
                name = intent.getStringExtra("name") ?: pkg,
                version = intent.getStringExtra("version") ?: "0",
                url = url,
                md5 = intent.getStringExtra("md5").orEmpty(),
                abi = intent.getStringExtra("abi") ?: "v7a",
                signature = intent.getStringExtra("signature"),
                size = intent.getLongExtra("size", -1L),
                icon = intent.getStringExtra("icon")
            )
        }
    }
}

/**
 * 下載與安裝佇列：同時最多 2 個檔案在下載（每檔內部再分 32–96 段），
 * 安裝固定 2 條執行緒（實測 8 個 APK：1 條 33.5s、2 條 28.3s、4 條 29.1s，commit/dexopt 會互搶 CPU）。
 */
class DownloadManager(
    private val ctx: Context,
    private val installer: InstallManager,
    private val cache: UpdateCache,
    private val updates: UpdateRepository,
    private val settings: Settings
) {
    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states

    private val tokens = ConcurrentHashMap<String, CancelToken>()
    private val parts = ConcurrentHashMap.newKeySet<File>()
    private val gate = TaskGate(2, "dl-queue")
    private val resolver = Executors.newFixedThreadPool(2) { r -> Thread(r, "dl-resolve").apply { isDaemon = true } }
    private val installs = Executors.newFixedThreadPool(2) { r -> Thread(r, "dl-install").apply { isDaemon = true } }
    private val ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "dl-notify").apply { isDaemon = true } }
    private val tickScheduled = AtomicBoolean(false)
    private val active = AtomicInteger(0)
    private val downloader by lazy {
        SegmentDownloader(Device.debugInt(ctx, "yyb_segments", 0).coerceIn(0, 512))
    }

    /** 自己的更新檔放在 GitHub、只有約 2MB：分 4 段就夠，每段都要多跳一次轉址。 */
    private val selfDownloader by lazy { SegmentDownloader(4) }

    fun enqueue(app: StoreApp) {
        if (_states.value[app.pkg]?.active == true) return
        queued(app)
        resolver.execute { start(app, fromBackground = false) }
    }

    /** 背景自動更新用：同步完成解析並交給前景服務，回傳是否成功。 */
    fun enqueueBlocking(app: StoreApp): Boolean {
        if (_states.value[app.pkg]?.active == true) return true
        queued(app)
        return start(app, fromBackground = true)
    }

    /** 令牌保留在表內（已取消），之後才抵達的前景服務啟動請求會看到並放棄。 */
    fun cancel(pkg: String) {
        tokens[pkg]?.cancel()
        val state = _states.value[pkg] ?: return
        if (state.phase <= DownloadPhase.DOWNLOADING) remove(pkg)
    }

    /** 移除已完成或失敗的項目；[deleteFile] 時一併刪掉下載檔。 */
    fun dismiss(pkg: String, deleteFile: Boolean) {
        val state = _states.value[pkg]
        if (state?.active == true) return
        remove(pkg)
        Notifications.cancelFor(ctx, pkg)
        if (deleteFile) ApkFiles.deleteFor(ctx, pkg, parts)
    }

    fun readyFile(pkg: String): File? =
        _states.value[pkg]?.file?.takeIf { it.exists() }
            ?: ApkFiles.dir(ctx, pkg)?.listFiles()?.filter { it.name.endsWith(".apk") }?.maxByOrNull { it.lastModified() }

    /** 介面上按「安裝」：降級確認由呼叫端先處理。 */
    fun installManually(pkg: String, file: File, done: (InstallOutcome) -> Unit) {
        val previous = _states.value[pkg]
        put(
            (previous ?: DownloadState(pkg, pkg, "", null, DownloadPhase.INSTALLING))
                .copy(phase = DownloadPhase.INSTALLING, file = file, error = null)
        )
        installs.execute {
            val outcome = installer.install(file)
            when (outcome) {
                is InstallOutcome.Installed -> remove(pkg)
                is InstallOutcome.Failed -> {
                    ErrorLog.record("install", "$pkg: ${outcome.message}")
                    put(stateOf(pkg)?.copy(phase = DownloadPhase.FAILED, error = InstallFailedException(outcome.via, outcome.message)))
                }
                InstallOutcome.Handoff, InstallOutcome.Busy -> put(stateOf(pkg)?.copy(phase = DownloadPhase.READY))
            }
            done(outcome)
        }
    }

    /** 套件裝好了（不論從哪裡裝的）：清掉完成狀態、通知與下載檔。 */
    fun onPackageInstalled(pkg: String) {
        Notifications.cancelFor(ctx, pkg)
        if (_states.value[pkg]?.active == true) return
        remove(pkg)
        ApkFiles.deleteFor(ctx, pkg, parts)
    }

    internal fun run(request: DownloadRequest) {
        val token = tokens.getOrPut(request.pkg) { CancelToken() }
        if (token.isCancelled) {
            tokens.remove(request.pkg, token)
            remove(request.pkg)
            stopIfIdle()
            return
        }
        if (_states.value[request.pkg] == null) {
            put(DownloadState(request.pkg, request.name, request.version, request.icon, DownloadPhase.QUEUED, sizeHint = request.size))
        }
        active.incrementAndGet()
        gate.submit {
            val file = try {
                download(request, token)
            } catch (t: Throwable) {
                null
            }
            if (file == null) {
                finish()
            } else {
                installs.execute {
                    try {
                        installDownloaded(request, file)
                    } finally {
                        finish()
                    }
                }
            }
        }
    }

    fun hasActive(): Boolean = active.get() > 0

    private fun queued(app: StoreApp) {
        tokens[app.pkg] = CancelToken()
        installer.resetAuto(app.pkg)
        Notifications.cancelFor(ctx, app.pkg)
        put(DownloadState(app.pkg, app.name, app.versionName, app.iconUrl, DownloadPhase.QUEUED, sizeHint = app.sizeBytes))
    }

    private fun start(app: StoreApp, fromBackground: Boolean): Boolean {
        val token = tokens[app.pkg] ?: return false
        val full = try {
            resolve(app)
        } catch (e: Exception) {
            fail(app.pkg, e)
            return false
        }
        if (token.isCancelled) {
            remove(app.pkg)
            return false
        }
        val url = Device.urlFor(full)
        val request = DownloadRequest(
            pkg = full.pkg,
            name = full.name,
            version = full.versionName,
            url = url,
            md5 = Abi.md5For(full, url),
            abi = Device.abiTag(full),
            signature = full.signatureMd5,
            size = full.sizeBytes,
            icon = full.iconUrl
        )
        put(stateOf(full.pkg)?.copy(name = full.name, version = full.versionName, iconUrl = full.iconUrl, sizeHint = full.sizeBytes))
        return try {
            ContextCompat.startForegroundService(ctx, request.toIntent(ctx))
            true
        } catch (e: Exception) {
            // Android 12+ 背景禁止啟動前景服務；有 Root 時改由 su 代為啟動。
            if (fromBackground && RootInstaller.isGranted() && startViaRoot(request)) {
                true
            } else {
                fail(app.pkg, e)
                false
            }
        }
    }

    /** 清單來的資料沒有簽章與 64 位連結，下載前補查一次 simple.jsp。 */
    private fun resolve(app: StoreApp): StoreApp {
        if (app.detailed) return app
        return when (val result = StoreClient.lookup(app.pkg)) {
            is LookupResult.Found -> result.app
            is LookupResult.Failed -> if (app.apkUrl.isNotBlank()) app else throw result.error
            LookupResult.NotInStore -> if (app.apkUrl.isNotBlank()) app else throw NoDownloadException()
        }
    }

    private fun startViaRoot(request: DownloadRequest): Boolean {
        val result = runCatching { ShellExec.exec(listOf("su", "-c", request.shellCommand(ctx)), 15_000) }.getOrNull()
        if (result?.ok != true) Log.w(TAG, "root fgs start failed: ${result?.err?.ifBlank { result.out }}")
        return result?.ok == true
    }

    private fun download(request: DownloadRequest, token: CancelToken): File? {
        val startedAt = SystemClock.elapsedRealtime()
        val target = ApkFiles.file(ctx, request.pkg, request.version, request.abi)
        val part = target?.let { File(it.path + ".part") }
        val report = DownloadReport()
        try {
            if (target == null || part == null) throw StorageUnavailableException()
            target.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw StorageUnavailableException() }
            parts += part
            put(stateOf(request.pkg)?.copy(phase = DownloadPhase.DOWNLOADING))
            Log.i(TAG, "start ${request.pkg} ${request.version} ${request.url}")

            var lastBytes = 0L
            var lastTime = SystemClock.elapsedRealtime()
            var speed = 0.0
            val url = Cdn.forceHost(request.url, Device.debugString(ctx, "yyb_host"))
            val engine = if (request.pkg == ctx.packageName) selfDownloader else downloader
            engine.downloadFull(url, request.md5.ifBlank { null }, part, token, report) { done, size ->
                val now = SystemClock.elapsedRealtime()
                val dt = now - lastTime
                if (dt >= 300) {
                    val instant = (done - lastBytes) * 1000.0 / dt
                    speed = if (speed == 0.0) instant else speed * 0.6 + instant * 0.4
                    lastBytes = done
                    lastTime = now
                }
                put(stateOf(request.pkg)?.copy(bytes = done, total = size, speedBps = speed.toLong()))
            }

            if (target.exists() && !target.delete()) throw IOException("cannot replace old file")
            if (!part.renameTo(target)) throw IOException("cannot rename download")
            val elapsed = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1)
            val bytes = target.length()
            Log.i(TAG, "download done ${request.pkg} $bytes bytes in ${elapsed}ms $report")
            val rate = bytes * 1000 / elapsed
            if (report.degraded || (bytes >= SLOW_MIN_BYTES && rate < SLOW_BPS)) {
                val summary = String.format(Locale.US, "%.1fMB in %.0fs = %dKB/s", bytes / 1048576.0, elapsed / 1000.0, rate / 1024)
                ErrorLog.record("download", "${request.pkg} slow: $summary | $report")
            }

            put(stateOf(request.pkg)?.copy(phase = DownloadPhase.VERIFYING, file = target))
            if (!request.signature.isNullOrBlank()) {
                val accepted = Signatures.archive(ctx, target)
                if (accepted.isEmpty()) {
                    target.delete()
                    throw SignatureUnreadableException()
                }
                if (!Signatures.accepts(accepted, request.signature)) {
                    target.delete()
                    throw SignatureMismatchException(request.signature, accepted)
                }
            }
            return target
        } catch (e: DownloadCancelledException) {
            part?.delete()
            part?.parentFile?.let { if (it.listFiles().isNullOrEmpty()) it.delete() }
            remove(request.pkg)
            return null
        } catch (e: Exception) {
            part?.delete()
            if (token.isCancelled) {
                remove(request.pkg)
                return null
            }
            Log.w(TAG, "fail ${request.pkg}: ${e.message}", e)
            ErrorLog.record("download", "${request.pkg}: ${e.message} | $report")
            fail(request.pkg, e)
            return null
        } finally {
            part?.let { parts -= it }
            tokens.remove(request.pkg, token)
        }
    }

    private fun installDownloaded(request: DownloadRequest, file: File) {
        if (request.pkg == ctx.packageName) awaitOthers(request.pkg)
        put(stateOf(request.pkg)?.copy(phase = DownloadPhase.INSTALLING, file = file))
        val downgrade = installer.downgradeInfo(file)
        if (downgrade != null) {
            // 商店頁以 versionName 判斷「較新」，但 APK 的 versionCode 更舊：刪檔並標記，之後不再提供這個版本。
            file.delete()
            cache.markRegression(request.pkg, request.version)
            updates.reevaluate(request.pkg)
            remove(request.pkg)
            ErrorLog.record("regression", "${request.pkg}: apk ${downgrade.targetCode} < installed ${downgrade.installedCode}")
            Notifications.failed(
                ctx, request.pkg, request.name,
                ctx.getString(R.string.error_store_regression, downgrade.targetCode, downgrade.installedCode)
            )
            return
        }
        when (val outcome = installer.install(file)) {
            is InstallOutcome.Installed -> {
                Log.i(TAG, "installed ${request.pkg} via ${outcome.via}")
                remove(request.pkg)
            }
            InstallOutcome.Handoff, InstallOutcome.Busy -> {
                put(stateOf(request.pkg)?.copy(phase = DownloadPhase.READY, file = file))
                if (!installer.tryAutoHandoff(request.pkg, request.version, file)) {
                    Notifications.readyToInstall(ctx, request.pkg, request.name, request.version, file)
                }
            }
            is InstallOutcome.Failed -> {
                Log.w(TAG, "install failed via ${outcome.via}: ${outcome.message}")
                ErrorLog.record("install", "${request.pkg}: ${outcome.message}")
                val error = InstallFailedException(outcome.via, outcome.message)
                put(stateOf(request.pkg)?.copy(phase = DownloadPhase.FAILED, error = error, file = file))
                Notifications.failed(ctx, request.pkg, request.name, Problems.message(ctx, error))
            }
        }
    }

    /** 裝上自己的新版時系統會結束這個行程：等其他下載與安裝都結束（最多 30 分鐘）再裝，免得一起中斷。 */
    private fun awaitOthers(self: String) {
        val deadline = SystemClock.elapsedRealtime() + 30 * 60_000L
        while (SystemClock.elapsedRealtime() < deadline && _states.value.values.any { it.pkg != self && it.active }) {
            Thread.sleep(1000)
        }
    }

    private fun fail(pkg: String, error: Throwable) {
        val state = stateOf(pkg) ?: return
        put(state.copy(phase = DownloadPhase.FAILED, error = error, speedBps = 0))
        Notifications.failed(ctx, pkg, state.name, Problems.message(ctx, error))
    }

    private fun finish() {
        active.decrementAndGet()
        stopIfIdle()
    }

    private fun stopIfIdle() {
        if (active.get() <= 0) DownloadService.stopWhenIdle()
    }

    private fun stateOf(pkg: String): DownloadState? = _states.value[pkg]

    private fun put(state: DownloadState?) {
        if (state == null) return
        _states.update { it + (state.pkg to state) }
        scheduleNotification()
    }

    private fun remove(pkg: String) {
        _states.update { it - pkg }
        scheduleNotification()
    }

    /** 前景通知最多每秒更新一次。 */
    private fun scheduleNotification() {
        if (tickScheduled.compareAndSet(false, true)) {
            ticker.schedule({
                tickScheduled.set(false)
                DownloadService.refresh(_states.value)
            }, 1, TimeUnit.SECONDS)
        }
    }

    private companion object {
        const val TAG = "YYBDL"

        /** 平均低於 1 MB/s（8 MB 以上的檔案）就寫進診斷記錄，方便使用者回報。 */
        const val SLOW_BPS = 1L shl 20
        const val SLOW_MIN_BYTES = 8L shl 20
    }
}
