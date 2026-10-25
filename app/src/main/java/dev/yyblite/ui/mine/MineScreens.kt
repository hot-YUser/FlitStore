@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.yyblite.ui.mine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import android.os.Build
import android.provider.Settings
import android.view.ViewTreeObserver
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.getSystemService
import dev.yyblite.R
import dev.yyblite.core.update.Verdict
import dev.yyblite.data.DexoptMode
import dev.yyblite.data.EntryStatus
import dev.yyblite.data.ErrorLog
import dev.yyblite.data.InstallerMode
import dev.yyblite.data.Settings as AppSettings
import dev.yyblite.data.UpdateEntry
import dev.yyblite.download.ApkFiles
import dev.yyblite.download.DownloadPhase
import dev.yyblite.download.DownloadState
import dev.yyblite.graph
import dev.yyblite.install.InstallerStatus
import dev.yyblite.install.ShizukuInstaller
import dev.yyblite.platform.Problems
import dev.yyblite.ui.Format
import dev.yyblite.ui.HomeTab
import dev.yyblite.ui.common.AppIcon
import dev.yyblite.ui.common.Glyph
import dev.yyblite.ui.common.InstalledRow
import dev.yyblite.ui.common.LocalActions
import dev.yyblite.ui.common.LocalDownloads
import dev.yyblite.ui.common.LocalUpdates
import dev.yyblite.ui.common.MessageBox
import dev.yyblite.ui.common.SectionHeader
import dev.yyblite.ui.common.SubScreen
import dev.yyblite.ui.nav.LocalNavigator
import dev.yyblite.ui.nav.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 已安裝應用與各自的商店狀態，可依名稱或套件名篩選。 */
@Composable
fun InstalledScreen() {
    val navigator = LocalNavigator.current
    val updates = LocalUpdates.current
    var filter by remember { mutableStateOf("") }
    val entries = updates.all.filter {
        filter.isBlank() || it.installed.label.contains(filter, ignoreCase = true) || it.installed.pkg.contains(filter, ignoreCase = true)
    }
    SubScreen(stringResource(R.string.mine_installed)) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = { Text(stringResource(R.string.installed_filter)) },
                    leadingIcon = { Glyph(R.drawable.ic_search) },
                    singleLine = true,
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            items(entries, key = { it.installed.pkg }) { entry ->
                InstalledRow(
                    pkg = entry.installed.pkg,
                    label = entry.installed.label,
                    supporting = statusLine(entry),
                    onClick = { navigator.push(Route.Detail(entry.installed.pkg, entry.store, "installed")) }
                )
            }
        }
    }
}

@Composable
private fun statusLine(entry: UpdateEntry): String {
    val version = entry.installed.versionName
    val state = when {
        entry.status == EntryStatus.NOT_IN_STORE -> stringResource(R.string.state_not_in_store)
        entry.status == EntryStatus.FAILED -> stringResource(R.string.state_check_failed)
        entry.store == null -> stringResource(R.string.state_pending)
        else -> when (entry.verdict) {
            Verdict.Available, is Verdict.NewBuild -> stringResource(R.string.state_update, entry.store.versionName)
            Verdict.Ignored -> stringResource(R.string.state_ignored)
            Verdict.CertMismatch -> stringResource(R.string.state_cert_mismatch)
            Verdict.Regression -> stringResource(R.string.state_regression)
            else -> stringResource(R.string.state_latest)
        }
    }
    return "$version · $state"
}

