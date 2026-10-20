package dev.yyblite

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import dev.yyblite.data.InstalledApp
import dev.yyblite.data.InstalledScanner
import dev.yyblite.data.LookupResult
import dev.yyblite.data.StoreApp
import dev.yyblite.data.StoreClient
import dev.yyblite.data.UpdateChecker
import dev.yyblite.data.VersionOrder
import dev.yyblite.databinding.ActivityMainBinding
import dev.yyblite.download.DownloadBus
import dev.yyblite.download.DownloadStarter
import dev.yyblite.download.Phase
import dev.yyblite.install.InstallHandoff
import dev.yyblite.ui.AppAdapter
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

    @Volatile private var scanned: List<InstalledApp> = emptyList()
    @Volatile private var busy = false

    private var updateRows: List<RowItem> = emptyList()
    private var searchRows: List<RowItem> = emptyList()
    private var searchQuery: String = ""
    private var subscription: AutoCloseable? = null
    private val pendingDownloads = ArrayList<StoreApp>()

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

        adapter = AppAdapter(::onRowAction, ::openDetail)
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.updateAll.setOnClickListener { updateAll() }
        binding.updateAll.isEnabled = false

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
        R.id.action_refresh -> {
            refresh()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onStart() {
        super.onStart()
        subscription = DownloadBus.subscribe { state ->
            runOnUiThread { adapter.setState(state) }
        }
        adapter.restoreStates()
    }

    override fun onStop() {
        subscription?.close()
        subscription = null
        super.onStop()
    }

    override fun onDestroy() {
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

    private fun refresh() {
        if (busy) return
        busy = true
        searchQuery = ""
        setProgress(true)
        setStatus(getString(R.string.status_scanning))

        io.execute {
            val installed = InstalledScanner.scan(this)
            scanned = installed
            scanLatch.countDown()

            if (installed.isEmpty()) {
                runOnUiThread {
                    updateRows = emptyList()
                    showRows(updateRows)
                    setProgress(false)
                    setStatus(getString(R.string.status_none))
                    busy = false
                }
                return@execute
            }

            val total = installed.size
            val done = AtomicInteger(0)
            val found = Collections.synchronizedList(ArrayList<RowItem>())
            val failed = Collections.synchronizedList(ArrayList<RowItem>())

            checker.check(
                installed,
                { app, result ->
                    when (result) {
                        is LookupResult.Found -> {
                            if (VersionOrder.compare(result.app.versionName, app.versionName) > 0) {
                                found.add(RowItem(result.app, app.versionName, true))
                            }
                        }
                        is LookupResult.Failed -> {
                            android.util.Log.w("YYBLite", "${app.pkg}: ${result.reason}")
                            failed.add(RowItem(placeholder(app), app.versionName, false, true))
                        }
                        LookupResult.NotInStore -> Unit
                    }
                    val checked = done.incrementAndGet()
                    runOnUiThread {
                        if (searchQuery.isEmpty()) {
                            setStatus(getString(R.string.status_checking, checked, total))
                        }
                    }
                },
                {
                    val rows = (found + failed).sortedBy { it.store.name.lowercase() }
                    runOnUiThread {
                        updateRows = rows
                        if (searchQuery.isEmpty()) showRows(rows)
                        setProgress(false)
                        setStatus(updateStatus())
                        busy = false
                    }
                }
            )
        }
    }

    private fun runSearch(query: String) {
        searchQuery = query
        setProgress(true)
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
                        VersionOrder.compare(app.versionName, installed.versionName) > 0
                )
            }
            runOnUiThread {
                searchRows = rows
                showRows(rows)
                setProgress(false)
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

    private fun recheck(item: RowItem) {
        setStatus("檢查 ${item.store.name}…")
        io.execute {
            val result = StoreClient.lookup(item.store.pkg)
            val installed = scanned.firstOrNull { it.pkg == item.store.pkg }
            val next = when (result) {
                is LookupResult.Found -> {
                    val current = installed?.versionName ?: item.installedVersion
                    if (current == null || VersionOrder.compare(result.app.versionName, current) <= 0) {
                        null
                    } else {
                        RowItem(result.app, current, true)
                    }
                }
                is LookupResult.Failed -> {
                    val base = installed ?: InstalledApp(item.store.pkg, item.store.name, item.store.versionName)
                    RowItem(placeholder(base), base.versionName, false, true)
                }
                LookupResult.NotInStore -> null
            }
            runOnUiThread {
                replaceRow(item.store.pkg, next)
                setStatus(updateStatus())
            }
        }
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

    private fun updateAll() {
        val rows = if (searchQuery.isEmpty()) updateRows else searchRows
        val targets = rows.filter { it.hasUpdate && !it.checkFailed }
        if (targets.isEmpty()) {
            Toast.makeText(this, R.string.status_none, Toast.LENGTH_SHORT).show()
            return
        }
        targets.forEach { startDownload(it.store) }
    }

    /** 搜尋結果只帶單一連結；下載前補一次詳情查詢以取得 64 位連結與精確大小。 */
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

    private fun openDetail(item: RowItem) {
        startActivity(AppDetailActivity.intent(this, item.store, item.installedVersion))
    }

    private fun placeholder(app: InstalledApp): StoreApp = StoreApp(
        pkg = app.pkg,
        name = app.label,
        versionName = "",
        versionCode = null,
        apkUrl = "",
        apkUrl64 = null,
        apkMd5 = "",
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

    private fun showRows(rows: List<RowItem>) {
        adapter.submitList(rows)
        binding.updateAll.isEnabled = searchQuery.isEmpty() && updateRows.any { it.hasUpdate }
    }

    private fun setProgress(visible: Boolean) {
        binding.progress.isVisible = visible
    }

    private fun setStatus(text: String) {
        binding.status.text = text
    }
}
