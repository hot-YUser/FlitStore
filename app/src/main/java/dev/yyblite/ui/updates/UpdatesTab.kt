package dev.yyblite.ui.updates

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.yyblite.R
import dev.yyblite.core.update.Verdict
import dev.yyblite.data.UpdateEntry
import dev.yyblite.graph
import dev.yyblite.platform.Problems
import dev.yyblite.ui.Format
import dev.yyblite.ui.common.AppRow
import dev.yyblite.ui.common.Glyph
import dev.yyblite.ui.common.InstalledRow
import dev.yyblite.ui.common.LoadingBox
import dev.yyblite.ui.common.LocalActions
import dev.yyblite.ui.common.LocalUpdates
import dev.yyblite.ui.common.MessageBox
import dev.yyblite.ui.common.SectionHeader
import dev.yyblite.ui.nav.LocalNavigator
import dev.yyblite.ui.nav.Route

/**
 * 更新：可更新清單、需要手動處理（簽章不符）、檢查失敗（可收合）。
 * 長按進入多選，頂部列換成「更新／忽略」動作。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpdatesTab(bottom: PaddingValues) {
    val context = LocalContext.current
    val repository = context.graph.updates
    val state = LocalUpdates.current
    val actions = LocalActions.current
    val navigator = LocalNavigator.current
    var picked by remember { mutableStateOf(emptySet<String>()) }
    var showFailed by rememberSaveable { mutableStateOf(false) }
    var pulling by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(state.checking) { if (!state.checking) pulling = false }

    val offered = state.offered
    // 已更新或被忽略的項目自動離開選取。
    val offeredPkgs = offered.mapTo(HashSet()) { it.installed.pkg }
    val selection = picked intersect offeredPkgs
    val selecting = selection.isNotEmpty()
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text(pluralStringResource(R.plurals.selected_count, selection.size, selection.size)) },
                    navigationIcon = {
                        IconButton(onClick = { picked = emptySet() }) {
                            Glyph(R.drawable.ic_close, contentDescription = stringResource(R.string.action_close))
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            actions.downloadAll(offered.filter { it.installed.pkg in selection }.mapNotNull { it.store })
                            picked = emptySet()
                        }) {
                            Glyph(R.drawable.ic_download, contentDescription = stringResource(R.string.action_update))
                        }
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Glyph(R.drawable.ic_more_vert, contentDescription = stringResource(R.string.action_more))
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.ignore_once)) },
                                    leadingIcon = { Glyph(R.drawable.ic_visibility_off) },
                                    onClick = {
                                        offered.filter { it.installed.pkg in selection }.forEach { entry ->
                                            entry.store?.let(repository::ignoreOnce)
                                        }
                                        picked = emptySet()
                                        menu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.ignore_forever)) },
                                    leadingIcon = { Glyph(R.drawable.ic_notifications_off) },
                                    onClick = {
                                        selection.forEach(repository::ignoreForever)
                                        picked = emptySet()
                                        menu = false
                                    }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(R.string.tab_updates))
                            val subtitle = when {
                                state.scanning -> stringResource(R.string.status_scanning)
                                state.checking -> stringResource(R.string.status_checking, state.progress, state.total)
                                state.checkedAt > 0 -> stringResource(R.string.status_checked_at, Format.relative(context, state.checkedAt))
                                else -> null
                            }
                            if (subtitle != null) {
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = repository::refresh, enabled = !state.checking && !state.scanning) {
                            Glyph(R.drawable.ic_refresh, contentDescription = stringResource(R.string.action_refresh))
                        }
                    },
                    scrollBehavior = scroll
                )
            }
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = pulling && (state.checking || state.scanning),
            onRefresh = {
                pulling = true
                repository.refresh()
            },
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = bottom) {
                if (state.checking && state.total > 0) {
                    item(key = "progress") {
                        LinearWavyProgressIndicator(
                            progress = { state.progress.toFloat() / state.total },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
                when {
                    !state.loaded -> item(key = "loading") { LoadingBox() }
                    offered.isEmpty() && state.blocked.isEmpty() && !state.checking -> item(key = "none") {
                        MessageBox(
                            icon = R.drawable.ic_check,
                            title = stringResource(R.string.updates_none_title),
                            body = pluralStringResource(R.plurals.updates_none_body, state.entries.size, state.entries.size)
                        )
                    }
                }
                if (offered.isNotEmpty()) {
                    item(key = "summary") {
                        SummaryCard(
                            count = offered.size,
                            bytes = offered.sumOf { it.store?.sizeBytes?.coerceAtLeast(0) ?: 0 },
                            onUpdateAll = { actions.downloadAll(offered.mapNotNull { it.store }) }
                        )
                    }
                    items(offered, key = { "o:" + it.installed.pkg }) { entry ->
                        UpdateRow(
                            entry = entry,
                            selected = if (selecting) entry.installed.pkg in selection else null,
                            onClick = {
                                if (selecting) picked = selection.toggle(entry.installed.pkg)
                                else navigator.push(Route.Detail(entry.installed.pkg, entry.store))
                            },
                            onLongClick = { picked = selection.toggle(entry.installed.pkg) }
                        )
                    }
                }
                if (state.blocked.isNotEmpty()) {
                    item(key = "blocked-h") { SectionHeader(stringResource(R.string.updates_blocked)) }
                    items(state.blocked, key = { "b:" + it.installed.pkg }) { entry ->
                        UpdateRow(entry, null, { navigator.push(Route.Detail(entry.installed.pkg, entry.store)) }, null)
                    }
                }
                if (state.failed.isNotEmpty()) {
                    item(key = "failed-h") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { showFailed = !showFailed }
                                .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.updates_failed, state.failed.size),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Glyph(if (showFailed) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
                        }
                    }
                    if (showFailed) {
                        items(state.failed, key = { "f:" + it.installed.pkg }) { entry ->
                            InstalledRow(
                                pkg = entry.installed.pkg,
                                label = entry.installed.label,
                                supporting = Problems.message(context, entry.error),
                                supportingColor = MaterialTheme.colorScheme.error,
                                onClick = { navigator.push(Route.Detail(entry.installed.pkg)) }
                            ) {
                                TextButton(onClick = { repository.recheck(entry.installed.pkg) }) {
                                    Text(stringResource(R.string.action_retry))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateRow(entry: UpdateEntry, selected: Boolean?, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val context = LocalContext.current
    val store = entry.store ?: return
    val versions = when (val verdict = entry.verdict) {
        is Verdict.NewBuild -> stringResource(R.string.version_new_build, entry.installed.versionName, verdict.versionCode)
        Verdict.CertMismatch -> stringResource(R.string.version_cert_mismatch, store.versionName)
        else -> stringResource(R.string.version_arrow, entry.installed.versionName, store.versionName)
    }
    val size = store.sizeBytes.takeIf { it > 0 }?.let { " · " + Format.size(context, it) }.orEmpty()
    AppRow(
        app = store,
        onClick = onClick,
        supporting = versions + size,
        selected = selected,
        onLongClick = onLongClick
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SummaryCard(count: Int, bytes: Long, onUpdateAll: () -> Unit) {
    val context = LocalContext.current
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    pluralStringResource(R.plurals.updates_available, count, count),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                if (bytes > 0) {
                    Text(
                        stringResource(R.string.updates_total_size, Format.size(context, bytes)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = onUpdateAll, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.MediumContentPadding) {
                Glyph(R.drawable.ic_download, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_update_all))
            }
        }
    }
}

private fun Set<String>.toggle(pkg: String): Set<String> = if (pkg in this) this - pkg else this + pkg
