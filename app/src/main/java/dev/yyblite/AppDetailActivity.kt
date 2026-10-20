package dev.yyblite

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import dev.yyblite.data.Abi
import dev.yyblite.data.AppRef
import dev.yyblite.data.IgnoreStore
import dev.yyblite.data.LookupResult
import dev.yyblite.data.Review
import dev.yyblite.data.StoreApp
import dev.yyblite.data.StoreClient
import dev.yyblite.data.StoreDetail
import dev.yyblite.data.StoreDetailClient
import dev.yyblite.data.UpdateDecision
import dev.yyblite.data.VersionHistory
import dev.yyblite.databinding.ActivityDetailBinding
import dev.yyblite.download.DownloadBus
import dev.yyblite.download.DownloadStarter
import dev.yyblite.download.Phase
import dev.yyblite.install.InstallHandoff
import dev.yyblite.ui.AppVisibility
import dev.yyblite.ui.Format
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
    private lateinit var ignoreStore: IgnoreStore
    private lateinit var history: VersionHistory

    private var app: StoreApp? = null
    private var detail: StoreDetail? = null
    private var installedVersion: String? = null
    private var subscription: AutoCloseable? = null
    private var requestedDownload = false

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart ?: return
            if (pkg != app?.pkg) return
            installedVersion = installedVersionOf(pkg)
            runOnUiThread { refreshAction() }
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

        ignoreStore = IgnoreStore(this)
        history = VersionHistory(this)

        installedVersion = intent.getStringExtra(EXTRA_INSTALLED)
        val pkg = intent.getStringExtra(EXTRA_PKG)
        val fallbackName = intent.getStringExtra(EXTRA_NAME) ?: pkg.orEmpty()

        binding.name.text = fallbackName
        binding.developer.text = pkg.orEmpty()
        binding.status.text = getString(R.string.detail_loading)
        binding.action.isEnabled = false

        binding.ignoreOnce.setOnClickListener {
            app?.let {
                ignoreStore.ignoreOnce(it.pkg, it.versionName)
                binding.status.text = getString(R.string.detail_ignored)
                binding.ignoreOnce.isEnabled = false
                binding.ignoreForever.isEnabled = false
            }
        }
        binding.ignoreForever.setOnClickListener {
            app?.let {
                ignoreStore.ignoreForever(it.pkg)
                binding.status.text = getString(R.string.detail_ignored)
                binding.ignoreOnce.isEnabled = false
                binding.ignoreForever.isEnabled = false
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        if (pkg.isNullOrBlank()) {
            binding.status.text = getString(R.string.detail_not_in_store)
            return
        }

        io.execute {
            val result = StoreClient.lookup(pkg)
            runOnUiThread {
                when (result) {
                    is LookupResult.Found -> {
                        val store = result.app
                        app = store
                        installedVersion = installedVersion ?: installedVersionOf(pkg)
                        render(store)
                    }
                    LookupResult.NotInStore -> binding.status.text = getString(R.string.detail_not_in_store)
                    is LookupResult.Failed ->
                        binding.status.text = getString(R.string.detail_failed, result.reason)
                }
            }

            val extra = StoreDetailClient.fetch(pkg)
            if (extra != null) {
                runOnUiThread {
                    detail = extra
                    renderDetail(extra)
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
        binding.slogan.text = store.slogan.orEmpty()
        binding.slogan.isVisible = !store.slogan.isNullOrBlank()
        binding.meta.text = listOfNotNull(
            store.downloadDesc?.let { "下載 $it" },
            store.rating?.let { "評分 %.1f" .format(it) + if (store.ratingCount > 0) "（${store.ratingCount} 人）" else "" }
        ).joinToString(" · ")
        binding.official.isVisible = store.official

        ThumbCache.load(store.iconUrl, binding.icon)

        binding.screenshots.removeAllViews()
        binding.screenshots.isVisible = store.screenshots.isNotEmpty()
        val width = (100 * resources.displayMetrics.density).toInt()
        val height = (178 * resources.displayMetrics.density).toInt()
        val gap = (8 * resources.displayMetrics.density).toInt()
        store.screenshots.forEachIndexed { index, url ->
            val view = ImageView(this)
            view.layoutParams = LinearLayout.LayoutParams(width, height).apply {
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
        binding.ignoreOnce.isEnabled = !ignoreStore.isIgnoredForever(store.pkg) &&
            ignoreStore.ignoredOnceVersion(store.pkg) != store.versionName
        binding.ignoreForever.isEnabled = !ignoreStore.isIgnoredForever(store.pkg)
        if (ignoreStore.isIgnoredForever(store.pkg)) {
            binding.status.text = getString(R.string.detail_ignored)
        }

        renderHistory(store)
        renderDetail(detail)
        refreshAction()
    }

    private fun renderDetail(extra: StoreDetail?) {
        binding.info.removeAllViews()
        val store = app
        if (store != null) {
            addInfo(getString(R.string.detail_version), store.versionName)
            addInfo(getString(R.string.detail_size), store.sizeDesc ?: Format.bytes(store.sizeBytes))
            store.rating?.let { rating ->
                val count = if (store.ratingCount > 0) "（${store.ratingCount} 人）" else ""
                addInfo(getString(R.string.detail_rating), "%.1f / 5%s".format(rating, count))
            }
            store.downloadDesc?.let { addInfo(getString(R.string.detail_downloads), it) }
        }
        if (extra == null) return

        extra.category?.let { addInfo(getString(R.string.detail_category), it) }
        extra.updateTimeSec?.let {
            addInfo(getString(R.string.detail_updated), Format.date(it))
        }
        extra.operator?.let { operator ->
            addInfo(getString(R.string.detail_operator), operator) {
                openDeveloper(operator, developerMode = false)
            }
        }
        store?.developer?.let { developer ->
            addInfo(getString(R.string.detail_developer), developer) {
                openDeveloper(developer, developerMode = true)
            }
        }
        store?.icp?.let { addInfo(getString(R.string.detail_icp), it) }

        renderReviews(extra.reviews)
        renderSameDeveloper(extra.sameDeveloper)
    }

    private fun addInfo(label: String, value: String, onClick: (() -> Unit)? = null) {
        if (value.isBlank()) return
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(3), 0, dp(3))

        val name = TextView(this)
        name.text = label
        name.textSize = 13f
        name.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        name.layoutParams = LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.WRAP_CONTENT)

        val content = TextView(this)
        content.text = value
        content.textSize = 13f
        content.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        if (onClick != null) {
            content.setTextColor(ContextCompat.getColor(this, R.color.primary))
            content.setOnClickListener { onClick() }
        }
        Linkify.addLinks(content, Linkify.WEB_URLS)
        content.linksClickable = true

        row.addView(name)
        row.addView(content)
        binding.info.addView(row)
    }

    private fun renderReviews(reviews: List<Review>) {
        binding.reviews.removeAllViews()
        binding.reviewsTitle.isVisible = reviews.isNotEmpty()
        reviews.take(6).forEach { review ->
            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            box.setPadding(0, dp(6), 0, dp(6))

            val header = TextView(this)
            val stars = "★".repeat(review.stars.coerceIn(0, 5)) + "☆".repeat((5 - review.stars).coerceIn(0, 5))
            header.text = "${review.user}  $stars  ${review.date}"
            header.textSize = 12f
            header.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))

            val body = TextView(this)
            body.text = review.content
            body.textSize = 13f
            body.setPadding(0, dp(2), 0, 0)

            box.addView(header)
            box.addView(body)
            binding.reviews.addView(box)
        }
    }

    private fun renderSameDeveloper(refs: List<AppRef>) {
        binding.same.removeAllViews()
        binding.sameTitle.isVisible = refs.isNotEmpty()
        refs.forEach { ref ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(6), 0, dp(6))

            val icon = ImageView(this)
            icon.layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            ThumbCache.load(ref.iconUrl, icon)

            val text = TextView(this)
            text.text = ref.name
            text.textSize = 14f
            text.setPadding(dp(10), 0, 0, 0)

            row.addView(icon)
            row.addView(text)
            row.setOnClickListener {
                startActivity(
                    intentFor(
                        ref.pkg,
                        ref.name,
                        installedVersionOf(ref.pkg)
                    )
                )
            }
            binding.same.addView(row)
        }
    }

    private fun renderHistory(store: StoreApp) {
        binding.history.removeAllViews()
        val list = history.history(store.pkg)
        binding.historyTitle.isVisible = true
        if (list.isEmpty()) {
            val empty = TextView(this)
            empty.text = getString(R.string.detail_history_empty)
            empty.textSize = 12f
            empty.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            binding.history.addView(empty)
            return
        }
        list.take(8).forEach { (version, at) ->
            val row = TextView(this)
            row.text = "$version · ${Format.time(at)}"
            row.textSize = 13f
            row.setTypeface(Typeface.MONOSPACE)
            row.setPadding(0, dp(2), 0, dp(2))
            binding.history.addView(row)
        }
    }

    private fun openDeveloper(name: String, developerMode: Boolean) {
        startActivity(DeveloperActivity.intent(this, name, developerMode, app?.pkg))
    }

    private fun intentFor(pkg: String, name: String, installed: String?): Intent =
        Intent(this, AppDetailActivity::class.java)
            .putExtra(EXTRA_PKG, pkg)
            .putExtra(EXTRA_NAME, name)
            .putExtra(EXTRA_INSTALLED, installed)

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
            Phase.RUNNING -> {
                val state = DownloadBus.snapshot()[store.pkg]
                if (state != null && state.speedBps > 0) {
                    val eta = if (state.total > state.bytes) (state.total - state.bytes) / state.speedBps else 0
                    Format.speed(state.speedBps) + " · 剩 " + Format.eta(eta)
                } else {
                    ""
                }
            }
            Phase.FAILED -> DownloadBus.snapshot()[store.pkg]?.error.orEmpty()
            Phase.DONE -> getString(R.string.detail_downloaded)
            else -> ""
        }
    }

    private fun isUpToDate(store: StoreApp): Boolean {
        val installed = installedVersion ?: return false
        return !UpdateDecision.needsUpdate(store, installed, installedVersionCodeOf(store.pkg))
    }

    private fun installedVersionCodeOf(pkg: String): Long = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(pkg, 0).longVersionCode
    } catch (_: PackageManager.NameNotFoundException) {
        0L
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
