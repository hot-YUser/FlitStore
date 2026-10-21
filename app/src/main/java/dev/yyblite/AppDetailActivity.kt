package dev.yyblite

import android.Manifest
import android.app.Dialog
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
import dev.yyblite.data.IgnoreStore
import dev.yyblite.data.LookupResult
import dev.yyblite.data.Review
import dev.yyblite.data.ReviewPage
import dev.yyblite.data.StoreApp
import dev.yyblite.data.StoreClient
import dev.yyblite.data.StoreDetail
import dev.yyblite.data.StoreDetailClient
import dev.yyblite.data.UpdateDecision
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

    private var app: StoreApp? = null
    private var detail: StoreDetail? = null
    private var packageName: String = ""
    private var installedVersion: String? = null
    private var subscription: AutoCloseable? = null
    private var requestedDownload = false

    private val reviews = ArrayList<Review>()
    private var reviewTotal = 0L

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

        installedVersion = intent.getStringExtra(EXTRA_INSTALLED)
        val pkg = intent.getStringExtra(EXTRA_PKG)
        packageName = pkg.orEmpty()
        val fallbackName = intent.getStringExtra(EXTRA_NAME) ?: pkg.orEmpty()

        binding.name.text = fallbackName
        binding.developer.text = getString(R.string.detail_loading)
        binding.status.text = getString(R.string.detail_loading)
        binding.action.isEnabled = false
        binding.action.isVisible = false
        binding.ignoreOnce.isVisible = false
        binding.ignoreForever.isVisible = false

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
        binding.moreReviews.setOnClickListener {
            app?.let { ReviewActivity.intent(this, it.pkg, it.name) }?.let(::startActivity)
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
                    applyReviews(extra.reviewPage)
                    extra.description?.let {
                        binding.intro.text = it
                        binding.intro.isVisible = true
                    }
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
        binding.slogan.text = store.editorIntro.orEmpty()
        binding.slogan.isVisible = !store.editorIntro.isNullOrBlank()
        binding.meta.text = listOfNotNull(
            store.downloadDesc?.let { "下載 $it" },
            store.rating?.let { "評分 %.1f".format(it) }
        ).joinToString(" · ")

        binding.official.text = if (store.official) {
            getString(R.string.detail_official)
        } else {
            getString(R.string.detail_unofficial)
        }
        binding.official.setTextColor(
            ContextCompat.getColor(
                this,
                if (store.official) R.color.primary else android.R.color.darker_gray
            )
        )
        binding.official.setBackgroundColor(
            if (store.official) android.graphics.Color.argb(26, 30, 111, 217)
            else android.graphics.Color.argb(26, 128, 128, 128)
        )
        binding.official.setOnClickListener(null)

        binding.icon.setOnClickListener { store.iconUrl?.let(::openImage) }
        ThumbCache.load(store.iconUrl, binding.icon)

        binding.screenshots.removeAllViews()
        binding.screenshots.isVisible = store.screenshots.isNotEmpty()
        val width = (104 * resources.displayMetrics.density).toInt()
        val height = (184 * resources.displayMetrics.density).toInt()
        val gap = (8 * resources.displayMetrics.density).toInt()
        store.screenshots.forEachIndexed { index, url ->
            val view = ImageView(this)
            view.layoutParams = LinearLayout.LayoutParams(width, height).apply {
                marginStart = if (index == 0) 0 else gap
            }
            view.scaleType = ImageView.ScaleType.FIT_CENTER
            ThumbCache.load(url, view)
            view.setOnClickListener { openImage(url) }
            binding.screenshots.addView(view)
        }

        val description = detail?.description ?: store.editorIntro
        binding.intro.text = description ?: ""
        binding.intro.isVisible = !description.isNullOrBlank()

        binding.status.text = ""
        binding.action.isEnabled = true
        binding.action.isVisible = true
        binding.action.setOnClickListener { onAction() }
        binding.ignoreOnce.isVisible = true
        binding.ignoreForever.isVisible = true
        binding.ignoreOnce.isEnabled = !ignoreStore.isIgnoredForever(store.pkg) &&
            ignoreStore.ignoredOnceVersion(store.pkg) != store.versionName
        binding.ignoreForever.isEnabled = !ignoreStore.isIgnoredForever(store.pkg)
        if (ignoreStore.isIgnoredForever(store.pkg)) {
            binding.status.text = getString(R.string.detail_ignored)
        }

        renderDetail(detail)
        refreshAction()
    }

    private fun renderDetail(extra: StoreDetail?) {
        binding.info.removeAllViews()
        if (packageName.isNotBlank()) {
            addInfo(getString(R.string.detail_package), packageName) {
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("package", packageName))
                android.widget.Toast.makeText(this, R.string.detail_package_copied, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
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

        extra.reviewPage?.distribution?.takeIf { it.isNotEmpty() }?.let { dist ->
            val total = dist.values.sum().coerceAtLeast(1)
            val text = (5 downTo 1).mapNotNull { star ->
                val count = dist[star] ?: return@mapNotNull null
                "$star★ ${count * 100 / total}%"
            }.joinToString("  ")
            addInfo(getString(R.string.detail_rating_mix), text)
        }
    }

    private fun addInfo(label: String, value: String, onClick: (() -> Unit)? = null) {
        if (value.isBlank()) return
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(5), 0, dp(5))

        val name = TextView(this)
        name.text = label
        name.textSize = 12f
        name.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        name.layoutParams = LinearLayout.LayoutParams(dp(78), LinearLayout.LayoutParams.WRAP_CONTENT)

        val content = TextView(this)
        content.text = value
        content.textSize = 14f
        content.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        if (onClick != null) {
            content.setTextColor(ContextCompat.getColor(this, R.color.primary))
            content.setOnClickListener { onClick() }
        } else {
            content.setTextColor(themeColor(android.R.attr.textColorPrimary))
        }
        Linkify.addLinks(content, Linkify.WEB_URLS)
        content.linksClickable = true

        row.addView(name)
        row.addView(content)
        binding.info.addView(row)
    }

    private fun applyReviews(page: ReviewPage?) {
        if (page == null) {
            binding.reviewsTitle.isVisible = reviews.isNotEmpty()
            binding.moreReviews.isVisible = false
            return
        }
        reviews.clear()
        reviews.addAll(page.reviews)
        reviewTotal = page.total

        binding.reviewsTitle.isVisible = reviews.isNotEmpty()
        binding.reviewsTitle.text = if (reviewTotal > 0) {
            getString(R.string.detail_reviews_count, reviewTotal)
        } else {
            getString(R.string.detail_reviews)
        }
        renderReviews()

        binding.moreReviews.isVisible = reviewTotal > 0
        binding.moreReviews.isEnabled = true
        binding.moreReviews.text = if (reviewTotal > 0) {
            getString(R.string.detail_view_all_reviews, reviewTotal)
        } else {
            getString(R.string.detail_show_all_reviews)
        }
    }

    private fun renderReviews() {
        binding.reviews.removeAllViews()
        val divider = ContextCompat.getColor(this, android.R.color.darker_gray)
        reviews.take(3).forEachIndexed { index, review ->
            if (index > 0) {
                val line = View(this)
                line.setBackgroundColor(divider)
                line.alpha = 0.25f
                line.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).apply { topMargin = dp(8) }
                binding.reviews.addView(line)
            }

            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            box.setPadding(0, dp(8), 0, dp(2))

            val header = TextView(this)
            val stars = "★".repeat(review.stars.coerceIn(0, 5))
            val like = if (review.likes > 0) "  ·  ${review.likes} 讚" else ""
            header.text = "${review.user}  $stars  ${review.date}$like"
            header.textSize = 12f
            header.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))

            val body = TextView(this)
            body.text = review.content
            body.textSize = 14f
            body.setLineSpacing(dp(3).toFloat(), 1f)
            body.setPadding(0, dp(4), 0, 0)

            box.addView(header)
            box.addView(body)
            binding.reviews.addView(box)
        }
    }

    private fun openImage(url: String) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val view = ImageView(this)
        view.scaleType = ImageView.ScaleType.FIT_CENTER
        view.setBackgroundColor(android.graphics.Color.BLACK)
        ThumbCache.load(url, view)
        view.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(view)
        dialog.show()
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

    private fun themeColor(attr: Int): Int {
        val typed = obtainStyledAttributes(intArrayOf(attr))
        val color = typed.getColor(0, android.graphics.Color.WHITE)
        typed.recycle()
        return color
    }
}
