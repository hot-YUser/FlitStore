package dev.yyblite.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.yyblite.R
import dev.yyblite.data.StoreApp
import dev.yyblite.databinding.ItemAppBinding
import dev.yyblite.download.DownloadBus
import dev.yyblite.download.DownloadState
import dev.yyblite.download.Phase

data class RowItem(
    val store: StoreApp,
    val installedVersion: String?,
    val hasUpdate: Boolean,
    val checkFailed: Boolean = false,
    val cached: Boolean = false,
    val certMismatch: Boolean = false
)

class AppAdapter(
    private val onAction: (RowItem) -> Unit,
    private val onItemClick: (RowItem) -> Unit,
    private val onLongClick: (RowItem) -> Unit,
    private val onSelectionChanged: () -> Unit
) : ListAdapter<RowItem, AppAdapter.Holder>(DIFF) {

    private val states = HashMap<String, DownloadState>()
    private val selected = LinkedHashSet<String>()
    private var selectable = false

    fun setState(state: DownloadState) {
        states[state.pkg] = state
        val position = currentList.indexOfFirst { it.store.pkg == state.pkg }
        if (position >= 0) notifyItemChanged(position)
    }

    fun restoreStates() {
        states.clear()
        states.putAll(DownloadBus.snapshot())
        notifyDataSetChanged()
    }

    fun setSelectable(enabled: Boolean) {
        if (selectable == enabled) return
        selectable = enabled
        if (!enabled) selected.clear()
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun selectedPkgs(): Set<String> = LinkedHashSet(selected)

    fun clearSelection() {
        if (selected.isEmpty()) return
        selected.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: RowItem) {
            val ctx = binding.root.context
            binding.name.text = item.store.name

            val base = when {
                item.certMismatch -> ctx.getString(R.string.row_cert_mismatch, item.store.versionName)
                item.checkFailed -> ctx.getString(R.string.row_check_failed)
                item.installedVersion == null ->
                    ctx.getString(R.string.row_not_installed, item.store.versionName)
                item.hasUpdate ->
                    ctx.getString(R.string.row_local, item.installedVersion, item.store.versionName)
                else -> ctx.getString(R.string.row_store_only, item.store.versionName)
            }

            val state = states[item.store.pkg]
            val extra = if (state?.phase == Phase.RUNNING) {
                val eta = if (state.speedBps > 0 && state.total > state.bytes) {
                    (state.total - state.bytes) / state.speedBps
                } else {
                    0L
                }
                "\n" + Format.speed(state.speedBps) + " · 剩 " + Format.eta(eta)
            } else {
                ""
            }
            val cachedSuffix = if (item.cached && state?.phase != Phase.RUNNING && state?.phase != Phase.QUEUED) {
                ctx.getString(R.string.row_cached_suffix, "")
            } else {
                ""
            }
            binding.subtitle.text = base + extra + cachedSuffix

            ThumbCache.load(item.store.iconUrl, binding.icon)

            val label = when {
                state?.phase == Phase.RUNNING -> {
                    val percent = if (state.total > 0) ((state.bytes * 100) / state.total).toInt() else 0
                    ctx.getString(R.string.action_downloading, percent)
                }
                state?.phase == Phase.QUEUED -> ctx.getString(R.string.action_queued)
                state?.phase == Phase.DONE -> ctx.getString(R.string.action_install)
                state?.phase == Phase.FAILED -> ctx.getString(R.string.action_retry)
                item.checkFailed -> ctx.getString(R.string.action_retry)
                item.certMismatch -> ctx.getString(R.string.action_cert_mismatch)
                item.installedVersion == null -> ctx.getString(R.string.action_download)
                else -> ctx.getString(R.string.action_update)
            }

            binding.action.text = label
            binding.action.isEnabled = !item.certMismatch &&
                state?.phase != Phase.RUNNING && state?.phase != Phase.QUEUED
            binding.action.setOnClickListener { onAction(item) }
            binding.root.setOnClickListener { onItemClick(item) }
            binding.root.setOnLongClickListener {
                onLongClick(item)
                true
            }

            binding.check.isVisible = selectable
            binding.check.setOnCheckedChangeListener(null)
            binding.check.isChecked = selected.contains(item.store.pkg)
            binding.check.setOnCheckedChangeListener { _, checked ->
                if (checked) selected.add(item.store.pkg) else selected.remove(item.store.pkg)
                notifyItemChanged(bindingAdapterPosition)
                onSelectionChanged()
            }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<RowItem>() {
            override fun areItemsTheSame(oldItem: RowItem, newItem: RowItem): Boolean =
                oldItem.store.pkg == newItem.store.pkg

            override fun areContentsTheSame(oldItem: RowItem, newItem: RowItem): Boolean =
                oldItem.installedVersion == newItem.installedVersion &&
                    oldItem.hasUpdate == newItem.hasUpdate &&
                    oldItem.checkFailed == newItem.checkFailed &&
                    oldItem.cached == newItem.cached &&
                    oldItem.store.versionName == newItem.store.versionName &&
                    oldItem.store.iconUrl == newItem.store.iconUrl
        }
    }
}
