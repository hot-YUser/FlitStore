package dev.yyblite

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.yyblite.data.StoreApp
import dev.yyblite.data.StoreClient
import dev.yyblite.data.StoreDetailClient
import dev.yyblite.databinding.ActivityDeveloperBinding
import dev.yyblite.databinding.ItemAppBinding
import dev.yyblite.ui.ThumbCache
import java.util.concurrent.Executors

class DeveloperActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_NAME = "name"
        private const val EXTRA_DEVELOPER = "developer"
        private const val EXTRA_SEED = "seed"

        fun intent(ctx: Context, name: String, developer: Boolean, seedPkg: String? = null): Intent =
            Intent(ctx, DeveloperActivity::class.java)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_DEVELOPER, developer)
                .putExtra(EXTRA_SEED, seedPkg)
    }

    private lateinit var binding: ActivityDeveloperBinding
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dev-io").apply { isDaemon = true }
    }
    private val adapter = DevAdapter { app -> openDetail(app) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityDeveloperBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        applyWindowInsets()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        // 開發者與運營商共用同一搜尋端點，差別只在呼叫端的語意。
        supportActionBar?.title = getString(R.string.developer_title, name)
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        if (name.isBlank()) {
            binding.progress.isVisible = false
            binding.empty.isVisible = true
            return
        }

        val seed = intent.getStringExtra(EXTRA_SEED)
        io.execute {
            // 優先用商店詳情頁的 SameDeveloper 卡片（官方整理的清單）；
            // 沒有種子套件時才退回關鍵字搜尋並要求公司名完全相符。
            val fromCard = seed?.let { StoreDetailClient.fetch(it)?.sameDeveloper }.orEmpty()
            val list: List<StoreApp> = if (fromCard.isNotEmpty()) {
                fromCard.map { ref ->
                    StoreApp(
                        pkg = ref.pkg,
                        name = ref.name,
                        versionName = "",
                        versionCode = null,
                        apkUrl = "",
                        apkUrl64 = null,
                        apkMd5 = "",
                        signatureMd5 = null,
                        sizeBytes = -1,
                        sizeDesc = null,
                        iconUrl = ref.iconUrl,
                        developer = ref.developer,
                        downloadDesc = null,
                        rating = null,
                        screenshots = emptyList(),
                        editorIntro = null
                    )
                }
            } else {
                StoreClient.search(name).filter {
                    it.developer?.replace(" ", "") == name.replace(" ", "")
                }
            }
            runOnUiThread {
                binding.progress.isVisible = false
                adapter.submitList(list)
                binding.empty.isVisible = list.isEmpty()
            }
        }
    }

    private fun openDetail(app: StoreApp) {
        var installed: String? = null
        try {
            @Suppress("DEPRECATION")
            installed = packageManager.getPackageInfo(app.pkg, 0).versionName
        } catch (_: PackageManager.NameNotFoundException) {
        }
        startActivity(AppDetailActivity.intent(this, app, installed))
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

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private class DevAdapter(
        private val onClick: (StoreApp) -> Unit
    ) : ListAdapter<StoreApp, DevAdapter.Holder>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

        inner class Holder(private val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(app: StoreApp) {
                binding.name.text = app.name
                binding.subtitle.text = listOfNotNull(
                    app.versionName.takeIf { it.isNotBlank() }?.let { "版本 $it" },
                    app.developer
                ).joinToString(" · ")
                binding.check.isVisible = false
                binding.action.isVisible = false
                ThumbCache.load(app.iconUrl, binding.icon)
                binding.root.setOnClickListener { onClick(app) }
            }
        }

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<StoreApp>() {
                override fun areItemsTheSame(oldItem: StoreApp, newItem: StoreApp): Boolean =
                    oldItem.pkg == newItem.pkg

                override fun areContentsTheSame(oldItem: StoreApp, newItem: StoreApp): Boolean =
                    oldItem.versionName == newItem.versionName && oldItem.iconUrl == newItem.iconUrl
            }
        }
    }
}
