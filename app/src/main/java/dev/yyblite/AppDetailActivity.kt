package dev.yyblite

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import dev.yyblite.data.Abi
import dev.yyblite.data.LookupResult
import dev.yyblite.data.StoreApp
import dev.yyblite.data.StoreClient
import dev.yyblite.data.VersionOrder
import dev.yyblite.databinding.ActivityDetailBinding
import dev.yyblite.download.DownloadBus
import dev.yyblite.download.DownloadStarter
import dev.yyblite.download.Phase
import dev.yyblite.install.InstallHandoff
import dev.yyblite.ui.AppVisibility
import dev.yyblite.ui.ThumbCache
import java.util.concurrent.Executors

class AppDetailActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_PKG = "pkg"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_INSTALLED = "installed"

        fun intent(ctx: Context, app: StoreApp, installedVersion: String?): Intent =
            Intent(ctx, AppDetailActivity::class.java)
                .putExtra(EXTRA_PKG, app.pkg)
                .putExtra(EXTRA_NAME, app.name)
                .putExtra(EXTRA_INSTALLED, installedVersion)
    }

    private lateinit var binding: ActivityDetailBinding
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "detail-io").apply { isDaemon = true }
    }
    private var app: StoreApp? = null
    private var installedVersion: String? = null
    private var subscription: AutoCloseable? = null
    private var requestedDownload = false

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart ?: return
            if (pkg != app?.pkg) return
            installedVersion = installedVersionOf(pkg)
            io.execute {
                val result = StoreClient.lookup(pkg)
                runOnUiThread { if (result is LookupResult.Found) render(result.app) }
            }
        }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (requestedDownload) {
                requestedDownload = false
                app?.let { DownloadStarter.start(this, it) }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        applyWindowInsets()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        supportActionBar?.title = getString(R.string.detail_title)

        installedVersion = intent.getStringExtra(EXTRA_INSTALLED)
        val pkg = intent.getStringExtra(EXTRA_PKG)
        val fallbackName = intent.getStringExtra(EXTRA_NAME) ?: pkg.orEmpty()

        binding.name.text = fallbackName
        binding.developer.text = pkg.orEmpty()
        binding.status.text = getString(R.string.detail_loading)
        binding.action.isEnabled = false

        if (pkg.isNullOrBlank()) {
            binding.status.text = getString(R.string.detail_not_in_store)
            return
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        io.execute {
            val result = StoreClient.lookup(pkg)
            runOnUiThread {
                when (result) {
                    is LookupResult.Found -> {
                        app = result.app
                        render(result.app)
                    }
                    LookupResult.NotInStore -> binding.status.text = getString(R.string.detail_not_in_store)
                    is LookupResult.Failed ->
                        binding.status.text = getString(R.string.detail_failed, result.reason)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.onStart()
        subscription = DownloadBus.subscribe { state ->
            runOnUiThread { if (state.pkg == app?.pkg) refreshAction() }
        }
        refreshAction()
    }

    override fun onStop() {
        AppVisibility.onStop()
        subscription?.close()
        subscription = null
        super.onStop()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(packageReceiver) }
        io.shutdownNow()
        super.onDestroy()
    }

    private fun applyWindowInsets() {
        val baseToolbarHeight = (56 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val params = binding.toolbar.layoutParams
            val height = baseToolbarHeight + bars.top
            if (params.height != height) {
                params.height = height
                binding.toolbar.layoutParams = params
            }
            binding.toolbar.setPadding(0, bars.top, 0, 0)
            binding.root.setPadding(0, 0, 0, bars.bottom)
            insets
        }
    }

    private fun render(store: StoreApp) {
        supportActionBar?.title = store.name
        binding.name.text = store.name
        binding.developer.text = store.developer ?: store.pkg
        binding.meta.text = listOfNotNull(
            store.downloadDesc?.let { "下載 $it" },
            store.rating?.let { "評分 %.1f".format(it) }
        ).joinToString(" · ")

        binding.versions.text = installedVersion?.let {
            getString(R.string.detail_versions, it, store.versionName)
        } ?: getString(R.string.detail_versions_new, store.versionName)

        binding.size.text = getString(
            R.string.detail_size,
            store.sizeDesc ?: formatBytes(store.sizeBytes)
        )
        binding.abi.text = if (Abi.isArm64(store, Build.SUPPORTED_ABIS.toList())) {
            getString(R.string.detail_abi64)
        } else {
            getString(R.string.detail_abi32)
        }

        ThumbCache.load(store.iconUrl, binding.icon)

        binding.screenshots.removeAllViews()
        binding.screenshots.isVisible = store.screenshots.isNotEmpty()
        val size = (100 * resources.displayMetrics.density).toInt()
        val height = (178 * resources.displayMetrics.density).toInt()
        val gap = (8 * resources.displayMetrics.density).toInt()
        store.screenshots.forEachIndexed { index, url ->
            val view = ImageView(this)
            view.layoutParams = android.widget.LinearLayout.LayoutParams(size, height).apply {
                marginStart = if (index == 0) 0 else gap
            }
            view.scaleType = ImageView.ScaleType.FIT_CENTER
            ThumbCache.load(url, view)
            binding.screenshots.addView(view)
        }

        binding.intro.text = store.editorIntro ?: ""
        binding.intro.isVisible = !store.editorIntro.isNullOrBlank()

        binding.status.text = ""
        binding.action.isEnabled = true
        binding.action.setOnClickListener { onAction() }
        refreshAction()
    }

    private fun onAction() {
        val store = app ?: return
        when (phaseOf(store)) {
            Phase.DONE -> InstallHandoff.install(this, DownloadStarter.apkFile(this, store))
            Phase.RUNNING, Phase.QUEUED -> Unit
            else -> startDownload(store)
        }
    }

    private fun startDownload(store: StoreApp) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestedDownload = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        DownloadStarter.start(this, store)
    }

    private fun refreshAction() {
        val store = app ?: return
        if (isUpToDate(store)) {
            binding.action.text = getString(R.string.action_latest)
            binding.action.isEnabled = false
            binding.status.text = ""
            return
        }
        val phase = phaseOf(store)
        binding.action.text = when (phase) {
            Phase.RUNNING -> {
                val state = DownloadBus.snapshot()[store.pkg]
                val percent = if (state != null && state.total > 0) {
                    ((state.bytes * 100) / state.total).toInt()
                } else {
                    0
                }
                getString(R.string.action_downloading, percent)
            }
            Phase.QUEUED -> getString(R.string.action_queued)
            Phase.DONE -> getString(R.string.action_install)
            Phase.FAILED -> getString(R.string.action_retry)
            null -> getString(R.string.action_update)
        }
        binding.action.isEnabled = phase != Phase.RUNNING && phase != Phase.QUEUED
        binding.status.text = when (phase) {
            Phase.FAILED -> DownloadBus.snapshot()[store.pkg]?.error.orEmpty()
            Phase.DONE -> getString(R.string.detail_downloaded)
            else -> ""
        }
    }

    private fun isUpToDate(store: StoreApp): Boolean {
        val installed = installedVersion ?: return false
        return VersionOrder.compare(store.versionName, installed) <= 0
    }

    private fun installedVersionOf(pkg: String): String? = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(pkg, 0).versionName
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun phaseOf(store: StoreApp): Phase? {
        val live = DownloadBus.snapshot()[store.pkg]?.phase
        if (live != null) return live
        return if (DownloadStarter.apkFile(this, store).exists()) Phase.DONE else null
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "未知"
        val mb = bytes / (1024.0 * 1024.0)
        return "%.1f MB".format(mb)
    }
}
