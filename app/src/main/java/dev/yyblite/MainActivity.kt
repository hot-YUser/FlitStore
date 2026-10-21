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
import dev.yyblite.data.IgnoreStore
import dev.yyblite.data.InstalledApp
import dev.yyblite.data.InstalledScanner
import dev.yyblite.data.LookupResult
import dev.yyblite.data.StoreApp
import dev.yyblite.data.StoreClient
import dev.yyblite.data.UpdateCache
import dev.yyblite.data.UpdateChecker
import dev.yyblite.data.UpdateDecision
import dev.yyblite.databinding.ActivityMainBinding
import dev.yyblite.download.DownloadBus
import dev.yyblite.download.DownloadStarter
import dev.yyblite.download.Phase
import dev.yyblite.install.AutoInstall
import dev.yyblite.install.InstallHandoff
import dev.yyblite.ui.AppAdapter
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
    private val checker = UpdateChecker(4)
    private val scanLatch = CountDownLatch(1)

    private lateinit var cache: UpdateCache
    private lateinit var ignoreStore: IgnoreStore

    @Volatile private var scanned: List<InstalledApp> = emptyList()
    @Volatile private var busy = false

    private var updateRows: List<RowItem> = emptyList()
    private var searchRows: List<RowItem> = emptyList()
    private var searchQuery: String = ""
    private var subscription: AutoCloseable? = null
    private val pendingDownloads = ArrayList<StoreApp>()

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart ?: return
            if (pkg == packageName) return
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
        R.id.action_ignored -> {
            showIgnoredDialog()
            true
        }
        else -> super.onOptionsItemSelected(item)
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
        DownloadBus.snapshot().values
            .filter { it.phase == Phase.DONE }
            .forEach { AutoInstall.tryInstall(this, it.pkg, it.version ?: return@forEach, it.abi ?: "v7a") }
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
        checker.shutdown()
        io.shutdownNow()
        super.onDestroy()
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
        searchQuery = ""
        setStatus(getString(R.string.status_scanning))

        io.execute {
            val installed = InstalledScanner.scan(this)
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
            val fresh = !force && cached.isNotEmpty() && cache.isFresh(fingerprint)

            runOnUiThread {
                updateRows = cachedRows
                if (searchQuery.isEmpty()) showRows(cachedRows)
                setStatus(updateStatus())
                if (fresh) {
                    android.util.Log.i("YYBLite", "cache hit: ${cachedRows.size} rows, skip network")
                    finishRefresh()
                }
            }
            if (fresh) return@execute

            val total = installed.size
            val done = AtomicInteger(0)
            val found = Collections.synchronizedList(ArrayList<RowItem>())
            val failed = Collections.synchronizedList(ArrayList<RowItem>())
            val successes = Collections.synchronizedList(ArrayList<StoreApp>())
            val removed = Collections.synchronizedList(ArrayList<String>())

            checker.check(
                installed,
                { app, result ->
                    when (result) {
                        is LookupResult.Found -> {
                            successes.add(result.app)
                            if (UpdateDecision.needsUpdate(result.app, app.versionName, app.versionCode) &&
                                !ignoreStore.isIgnored(result.app.pkg, result.app.versionName)
                            ) {
                                found.add(RowItem(result.app, app.versionName, true))
                            }
                        }
                        is LookupResult.Failed -> {
                            val stale = cached[app.pkg]
                            if (stale != null &&
                                UpdateDecision.needsUpdate(stale, app.versionName, app.versionCode) &&
                                !ignoreStore.isIgnored(stale.pkg, stale.versionName)
                            ) {
                                found.add(RowItem(stale, app.versionName, true, cached = true))
                            } else {
                                failed.add(RowItem(placeholder(app), app.versionName, false, true))
                            }
                        }
                        LookupResult.NotInStore -> removed.add(app.pkg)
                    }
                    val checked = done.incrementAndGet()
                    runOnUiThread {
                        if (searchQuery.isEmpty()) {
                            setStatus(getString(R.string.status_checking, checked, total))
                        }
                    }
                },
                {
                    val merged = HashMap(cached)
                    successes.forEach { merged[it.pkg] = it }
                    removed.forEach { merged.remove(it) }
                    cache.save(merged.values)
                    cache.markChecked(fingerprint)

                    val rows = (found + failed).sortedBy { it.store.name.lowercase() }
                    android.util.Log.i(
                        "YYBLite",
                        "check done: ${installed.size} apps, ${rows.size} rows, ${failed.size} failed"
                    )
                    runOnUiThread {
                        updateRows = rows
                        if (searchQuery.isEmpty()) showRows(rows)
                        finishRefresh()
                        setStatus(updateStatus())
                        maybeAskOverlay()
                    }
                }
            )
        }
    }

    private fun buildRows(
        installed: List<InstalledApp>,
        cached: Map<String, StoreApp>
    ): List<RowItem> = installed.mapNotNull { app ->
        val store = cached[app.pkg] ?: return@mapNotNull null
        if (!UpdateDecision.needsUpdate(store, app.versionName, app.versionCode)) return@mapNotNull null
        if (ignoreStore.isIgnored(store.pkg, store.versionName)) return@mapNotNull null
        RowItem(store, app.versionName, true, cached = true)
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
                if (file.exists()) InstallHandoff.install(this, file) else startDownload(item.store)
            }
            Phase.RUNNING, Phase.QUEUED -> Unit
            else -> startDownload(item.store)
        }
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
                    0 -> ignoreStore.ignoreOnce(pkg, item.store.versionName)
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
                        ignoreStore.isIgnored(result.app.pkg, result.app.versionName)
                    ) {
                        null
                    } else {
                        RowItem(result.app, current, true)
                    }
                }
                is LookupResult.Failed -> {
                    val base = installed ?: InstalledApp(
                        item.store.pkg,
                        item.store.name,
                        item.store.versionName,
                        installedVersionCode(item.store.pkg)
                    )
                    RowItem(placeholder(base), base.versionName, false, true)
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
                        if (installed == null ||
                            UpdateDecision.needsUpdate(
                                result.app, installed, installedVersionCode(pkg)
                            )
                        ) {
                            RowItem(result.app, installed, true)
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
            rows.filter { it.store.pkg in selected && it.hasUpdate && !it.checkFailed }
        } else {
            rows.filter { it.hasUpdate && !it.checkFailed }
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
