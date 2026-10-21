package dev.yyblite

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.yyblite.data.Review
import dev.yyblite.data.StoreDetailClient
import dev.yyblite.databinding.ActivityReviewBinding
import dev.yyblite.databinding.ItemReviewBinding
import java.util.concurrent.Executors

class ReviewActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_PKG = "pkg"
        private const val EXTRA_NAME = "name"

        fun intent(ctx: Context, pkg: String, name: String): Intent =
            Intent(ctx, ReviewActivity::class.java)
                .putExtra(EXTRA_PKG, pkg)
                .putExtra(EXTRA_NAME, name)
    }

    private data class Filter(val label: String, val tagType: String, val sortType: String)

    private val filters = listOf(
        Filter("全部", "0", "commentListByHot"),
        Filter("最新", "10000", "commentListByTime"),
        Filter("好評", "10001", "commentListByHot"),
        Filter("中評", "10002", "commentListByHot"),
        Filter("差評", "10003", "commentListByHot")
    )

    private lateinit var binding: ActivityReviewBinding
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "review-io").apply { isDaemon = true }
    }
    private val adapter = ReviewAdapter()

    private lateinit var pkg: String
    private var current: Filter = filters[0]
    private var page = 0
    private var hasNext = true
    private var loading = false
    private var requestId = 0
    private var total = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityReviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        applyWindowInsets()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        supportActionBar?.title = getString(R.string.detail_reviews)

        pkg = intent.getStringExtra(EXTRA_PKG).orEmpty()
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        if (name.isNotBlank()) supportActionBar?.subtitle = name

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || loading || !hasNext) return
                val lm = recyclerView.layoutManager as LinearLayoutManager
                if (lm.findLastVisibleItemPosition() >= adapter.itemCount - 4) {
                    loadPage(page + 1, reset = false)
                }
            }
        })

        buildTabs()
        loadPage(0, reset = true)
    }

    private fun buildTabs() {
        binding.tabs.removeAllViews()
        val density = resources.displayMetrics.density
        filters.forEachIndexed { index, filter ->
            val chip = TextView(this)
            chip.text = filter.label
            chip.textSize = 13f
            chip.setPadding(
                (14 * density).toInt(), (6 * density).toInt(),
                (14 * density).toInt(), (6 * density).toInt()
            )
            chip.layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * density).toInt() }
            chip.setOnClickListener {
                if (current == filter) return@setOnClickListener
                current = filter
                styleTabs()
                page = 0
                hasNext = true
                total = 0
                binding.count.text = ""
                binding.empty.isVisible = false
                adapter.submitList(emptyList())
                loadPage(0, reset = true)
            }
            binding.tabs.addView(chip)
        }
        styleTabs()
    }

    private fun styleTabs() {
        val primary = ContextCompat.getColor(this, R.color.primary)
        val secondary = ContextCompat.getColor(this, android.R.color.darker_gray)
        for (i in 0 until binding.tabs.childCount) {
            val chip = binding.tabs.getChildAt(i) as TextView
            val selected = filters[i] == current
            chip.setTextColor(if (selected) primary else secondary)
            chip.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            chip.setBackgroundColor(
                if (selected) android.graphics.Color.argb(30, 30, 111, 217)
                else android.graphics.Color.TRANSPARENT
            )
        }
    }

    private fun loadPage(target: Int, reset: Boolean) {
        if (pkg.isBlank()) return
        if (loading && !reset) return
        val id = ++requestId
        loading = true
        binding.progress.isVisible = true
        val filter = current
        io.execute {
            val result = StoreDetailClient.fetchComments(
                pkg, target,
                tagType = filter.tagType,
                sortType = filter.sortType
            )
            runOnUiThread {
                if (id != requestId) return@runOnUiThread
                loading = false
                binding.progress.isVisible = false
                if (current != filter) return@runOnUiThread
                if (result == null) {
                    hasNext = false
                    binding.empty.isVisible = adapter.itemCount == 0
                    return@runOnUiThread
                }
                page = target
                hasNext = result.hasNext
                total = result.total
                val combined = if (reset) result.reviews
                else adapter.currentList + result.reviews
                adapter.submitList(combined)
                binding.count.text = if (total > 0) {
                    getString(R.string.reviews_count, total)
                } else if (combined.isNotEmpty()) {
                    getString(R.string.reviews_loaded, combined.size)
                } else {
                    ""
                }
                binding.empty.isVisible = combined.isEmpty()
            }
        }
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

    private class ReviewAdapter : ListAdapter<Review, ReviewAdapter.Holder>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemReviewBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

        inner class Holder(private val binding: ItemReviewBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(review: Review) {
                binding.user.text = review.user
                binding.stars.text = "★".repeat(review.stars.coerceIn(0, 5))
                binding.date.text = review.date
                binding.content.text = review.content
                binding.likes.isVisible = review.likes > 0
                binding.likes.text = "${review.likes} 讚"
            }
        }

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<Review>() {
                override fun areItemsTheSame(oldItem: Review, newItem: Review): Boolean =
                    oldItem.content == newItem.content && oldItem.user == newItem.user

                override fun areContentsTheSame(oldItem: Review, newItem: Review): Boolean =
                    oldItem == newItem
            }
        }
    }
}
