package dev.yyblite

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import dev.yyblite.data.Abi
import dev.yyblite.data.ApkBuildProbe
import dev.yyblite.data.ErrorLog
import dev.yyblite.data.IgnoreStore
import dev.yyblite.data.InstalledApp
import dev.yyblite.data.InstalledScanner
import dev.yyblite.data.LookupResult
import dev.yyblite.data.StoreApp
import dev.yyblite.data.StoreClient
import dev.yyblite.data.UpdateCache
import dev.yyblite.data.UpdateChecker
import dev.yyblite.data.UpdateDecision
import dev.yyblite.data.VersionOrder
import dev.yyblite.databinding.ActivityMainBinding
import dev.yyblite.download.DownloadBus
import dev.yyblite.download.DownloadStarter
import dev.yyblite.download.Phase
import dev.yyblite.install.AutoInstall
import dev.yyblite.install.InstallHandoff
import dev.yyblite.install.InstallOutcome
import dev.yyblite.install.InstallerEngine
import dev.yyblite.install.InstallerKind
import dev.yyblite.install.InstallerMode
import dev.yyblite.work.AutoUpdatePrefs
import dev.yyblite.install.InstallerPrefs
import dev.yyblite.install.InstallerStatus
import dev.yyblite.install.ShizukuInstaller
import rikka.shizuku.Shizuku
import dev.yyblite.ui.AppAdapter
import dev.yyblite.ui.ThumbCache
import dev.yyblite.ui.AppVisibility
import dev.yyblite.ui.Format
import dev.yyblite.ui.RowItem
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: AppAdapter

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ui-io").apply { isDaemon = true }
    }
    private lateinit var checker: UpdateChecker
    private val scanLatch = CountDownLatch(1)

    private lateinit var cache: UpdateCache
    private lateinit var ignoreStore: IgnoreStore

    @Volatile private var scanned: List<InstalledApp> = emptyList()
    @Volatile private var busy = false
    @Volatile private var lastProgressAt = 0L
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val probeExecutor = java.util.concurrent.Executors.newFixedThreadPool(4) { r ->
        Thread(r, "build-probe").apply { isDaemon = true }
    }
    @Volatile private var flushScheduled = false
    @Volatile private var lastFlushAt = 0L
    @Volatile private var loggedLive = false

    private var updateRows: List<RowItem> = emptyList()
    private var searchRows: List<RowItem> = emptyList()
    private var searchQuery: String = ""
    private var subscription: AutoCloseable? = null
    private val pendingDownloads = ArrayList<StoreApp>()
    @Volatile private var installerStatus: InstallerStatus? = null

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == ShizukuInstaller.REQUEST_CODE) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (grantResult == PackageManager.PERMISSION_GRANTED) {
                            R.string.installer_shizuku_ok
                        } else {
                            R.string.installer_shizuku_rejected
                        },
                        Toast.LENGTH_SHORT
                    ).show()
                    installerStatus = null
                }
            }
        }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart ?: return
            if (pkg == packageName) return
            if (intent.action != Intent.ACTION_PACKAGE_REMOVED) {
                dev.yyblite.install.ApkStore.deleteFor(this@MainActivity, pkg)
            }
            refreshPackage(pkg)
        }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            val pending = ArrayList(pendingDownloads)
            pendingDownloads.clear()
            pending.forEach { startDownloadNow(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        applyWindowInsets()

        checker = UpdateChecker(resolveThreads())
        cache = UpdateCache(this)
        ignoreStore = IgnoreStore(this)

        adapter = AppAdapter(::onRowAction, ::openDetail, ::onRowLongClick, ::onSelectionChanged)
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.swipe.setOnRefreshListener { refresh(force = true) }
        binding.swipe.setColorSchemeResources(R.color.primary)
        binding.updateAll.setOnClickListener { updateSelectedOrAll() }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (ShizukuInstaller.binderAlive()) {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        }
        io.execute {
            installerStatus = InstallerEngine.status(this)
            android.util.Log.i(
                "YYBLite",
                "installer: root=${installerStatus?.rootBinary} shizuku=${installerStatus?.shizukuAlive}"
            )
        }

        refresh()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        val search = menu.findItem(R.id.action_search).actionView as SearchView
        search.queryHint = getString(R.string.search_hint)
        search.setSubmitButtonEnabled(true)
        search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                query?.trim()?.takeIf { it.isNotEmpty() }?.let(::runSearch)
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                if (newText.isNullOrBlank() && searchQuery.isNotEmpty()) {
                    searchQuery = ""
                    showRows(updateRows)
                    setStatus(updateStatus())
                }
                return true
            }
        })
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_diagnostics -> {
            showDiagnostics()
            true
        }
        R.id.action_auto_update -> {
            showAutoUpdateDialog()
            true
        }
        R.id.action_installer -> {
            showInstallerDialog()
            true
        }
        R.id.action_ignored -> {
            showIgnoredDialog()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun showDiagnostics() {
        val text = ErrorLog.text()
        if (text.isBlank()) {
            Toast.makeText(this, R.string.diagnostics_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val density = resources.displayMetrics.density
        val view = android.widget.TextView(this).apply {
            this.text = text
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding((20 * density).toInt(), (8 * density).toInt(), (20 * density).toInt(), 0)
            setTextIsSelectable(true)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(view) }
        AlertDialog.Builder(this)
            .setTitle(R.string.diagnostics_title)
            .setView(scroll)
            .setPositiveButton(R.string.diagnostics_copy) { _, _ ->
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("diagnostics", text))
                Toast.makeText(this, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.diagnostics_clear) { _, _ -> ErrorLog.clear() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showIgnoredDialog() {
        val entries = ignoreStore.entries()
        if (entries.isEmpty()) {
            Toast.makeText(this, R.string.ignore_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = entries.map {
            if (it.forever) "${it.pkg}（永久）" else "${it.pkg}（${it.version}）"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_ignored)
            .setItems(labels) { _, which ->
                ignoreStore.clear(entries[which].pkg)
                reloadRows()
            }
            .show()
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.onStart()
        subscription = DownloadBus.subscribe { state ->
            runOnUiThread {
                adapter.setState(state)
                updateGlobalStatus()
            }
        }
        adapter.restoreStates()
        updateGlobalStatus()
    }

    override fun onStop() {
        AppVisibility.onStop()
        subscription?.close()
        subscription = null
        super.onStop()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(packageReceiver) }
        runCatching { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) }
        checker.shutdown()
        probeExecutor.shutdownNow()
        io.shutdownNow()
        super.onDestroy()
    }

    /** 除錯版可透過 Settings.Global 的 yyb_threads 調整並發，方便實機壓測。 */
    private fun resolveThreads(): Int {
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return 128
        return runCatching {
            android.provider.Settings.Global.getInt(contentResolver, "yyb_threads", 128)
        }.getOrDefault(128).coerceIn(1, 512)
    }

    private fun applyWindowInsets() {
        val density = resources.displayMetrics.density
        val baseToolbarHeight = (56 * density).toInt()
        val barPaddingTop = binding.bottomBar.paddingTop
        val barPaddingBottom = binding.bottomBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val params = binding.toolbar.layoutParams
            val height = baseToolbarHeight + bars.top
            if (params.height != height) {
                params.height = height
                binding.toolbar.layoutParams = params
            }
            binding.toolbar.setPadding(0, bars.top, 0, 0)
            binding.bottomBar.setPadding(
                binding.bottomBar.paddingLeft,
                barPaddingTop,
                binding.bottomBar.paddingRight,
                barPaddingBottom + bars.bottom
            )
            insets
        }
    }

    private fun refresh(force: Boolean = false) {
        if (busy) return
        busy = true
        loggedLive = false
        searchQuery = ""
        setStatus(getString(R.string.status_scanning))

        io.execute {
            val scanStart = System.currentTimeMillis()
            val installed = InstalledScanner.scan(this)
            ThumbCache.preloadInstalled(this, installed.map { it.pkg })
            android.util.Log.i("YYBLite", "scan done ${installed.size} in ${System.currentTimeMillis() - scanStart}ms")
            scanned = installed
            scanLatch.countDown()

            if (installed.isEmpty()) {
                runOnUiThread {
                    updateRows = emptyList()
                    showRows(updateRows)
                    finishRefresh()
                    setStatus(getString(R.string.status_none))
                }
                return@execute
            }

            val cached = cache.load()
            val cachedRows = buildRows(installed, cached)
            val fingerprint = cache.fingerprint(installed)

            // 立即顯示快取，並用同一份資料作為即時更新的起點。
            val live = java.util.Collections.synchronizedMap(LinkedHashMap<String, RowItem>())
            cachedRows.forEach { live[it.store.pkg] = it }
            runOnUiThread {
                updateRows = cachedRows
                if (searchQuery.isEmpty()) showRows(cachedRows)
                setStatus(updateStatus())
                android.util.Log.i("YYBLite", "cache shown ${cachedRows.size}")
            }

            // 未收錄的套件在 3 天內不再重查。
            val toCheck = installed.filterNot { cache.isNotFoundFresh(it.pkg) }
            val total = toCheck.size
            val done = AtomicInteger(0)
            val successes = Collections.synchronizedList(ArrayList<StoreApp>())
            val removed = Collections.synchronizedList(ArrayList<String>())
            var failedCount = 0

            checker.check(
                toCheck,
                { app, result ->
                    when (result) {
                        is LookupResult.Found -> {
                            cache.clearNotFound(result.app.pkg)
                            successes.add(result.app)
                            val certOk = dev.yyblite.install.ApkSignature.matchesInstalled(
                                this, result.app.signatureMd5, result.app.pkg
                            )
                            if (!certOk) {
                                android.util.Log.w("YYBLite", "cert mismatch: ${result.app.pkg}")
                            }
                            val updatable =
                                UpdateDecision.needsUpdate(result.app, app.versionName, app.versionCode) &&
                                !cache.isRegression(result.app.pkg, result.app.versionName) &&
                                !ignoreStore.isIgnored(
                                    result.app.pkg, result.app.versionName, result.app.apkMd5
                                )
                            synchronized(live) {
                                if (updatable) {
                                    live[result.app.pkg] = RowItem(
                                        result.app, app.versionName, true, certMismatch = !certOk
                                    )
                                } else {
                                    live.remove(result.app.pkg)
                                }
                            }
                        }
                        is LookupResult.Failed -> {
                            android.util.Log.w("YYBLite", "lookup failed ${app.pkg}: ${result.reason}")
                            ErrorLog.record("check", "${app.pkg}: ${result.reason}")
                            val stale = cached[app.pkg]
                            synchronized(live) {
                                if (stale != null &&
                                    UpdateDecision.needsUpdate(stale, app.versionName, app.versionCode) &&
                                    !ignoreStore.isIgnored(stale.pkg, stale.versionName, stale.apkMd5)
                                ) {
                                    live[stale.pkg] = RowItem(stale, app.versionName, true, cached = true)
                                } else {
                                    live[app.pkg] = RowItem(
                                        placeholder(app), app.versionName, false, true,
                                        error = result.reason
                                    )
                                }
                            }
                            failedCount++
                        }
                        LookupResult.NotInStore -> {
                            removed.add(app.pkg)
                            cache.markNotFound(app.pkg)
                            synchronized(live) { live.remove(app.pkg) }
                        }
                    }
                    flushLiveRows(live)

                    val checked = done.incrementAndGet()
                    val now = System.currentTimeMillis()
                    if (checked == total || now - lastProgressAt >= 300) {
                        lastProgressAt = now
                        runOnUiThread {
                            if (searchQuery.isEmpty()) {
                                setStatus(getString(R.string.status_checking, checked, total))
                            }
                        }
                    }
                },
                {
                    val merged = HashMap(cached)
                    successes.forEach { merged[it.pkg] = it }
                    removed.forEach { merged.remove(it) }
                    cache.save(merged.values)
                    cache.markChecked(fingerprint)

                    val finalRows = synchronized(live) { live.values.toList() }
                    val rows = finalRows.sortedBy { it.store.name.lowercase() }
                    android.util.Log.i(
                        "YYBLite",
                        "check done: ${toCheck.size}/${installed.size} apps, ${rows.size} rows, $failedCount failed"
                    )
                    runOnUiThread {
                        updateRows = rows
                        if (searchQuery.isEmpty()) showRows(rows)
                        finishRefresh()
                        setStatus(updateStatus())
                        maybeAskOverlay()
                        probeEqualBuilds(successes, installed.associateBy { it.pkg }, live)
                    }
                }
            )
        }
    }

    /**
     * 同 versionName 的商店重建（例如 B 站 9160100 → 9160300）無法從公開頁得知，
     * 以 Range 讀取 APK 的 AndroidManifest 取得 versionCode；結果快取在 URL MD5 上。
     */
    private fun probeEqualBuilds(
        apps: List<StoreApp>,
        installedByPkg: Map<String, InstalledApp>,
        live: MutableMap<String, RowItem>
    ) {
        var candidates = 0
        for (app in apps) {
            val installed = installedByPkg[app.pkg] ?: continue
            if (VersionOrder.compare(app.versionName, installed.versionName) != 0) continue
            val url = Abi.chooseUrl(app, android.os.Build.SUPPORTED_ABIS.toList())
            val md5 = Abi.md5For(app, url)
            val cached = cache.buildProbe(app.pkg)
            if (cached != null && cached.first == md5) {
                applyBuildCode(app, installed, cached.second, live)
                continue
            }
            candidates++
            probeExecutor.execute {
                val code = ApkBuildProbe.versionCode(url) ?: return@execute
                cache.putBuildProbe(app.pkg, md5, code)
                applyBuildCode(app, installed, code, live)
            }
        }
    }

    private fun applyBuildCode(
        app: StoreApp,
        installed: InstalledApp,
        code: Long,
        live: MutableMap<String, RowItem>
    ) {
        if (code <= installed.versionCode) return
        if (cache.isRegression(app.pkg, app.versionName)) return
        if (ignoreStore.isIgnored(app.pkg, app.versionName, app.apkMd5)) return
        synchronized(live) {
            live[app.pkg] = RowItem(app, installed.versionName, true, buildCode = code)
        }
        flushLiveRows(live)
    }

    /** 檢查過程中把即時結果合併回畫面（150ms 節流），讓「快取」逐步變成即時資料。 */
    private fun flushLiveRows(live: MutableMap<String, RowItem>) {
        if (flushScheduled) return
        flushScheduled = true
        val delay = (150 - (System.currentTimeMillis() - lastFlushAt)).coerceAtLeast(0)
        mainHandler.postDelayed({
            flushScheduled = false
            lastFlushAt = System.currentTimeMillis()
            val rows = synchronized(live) { live.values.toList() }
                .sortedBy { it.store.name.lowercase() }
            if (!loggedLive) {
                loggedLive = true
                android.util.Log.i("YYBLite", "live flush ${rows.size}")
            }
            updateRows = rows
            if (searchQuery.isEmpty()) showRows(rows)
        }, delay)
    }

    private fun buildRows(
        installed: List<InstalledApp>,
        cached: Map<String, StoreApp>
    ): List<RowItem> = installed.mapNotNull { app ->
        val store = cached[app.pkg] ?: return@mapNotNull null
        if (!UpdateDecision.needsUpdate(store, app.versionName, app.versionCode)) return@mapNotNull null
        if (ignoreStore.isIgnored(store.pkg, store.versionName, store.apkMd5)) return@mapNotNull null
        if (cache.isRegression(store.pkg, store.versionName)) return@mapNotNull null
        val certOk = dev.yyblite.install.ApkSignature.matchesInstalled(
            this, store.signatureMd5, store.pkg
        )
        RowItem(store, app.versionName, true, cached = true, certMismatch = !certOk)
    }.sortedBy { it.store.name.lowercase() }

    private fun runSearch(query: String) {
        searchQuery = query
        setStatus(getString(R.string.status_searching, query))
        io.execute {
            scanLatch.await(20, TimeUnit.SECONDS)
            val results = StoreClient.search(query)
            val installedByPkg = scanned.associateBy { it.pkg }
            val rows = results.map { app ->
                val installed = installedByPkg[app.pkg]
                RowItem(
                    store = app,
                    installedVersion = installed?.versionName,
                    hasUpdate = installed != null &&
                        UpdateDecision.needsUpdate(app, installed.versionName, installed.versionCode)
                )
            }
            runOnUiThread {
                searchRows = rows
                showRows(rows)
                setStatus(
                    if (rows.isEmpty()) getString(R.string.status_search_empty, query)
                    else getString(R.string.status_search, query, rows.size)
                )
            }
        }
    }

    private fun onRowAction(item: RowItem) {
        if (item.checkFailed) {
            recheck(item)
            return
        }
        when (DownloadBus.snapshot()[item.store.pkg]?.phase) {
            Phase.DONE -> {
                val file = DownloadStarter.apkFile(this, item.store)
                if (file.exists()) installDownloaded(file) else startDownload(item.store)
            }
            Phase.RUNNING, Phase.QUEUED -> Unit
            else -> startDownload(item.store)
        }
    }

    private fun installDownloaded(file: java.io.File) {
        io.execute {
            val downgrade = InstallerEngine.downgradeInfo(this, listOf(file))
            runOnUiThread {
                if (downgrade != null) {
                    confirmDowngrade(file, downgrade)
                } else {
                    performInstall(file)
                }
            }
        }
    }

    private fun confirmDowngrade(file: java.io.File, info: dev.yyblite.install.DowngradeInfo) {
        AlertDialog.Builder(this)
            .setTitle(R.string.downgrade_title)
            .setMessage(
                getString(
                    R.string.downgrade_message,
                    info.installedVersion ?: "?",
                    info.installedCode,
                    info.targetVersion ?: "?",
                    info.targetCode
                )
            )
            .setPositiveButton(R.string.downgrade_confirm) { _, _ -> performInstall(file) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun performInstall(file: java.io.File) {
        android.util.Log.i("YYBLite", "install start ${file.name}")
        io.execute {
            val outcome = InstallerEngine.install(this, listOf(file))
            android.util.Log.i("YYBLite", "install outcome: $outcome")
            runOnUiThread {
                when (outcome) {
                    is InstallOutcome.Installed -> Toast.makeText(
                        this,
                        if (outcome.via == InstallerKind.ROOT) R.string.installer_installed_root
                        else R.string.installer_installed_shizuku,
                        Toast.LENGTH_SHORT
                    ).show()
                    InstallOutcome.Handoff -> InstallHandoff.install(this, file)
                    is InstallOutcome.Failed -> Toast.makeText(
                        this,
                        getString(R.string.installer_failed, outcome.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showAutoUpdateDialog() {
        val prefs = AutoUpdatePrefs(this)
        val density = resources.displayMetrics.density
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (8 * density).toInt(), (20 * density).toInt(), 0)
        }
        container.addView(android.widget.TextView(this).apply {
            text = getString(R.string.auto_update_message)
            textSize = 13f
            setTextColor(androidx.core.content.ContextCompat.getColor(this@MainActivity, android.R.color.darker_gray))
        })
        val check = android.widget.CheckBox(this).apply {
            text = getString(R.string.auto_update_enable)
            textSize = 14f
            isChecked = prefs.enabled
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        container.addView(check)
        AlertDialog.Builder(this)
            .setTitle(R.string.auto_update_title)
            .setView(container)
            .setPositiveButton(R.string.installer_apply) { _, _ ->
                prefs.enabled = check.isChecked
                if (check.isChecked) {
                    val status = InstallerEngine.status(this)
                    if (status.rootGranted != true && !status.shizukuGranted) {
                        Toast.makeText(this, R.string.auto_update_need_privilege, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showInstallerDialog() {
        val status = installerStatus ?: InstallerEngine.status(this)
        val prefs = InstallerPrefs(this)
        val modes = listOf(
            InstallerMode.AUTO,
            InstallerMode.ROOT,
            InstallerMode.SHIZUKU,
            InstallerMode.EXTERNAL
        )
        val labels = listOf(
            getString(R.string.installer_auto),
            getString(R.string.installer_root),
            getString(R.string.installer_shizuku),
            getString(R.string.installer_external)
        )
        val statusText = buildString {
            append(
                when {
                    status.rootGranted == true -> getString(R.string.installer_root_granted)
                    status.rootBinary -> getString(R.string.installer_root_status_yes)
                    else -> getString(R.string.installer_root_status_no)
                }
            )
            append('\n')
            append(
                when {
                    !status.shizukuAlive -> getString(R.string.installer_shizuku_absent)
                    status.shizukuGranted -> getString(R.string.installer_shizuku_granted)
                    else -> getString(R.string.installer_shizuku_denied)
                }
            )
        }

        val density = resources.displayMetrics.density
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (8 * density).toInt(), (20 * density).toInt(), 0)
        }
        val fastBox = android.widget.CheckBox(this).apply {
            text = getString(R.string.installer_fast_dexopt)
            textSize = 13f
            isChecked = prefs.dexoptMode == dev.yyblite.install.DexoptMode.FAST
            setPadding(0, (8 * density).toInt(), 0, (4 * density).toInt())
        }
        val statusView = android.widget.TextView(this).apply {
            text = statusText
            textSize = 12f
            setTextColor(androidx.core.content.ContextCompat.getColor(this@MainActivity, android.R.color.darker_gray))
            setPadding(0, 0, 0, (10 * density).toInt())
        }
        val group = android.widget.RadioGroup(this)
        labels.forEachIndexed { index, label ->
            val button = android.widget.RadioButton(this).apply {
                id = index + 1
                text = label
                textSize = 14f
                isChecked = modes[index] == prefs.mode
                setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
            }
            group.addView(button)
        }
        container.addView(statusView)
        container.addView(group)
        container.addView(fastBox)

        AlertDialog.Builder(this)
            .setTitle(R.string.installer_title)
            .setView(container)
            .setPositiveButton(R.string.installer_apply) { _, _ ->
                val selected = (group.checkedRadioButtonId - 1).coerceIn(0, modes.lastIndex)
                prefs.mode = modes[selected]
                prefs.dexoptMode = if (fastBox.isChecked) {
                    dev.yyblite.install.DexoptMode.FAST
                } else {
                    dev.yyblite.install.DexoptMode.STANDARD
                }
                installerStatus = null
                if (modes[selected] == InstallerMode.SHIZUKU &&
                    status.shizukuAlive && !status.shizukuGranted
                ) {
                    ShizukuInstaller.requestPermission()
                }
            }
            .setNeutralButton(R.string.installer_request_shizuku) { _, _ ->
                if (!ShizukuInstaller.binderAlive()) {
                    Toast.makeText(this, R.string.installer_shizuku_not_running, Toast.LENGTH_SHORT).show()
                } else {
                    ShizukuInstaller.requestPermission()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openDetail(item: RowItem) {
        startActivity(AppDetailActivity.intent(this, item.store, item.installedVersion))
    }

    /** 長按：忽略本次更新／忽略此應用更新／取消忽略。 */
    private fun onRowLongClick(item: RowItem) {
        val pkg = item.store.pkg
        val options = ArrayList<String>()
        options.add(getString(R.string.ignore_once))
        options.add(getString(R.string.ignore_forever))
        if (ignoreStore.isIgnoredForever(pkg) || ignoreStore.ignoredOnceVersion(pkg) != null) {
            options.add(getString(R.string.ignore_clear))
        }
        AlertDialog.Builder(this)
            .setTitle(item.store.name)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> ignoreStore.ignoreOnce(pkg, item.store.versionName, item.store.apkMd5)
                    1 -> ignoreStore.ignoreForever(pkg)
                    else -> ignoreStore.clear(pkg)
                }
                reloadRows()
                Toast.makeText(this, options[which], Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun reloadRows() {
        val installed = scanned
        val cached = cache.load()
        updateRows = buildRows(installed, cached)
        if (searchQuery.isEmpty()) showRows(updateRows)
        setStatus(updateStatus())
    }

    private fun recheck(item: RowItem) {
        setStatus(getString(R.string.status_checking, 1, 1))
        io.execute {
            val result = StoreClient.lookup(item.store.pkg)
            val installed = scanned.firstOrNull { it.pkg == item.store.pkg }
            val next = when (result) {
                is LookupResult.Found -> {
                    cache.put(result.app)
                    val current = installed?.versionName ?: item.installedVersion
                    if (current == null ||
                        !UpdateDecision.needsUpdate(
                            result.app,
                            current,
                            installed?.versionCode ?: installedVersionCode(item.store.pkg)
                        ) ||
                        ignoreStore.isIgnored(result.app.pkg, result.app.versionName, result.app.apkMd5) ||
                        cache.isRegression(result.app.pkg, result.app.versionName)
                    ) {
                        null
                    } else {
                        RowItem(
                            result.app, current, true,
                            certMismatch = !dev.yyblite.install.ApkSignature.matchesInstalled(
                                this, result.app.signatureMd5, result.app.pkg
                            )
                        )
                    }
                }
                is LookupResult.Failed -> {
                    val base = installed ?: InstalledApp(
                        item.store.pkg,
                        item.store.name,
                        item.store.versionName,
                        installedVersionCode(item.store.pkg)
                    )
                    RowItem(placeholder(base), base.versionName, false, true, error = result.reason)
                }
                LookupResult.NotInStore -> {
                    cache.remove(item.store.pkg)
                    null
                }
            }
            runOnUiThread {
                replaceRow(item.store.pkg, next)
                setStatus(updateStatus())
            }
        }
    }

    private fun refreshPackage(pkg: String) {
        if (busy) return
        io.execute {
            val installed = installedVersion(pkg)
            val result = StoreClient.lookup(pkg)
            runOnUiThread {
                val next = when (result) {
                    is LookupResult.Found -> {
                        cache.put(result.app)
                        if (installed != null &&
                            UpdateDecision.needsUpdate(
                                result.app, installed, installedVersionCode(pkg)
                            ) &&
                            !cache.isRegression(result.app.pkg, result.app.versionName)
                        ) {
                            RowItem(
                                result.app, installed, true,
                                certMismatch = !dev.yyblite.install.ApkSignature.matchesInstalled(
                                    this, result.app.signatureMd5, result.app.pkg
                                )
                            )
                        } else {
                            null
                        }
                    }
                    else -> null
                }
                if (next == null) DownloadBus.clear(pkg)
                replaceRow(pkg, next)
                setStatus(updateStatus())
            }
        }
    }

    private fun installedVersionCode(pkg: String): Long = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(pkg, 0).longVersionCode
    } catch (_: PackageManager.NameNotFoundException) {
        0L
    }

    private fun installedVersion(pkg: String): String? = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(pkg, 0).versionName
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun replaceRow(pkg: String, next: RowItem?) {
        fun merge(list: List<RowItem>): List<RowItem> {
            val filtered = list.filterNot { it.store.pkg == pkg }
            val combined = if (next == null) filtered else filtered + next
            return combined.sortedBy { it.store.name.lowercase() }
        }
        updateRows = merge(updateRows)
        searchRows = merge(searchRows)
        showRows(if (searchQuery.isEmpty()) updateRows else searchRows)
    }

    private fun updateSelectedOrAll() {
        val selected = adapter.selectedPkgs()
        val rows = if (searchQuery.isEmpty()) updateRows else searchRows
        val targets = if (selected.isNotEmpty()) {
            rows.filter { it.store.pkg in selected && it.hasUpdate && !it.checkFailed && !it.certMismatch }
        } else {
            rows.filter { it.hasUpdate && !it.checkFailed && !it.certMismatch }
        }
        if (targets.isEmpty()) {
            Toast.makeText(this, R.string.status_none, Toast.LENGTH_SHORT).show()
            return
        }
        targets.forEach { startDownload(it.store) }
        adapter.clearSelection()
        onSelectionChanged()
    }

    private fun onSelectionChanged() {
        val count = adapter.selectedPkgs().size
        binding.updateAll.text = if (count > 0) {
            getString(R.string.action_update_selected, count)
        } else {
            getString(R.string.menu_update_all)
        }
    }

    private fun startDownload(app: StoreApp) {
        if (app.apkUrl64 != null) {
            requestOrStart(app)
            return
        }
        io.execute {
            val enriched = (StoreClient.lookup(app.pkg) as? LookupResult.Found)?.app ?: app
            runOnUiThread { requestOrStart(enriched) }
        }
    }

    private fun requestOrStart(app: StoreApp) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownloads.add(app)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startDownloadNow(app)
    }

    private fun startDownloadNow(app: StoreApp) {
        DownloadStarter.start(this, app)
    }

    private fun placeholder(app: InstalledApp): StoreApp = StoreApp(
        pkg = app.pkg,
        name = app.label,
        versionName = "",
        versionCode = null,
        apkUrl = "",
        apkUrl64 = null,
        apkMd5 = "",
        signatureMd5 = null,
        sizeBytes = -1,
        sizeDesc = null,
        iconUrl = null,
        developer = null,
        downloadDesc = null,
        rating = null,
        screenshots = emptyList(),
        editorIntro = null
    )

    private fun updateStatus(): String {
        val updates = updateRows.count { it.hasUpdate }
        val failures = updateRows.count { it.checkFailed }
        return when {
            failures > 0 -> getString(R.string.status_updates_with_failures, updates, failures)
            updates > 0 -> getString(R.string.status_updates, updates)
            else -> getString(R.string.status_none)
        }
    }

    private fun updateGlobalStatus() {
        val states = DownloadBus.snapshot().values
        val running = states.filter { it.phase == Phase.RUNNING }
        val queued = states.filter { it.phase == Phase.QUEUED }
        if (running.isEmpty() && queued.isEmpty()) {
            setStatus(updateStatus())
            return
        }
        val speed = running.sumOf { it.speedBps }
        val remaining = running.sumOf { (it.total - it.bytes).coerceAtLeast(0) } +
            queued.sumOf { if (it.sizeHint > 0) it.sizeHint else 0L }
        val eta = if (speed > 0) remaining / speed else 0L
        setStatus(
            getString(
                R.string.global_downloading,
                Format.speed(speed),
                Format.eta(eta),
                running.size + queued.size
            )
        )
    }

    private fun maybeAskOverlay() {
        if (Settings.canDrawOverlays(this)) return
        if (updateRows.none { it.hasUpdate }) return
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (prefs.getBoolean("overlay_asked", false)) return
        prefs.edit().putBoolean("overlay_asked", true).apply()

        AlertDialog.Builder(this)
            .setTitle(R.string.overlay_title)
            .setMessage(R.string.overlay_message)
            .setPositiveButton(R.string.overlay_open) { _, _ ->
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            .setNegativeButton(R.string.overlay_later, null)
            .show()
    }

    private fun showRows(rows: List<RowItem>) {
        adapter.setSelectable(searchQuery.isEmpty() && rows.any { it.hasUpdate })
        adapter.submitList(rows)
        binding.updateAll.isEnabled =
            searchQuery.isEmpty() && rows.any { it.hasUpdate }
        onSelectionChanged()
    }

    private fun finishRefresh() {
        busy = false
        binding.swipe.isRefreshing = false
    }

    private fun setStatus(text: String) {
        binding.status.text = text
    }
}