/** 下載管理：進行中的項目，以及已下載但還沒安裝的 APK。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DownloadsScreen() {
    val context = LocalContext.current
    val graph = context.graph
    val actions = LocalActions.current
    val downloads = LocalDownloads.current
    var version by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    val files by produceState(emptyList<File>(), version, downloads.size) {
        value = withContext(Dispatchers.IO) { ApkFiles.list(context) }
    }
    val tracked = downloads.values.sortedBy { it.name.lowercase() }
    val trackedPkgs = tracked.mapTo(HashSet()) { it.pkg }
    val loose = files.filter { ApkFiles.packageOf(it) !in trackedPkgs }

    SubScreen(
        stringResource(R.string.mine_downloads),
        actions = {
            if (files.isNotEmpty()) {
                IconButton(onClick = { confirmClear = true }) {
                    Glyph(R.drawable.ic_delete, contentDescription = stringResource(R.string.downloads_clear))
                }
            }
        }
    ) { padding ->
        if (tracked.isEmpty() && loose.isEmpty()) {
            Column(Modifier.padding(padding)) {
                MessageBox(R.drawable.ic_download_done, stringResource(R.string.downloads_empty))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                items(tracked, key = { "t:" + it.pkg }) { state ->
                    DownloadRow(state)
                }
                if (loose.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.downloads_stored)) }
                    items(loose, key = { "f:" + it.path }) { file ->
                        val pkg = ApkFiles.packageOf(file)
                        val name = graph.cache.get(pkg)?.name ?: pkg
                        InstalledRow(
                            pkg = pkg,
                            label = name,
                            supporting = Format.size(context, file.length()) + " · " + Format.relative(context, file.lastModified()),
                            onClick = { actions.install(pkg, file) }
                        ) {
                            IconButton(onClick = {
                                file.delete()
                                version++
                            }) {
                                Glyph(R.drawable.ic_delete, contentDescription = stringResource(R.string.action_delete))
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.downloads_clear)) },
            text = { Text(stringResource(R.string.downloads_clear_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    downloads.values.filterNot { it.active }.forEach { graph.downloads.dismiss(it.pkg, deleteFile = false) }
                    ApkFiles.deleteAll(context)
                    version++
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DownloadRow(state: DownloadState) {
    val context = LocalContext.current
    val graph = context.graph
    val actions = LocalActions.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(state.iconUrl, state.pkg, 52.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(state.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = when (state.phase) {
                DownloadPhase.QUEUED -> stringResource(R.string.status_queued)
                DownloadPhase.DOWNLOADING -> {
                    val eta = if (state.speedBps > 0 && state.total > state.bytes) (state.total - state.bytes) / state.speedBps else 0
                    "${Format.speed(context, state.speedBps)} · ${Format.size(context, state.bytes)} / ${Format.size(context, state.total)} · ${Format.eta(eta)}"
                }
                DownloadPhase.VERIFYING -> stringResource(R.string.status_verifying)
                DownloadPhase.INSTALLING -> stringResource(R.string.status_installing)
                DownloadPhase.READY -> stringResource(R.string.status_ready)
                DownloadPhase.FAILED -> Problems.message(context, state.error)
            }
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.phase == DownloadPhase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
            if (state.phase == DownloadPhase.DOWNLOADING) {
                LinearWavyProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        when (state.phase) {
            DownloadPhase.QUEUED, DownloadPhase.DOWNLOADING -> IconButton(onClick = { actions.cancel(state.pkg) }) {
                Glyph(R.drawable.ic_close, contentDescription = stringResource(R.string.action_cancel))
            }
            DownloadPhase.READY -> FilledTonalButton(onClick = { actions.install(state.pkg) }) { Text(stringResource(R.string.action_install)) }
            DownloadPhase.FAILED -> IconButton(onClick = { graph.downloads.dismiss(state.pkg, deleteFile = true) }) {
                Glyph(R.drawable.ic_delete, contentDescription = stringResource(R.string.action_delete))
            }
            else -> Unit
        }
    }
}

/** 已忽略的更新，可逐一取消。 */
@Composable
fun IgnoredScreen() {
    val context = LocalContext.current
    val graph = context.graph
    val updates = LocalUpdates.current
    var version by remember { mutableIntStateOf(0) }
    val entries = remember(version) { graph.ignores.entries().sortedBy { updates.entries[it.pkg]?.installed?.label ?: it.pkg } }
    SubScreen(stringResource(R.string.mine_ignored)) { padding ->
        if (entries.isEmpty()) {
            Column(Modifier.padding(padding)) { MessageBox(R.drawable.ic_notifications_off, stringResource(R.string.ignored_empty)) }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                items(entries, key = { it.pkg }) { entry ->
                    InstalledRow(
                        pkg = entry.pkg,
                        label = updates.entries[entry.pkg]?.installed?.label ?: entry.pkg,
                        supporting = if (entry.forever) stringResource(R.string.ignored_forever)
                        else stringResource(R.string.ignored_version, entry.version.orEmpty()),
                        onClick = {}
                    ) {
                        TextButton(onClick = {
                            graph.updates.clearIgnore(entry.pkg)
                            version++
                        }) { Text(stringResource(R.string.ignore_clear)) }
                    }
                }
            }
        }
    }
}

