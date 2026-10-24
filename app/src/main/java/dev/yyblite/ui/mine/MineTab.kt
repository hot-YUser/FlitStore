package dev.yyblite.ui.mine

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.yyblite.R
import dev.yyblite.data.ErrorLog
import dev.yyblite.data.InstallerKind
import dev.yyblite.download.ApkFiles
import dev.yyblite.graph
import dev.yyblite.install.InstallerStatus
import dev.yyblite.ui.Format
import dev.yyblite.ui.common.Glyph
import dev.yyblite.ui.common.LocalDownloads
import dev.yyblite.ui.common.LocalUpdates
import dev.yyblite.ui.nav.LocalNavigator
import dev.yyblite.ui.nav.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 我的：本機管理與設定的入口，一頁看完。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MineTab(bottom: PaddingValues) {
    val context = LocalContext.current
    val graph = context.graph
    val navigator = LocalNavigator.current
    val updates = LocalUpdates.current
    val downloads = LocalDownloads.current
    val logs by ErrorLog.entries.collectAsState()
    val status by produceState<InstallerStatus?>(null) { value = withContext(Dispatchers.IO) { graph.installer.status() } }
    val stored by produceState(0L, downloads.size) { value = withContext(Dispatchers.IO) { ApkFiles.totalBytes(context) } }
    val active = downloads.values.count { it.active }
    val ignored by produceState(0, updates) { value = withContext(Dispatchers.IO) { graph.ignores.entries().size } }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_mine)) }) }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = bottom) {
            item { InstallerCard(status) { navigator.push(Route.Settings) } }
            item {
                Entry(
                    R.drawable.ic_apps,
                    stringResource(R.string.mine_installed),
                    pluralStringResource(R.plurals.apps_count, updates.entries.size, updates.entries.size)
                ) { navigator.push(Route.Installed) }
            }
            item {
                Entry(
                    R.drawable.ic_download_done,
                    stringResource(R.string.mine_downloads),
                    when {
                        active > 0 -> pluralStringResource(R.plurals.downloads_active, active, active)
                        stored > 0 -> stringResource(R.string.mine_downloads_stored, Format.size(context, stored))
                        else -> stringResource(R.string.downloads_empty)
                    }
                ) { navigator.push(Route.Downloads) }
            }
            item {
                Entry(
                    R.drawable.ic_notifications_off,
                    stringResource(R.string.mine_ignored),
                    pluralStringResource(R.plurals.apps_count, ignored, ignored)
                ) { navigator.push(Route.Ignored) }
            }
            item {
                Entry(R.drawable.ic_settings, stringResource(R.string.mine_settings), stringResource(R.string.mine_settings_hint)) {
                    navigator.push(Route.Settings)
                }
            }
            item {
                Entry(
                    R.drawable.ic_bug_report,
                    stringResource(R.string.mine_diagnostics),
                    pluralStringResource(R.plurals.log_count, logs.size, logs.size)
                ) { navigator.push(Route.Diagnostics) }
            }
            item {
                Entry(R.drawable.ic_info, stringResource(R.string.mine_about), stringResource(R.string.about_version, appVersion(context))) {
                    navigator.push(Route.About)
                }
            }
        }
    }
}

@Composable
private fun InstallerCard(status: InstallerStatus?, onClick: () -> Unit) {
    val label = when (status?.effective) {
        InstallerKind.ROOT -> stringResource(R.string.installer_root)
        InstallerKind.SHIZUKU -> stringResource(R.string.installer_shizuku)
        InstallerKind.EXTERNAL -> stringResource(R.string.installer_external)
        null -> ""
    }
    val detail = when (status?.effective) {
        InstallerKind.ROOT, InstallerKind.SHIZUKU -> stringResource(R.string.installer_silent)
        InstallerKind.EXTERNAL -> stringResource(R.string.installer_confirm_each)
        null -> stringResource(R.string.status_detecting)
    }
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.secondary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painterResource(if (status?.effective == InstallerKind.EXTERNAL) R.drawable.ic_install_mobile else R.drawable.ic_bolt),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondary
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.installer_current, label),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Glyph(R.drawable.ic_chevron_right)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Entry(@DrawableRes icon: Int, title: String, supporting: String, onClick: () -> Unit) {
    ListItem(
        onClick = onClick,
        supportingContent = { Text(supporting) },
        leadingContent = { Glyph(icon) },
        trailingContent = { Glyph(R.drawable.ic_chevron_right) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.padding(horizontal = 4.dp)
    ) { Text(title) }
}

fun appVersion(context: android.content.Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