/** 設定：安裝方式、極速安裝、背景自動更新、懸浮視窗授權、語言。 */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val graph = context.graph
    val settings = graph.settings
    val actions = LocalActions.current
    var refresh by remember { mutableIntStateOf(0) }
    OnWindowFocus { refresh++ }
    val status by produceState<InstallerStatus?>(null, refresh) { value = withContext(Dispatchers.IO) { graph.installer.status() } }
    var mode by remember { mutableStateOf(settings.installerMode) }
    var fast by remember { mutableStateOf(settings.dexoptMode == DexoptMode.FAST) }
    var auto by remember { mutableStateOf(settings.autoUpdate) }
    var startTab by remember { mutableStateOf(settings.startTab) }
    var pickStart by remember { mutableStateOf(false) }
    val overlay = remember(refresh) { Settings.canDrawOverlays(context) }

    SubScreen(stringResource(R.string.mine_settings)) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item { SectionHeader(stringResource(R.string.settings_install)) }
            val modes = listOf(
                InstallerMode.AUTO to R.string.installer_auto,
                InstallerMode.ROOT to R.string.installer_root,
                InstallerMode.SHIZUKU to R.string.installer_shizuku,
                InstallerMode.EXTERNAL to R.string.installer_external
            )
            items(modes, key = { it.first.name }) { (option, label) ->
                RadioRow(
                    label = label,
                    supporting = modeStatus(option, status),
                    selected = mode == option,
                    onClick = {
                        mode = option
                        settings.installerMode = option
                        refresh++
                        if (option == InstallerMode.SHIZUKU && status?.shizukuAlive == true && status?.shizukuGranted == false) {
                            ShizukuInstaller.requestPermission()
                        }
                    }
                )
            }
            if (status?.shizukuAlive == true && status?.shizukuGranted == false) {
                item {
                    TextButton(onClick = { ShizukuInstaller.requestPermission() }, modifier = Modifier.padding(start = 56.dp)) {
                        Text(stringResource(R.string.installer_request_shizuku))
                    }
                }
            }
            item {
                SwitchRow(R.string.settings_fast_install, R.string.settings_fast_install_hint, fast) {
                    fast = it
                    settings.dexoptMode = if (it) DexoptMode.FAST else DexoptMode.STANDARD
                }
            }
            item { SectionHeader(stringResource(R.string.settings_updates)) }
            item {
                SwitchRow(R.string.settings_auto_update, R.string.settings_auto_update_hint, auto) {
                    auto = it
                    settings.autoUpdate = it
                    if (it && status?.rootGranted != true && status?.shizukuGranted != true) {
                        actions.message(R.string.settings_auto_update_no_privilege)
                    }
                }
            }
            item {
                ListItem(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())
                        )
                    },
                    supportingContent = {
                        Text(stringResource(if (overlay) R.string.settings_overlay_on else R.string.settings_overlay_off))
                    },
                    trailingContent = { Glyph(R.drawable.ic_open_in_new) }
                ) { Text(stringResource(R.string.settings_overlay)) }
            }
            item { SectionHeader(stringResource(R.string.settings_general)) }
            item {
                ListItem(
                    onClick = { pickStart = true },
                    supportingContent = { Text(stringResource(startTabLabel(startTab))) },
                    leadingContent = { Glyph(R.drawable.ic_tab) }
                ) { Text(stringResource(R.string.settings_start_tab)) }
            }
            if (Build.VERSION.SDK_INT >= 33) {
                item {
                    ListItem(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            )
                        },
                        supportingContent = { Text(stringResource(R.string.settings_language_hint)) },
                        leadingContent = { Glyph(R.drawable.ic_language) },
                        trailingContent = { Glyph(R.drawable.ic_open_in_new) }
                    ) { Text(stringResource(R.string.settings_language)) }
                }
            }
        }
    }

    if (pickStart) {
        AlertDialog(
            onDismissRequest = { pickStart = false },
            title = { Text(stringResource(R.string.settings_start_tab)) },
            text = {
                Column {
                    START_TABS.forEach { value ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    startTab = value
                                    settings.startTab = value
                                    pickStart = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = startTab == value, onClick = null)
                            Spacer(Modifier.width(16.dp))
                            Text(stringResource(startTabLabel(value)), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickStart = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

private val START_TABS = HomeTab.entries.map { it.name } + AppSettings.START_LAST

private fun startTabLabel(value: String): Int =
    HomeTab.entries.firstOrNull { it.name == value }?.label ?: R.string.start_tab_last

@Composable
private fun modeStatus(mode: InstallerMode, status: InstallerStatus?): String = when (mode) {
    InstallerMode.AUTO -> stringResource(R.string.installer_auto_hint)
    InstallerMode.ROOT -> stringResource(
        when {
            status?.rootGranted == true -> R.string.installer_root_granted
            status?.rootBinary == true -> R.string.installer_root_detected
            else -> R.string.installer_root_missing
        }
    )
    InstallerMode.SHIZUKU -> stringResource(
        when {
            status?.shizukuGranted == true -> R.string.installer_shizuku_granted
            status?.shizukuAlive == true -> R.string.installer_shizuku_denied
            else -> R.string.installer_shizuku_missing
        }
    )
    InstallerMode.EXTERNAL -> stringResource(R.string.installer_external_hint)
}

@Composable
private fun RadioRow(@StringRes label: Int, supporting: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        selected = selected,
        onClick = onClick,
        supportingContent = { Text(supporting) },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        modifier = Modifier.padding(horizontal = 8.dp)
    ) { Text(stringResource(label)) }
}

@Composable
private fun SwitchRow(@StringRes title: Int, @StringRes hint: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        checked = checked,
        onCheckedChange = onChange,
        supportingContent = { Text(stringResource(hint)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) }
    ) { Text(stringResource(title)) }
}

/** 從系統設定頁回來時視窗重新取得焦點，借此刷新授權狀態，不需要額外的 lifecycle 函式庫。 */
@Composable
private fun OnWindowFocus(action: () -> Unit) {
    val view = LocalView.current
    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (focused) action() }
        view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        onDispose { view.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
    }
}

/** 診斷記錄：最近 100 筆錯誤，可複製回報。 */
@Composable
fun DiagnosticsScreen() {
    val context = LocalContext.current
    val actions = LocalActions.current
    val lines by ErrorLog.entries.collectAsState()
    SubScreen(
        stringResource(R.string.mine_diagnostics),
        actions = {
            if (lines.isNotEmpty()) {
                IconButton(onClick = {
                    context.getSystemService<ClipboardManager>()?.setPrimaryClip(ClipData.newPlainText("diagnostics", ErrorLog.text()))
                    actions.message(R.string.diagnostics_copied)
                }) { Glyph(R.drawable.ic_content_copy, contentDescription = stringResource(R.string.action_copy)) }
                IconButton(onClick = ErrorLog::clear) {
                    Glyph(R.drawable.ic_delete, contentDescription = stringResource(R.string.action_clear))
                }
            }
        }
    ) { padding ->
        if (lines.isEmpty()) {
            Column(Modifier.padding(padding)) { MessageBox(R.drawable.ic_check, stringResource(R.string.diagnostics_empty)) }
        } else {
            SelectionContainer {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                    items(lines.asReversed()) { line ->
                        Text(
                            line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 關於：版本與第三方授權。 */
@Composable
fun AboutScreen() {
    val context = LocalContext.current
    SubScreen(stringResource(R.string.mine_about)) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 自適應圖示不能直接給 painterResource，改用同一份前景向量自行組合。
                    Box(
                        Modifier
                            .size(88.dp)
                            .clip(RoundedCornerShape(26.dp))
                            .background(colorResource(R.color.brand)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painterResource(R.drawable.ic_launcher_foreground),
                            contentDescription = null,
                            modifier = Modifier.requiredSize(144.dp)
                        )
                    }
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.about_version, appVersion(context)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.about_tagline),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                }
            }
            item { SectionHeader(stringResource(R.string.about_licenses)) }
            val licenses = listOf(
                "Jetpack Compose · AndroidX" to "Apache License 2.0",
                "Material Symbols" to "Apache License 2.0",
                "Shizuku API" to "MIT License"
            )
            items(licenses) { (name, license) ->
                ListItem(supportingContent = { Text(license) }) { Text(name) }
            }
        }
    }
}
