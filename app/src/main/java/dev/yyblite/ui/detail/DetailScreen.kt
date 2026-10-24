package dev.yyblite.ui.detail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.icu.text.CompactDecimalFormat
import androidx.core.net.toUri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import dev.yyblite.R
import dev.yyblite.core.store.Review
import dev.yyblite.core.store.ReviewPage
import dev.yyblite.core.store.StoreApp
import dev.yyblite.core.store.StoreDetail
import dev.yyblite.core.update.Verdict
import dev.yyblite.data.AppStatus
import dev.yyblite.download.DownloadPhase
import dev.yyblite.download.DownloadState
import dev.yyblite.graph
import dev.yyblite.platform.Device
import dev.yyblite.platform.Problems
import dev.yyblite.ui.Format
import dev.yyblite.ui.common.ActionState
import dev.yyblite.ui.common.AppIcon
import dev.yyblite.ui.common.ErrorBox
import dev.yyblite.ui.common.Glyph
import dev.yyblite.ui.common.InfoRow
import dev.yyblite.ui.common.LoadingBox
import dev.yyblite.ui.common.LocalActions
import dev.yyblite.ui.common.LocalDownloads
import dev.yyblite.ui.common.LocalUpdates
import dev.yyblite.ui.common.MessageBox
import dev.yyblite.ui.common.SectionHeader
import dev.yyblite.ui.common.SubScreen
import dev.yyblite.ui.common.actionStateOf
import dev.yyblite.ui.common.rememberImage
import dev.yyblite.ui.nav.LocalNavigator
import dev.yyblite.ui.nav.Route
import dev.yyblite.ui.nav.sharedIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun DetailScreen(route: Route.Detail) {
    val context = LocalContext.current
    val graph = context.graph
    val state by remember(route.pkg) { graph.store.detail(route.pkg, route.seed) }.collectAsState()
    val app = state.app ?: route.seed
    val updates = LocalUpdates.current
    // 簽章比對要讀 PackageManager，放到背景；結果出來前先用不含 IPC 的判定。
    val quick = app?.let { graph.updates.statusOf(it, updates) }
    val precise by produceState<AppStatus?>(null, app, updates) {
        value = app?.let { withContext(Dispatchers.IO) { graph.updates.statusOfBlocking(it) } }
    }
    val status = precise ?: quick ?: AppStatus.NotInstalled
    val download = LocalDownloads.current[route.pkg]
    var menu by remember { mutableStateOf(false) }

    SubScreen(
        title = "",
        actions = {
            if (app != null) {
                Box {
                    IconButton(onClick = { menu = true }) {
                        Glyph(R.drawable.ic_more_vert, contentDescription = stringResource(R.string.action_more))
                    }
                    DetailMenu(app, status, expanded = menu, onDismiss = { menu = false })
                }
            }
        }
    ) { padding ->
        when {
            app == null && state.loadingApp -> Box(Modifier.padding(padding)) { LoadingBox() }
            app == null && state.notInStore -> Box(Modifier.padding(padding)) {
                MessageBox(R.drawable.ic_block, stringResource(R.string.detail_not_in_store))
            }
            app == null -> Box(Modifier.padding(padding)) {
                ErrorBox(state.appError, { graph.store.detail(route.pkg, route.seed, force = true) })
            }
            else -> DetailContent(app, state.detail, status, download, route.origin, padding)
        }
    }
}

@Composable
private fun DetailMenu(app: StoreApp, status: AppStatus, expanded: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val actions = LocalActions.current
    val verdict = (status as? AppStatus.Installed)?.verdict
    val ignored = graph.ignores.has(app.pkg)
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (verdict?.offered == true) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ignore_once)) },
                leadingIcon = { Glyph(R.drawable.ic_visibility_off) },
                onClick = { graph.updates.ignoreOnce(app); onDismiss() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ignore_forever)) },
                leadingIcon = { Glyph(R.drawable.ic_notifications_off) },
                onClick = { graph.updates.ignoreForever(app.pkg); onDismiss() }
            )
        }
        if (ignored) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ignore_clear)) },
                leadingIcon = { Glyph(R.drawable.ic_restart_alt) },
                onClick = { graph.updates.clearIgnore(app.pkg); onDismiss() }
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.detail_copy_package)) },
            leadingIcon = { Glyph(R.drawable.ic_content_copy) },
            onClick = {
                context.getSystemService<ClipboardManager>()?.setPrimaryClip(ClipData.newPlainText("package", app.pkg))
                actions.message(R.string.detail_package_copied)
                onDismiss()
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.detail_open_web)) },
            leadingIcon = { Glyph(R.drawable.ic_open_in_new) },
            onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, "https://sj.qq.com/appdetail/${app.pkg}".toUri()))
                }
                onDismiss()
            }
        )
    }
}

@Composable
private fun DetailContent(
    app: StoreApp,
    detail: StoreDetail?,
    status: AppStatus,
    download: DownloadState?,
    origin: String,
    padding: PaddingValues
) {
    val navigator = LocalNavigator.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item(key = "header") { Header(app, origin) }
        item(key = "stats") { Stats(app, detail) }
        item(key = "action") { PrimaryAction(app, status, download) }
        item(key = "status") { StatusLine(app, status, download) }
        if (app.screenshots.isNotEmpty()) {
            item(key = "shots") { Screenshots(app.screenshots) }
        }
        val description = detail?.description ?: app.editorIntro
        if (!description.isNullOrBlank()) {
            item(key = "about") { About(app.editorIntro.takeIf { it != description }, description) }
        }
        detail?.reviewPage?.takeIf { it.reviews.isNotEmpty() || it.score != null }?.let { page ->
            item(key = "ratings") {
                Ratings(page) { navigator.push(Route.Reviews(app.pkg, app.name)) }
            }
        }
        val sameDeveloper = detail?.sameDeveloper.orEmpty()
        if (sameDeveloper.isNotEmpty()) {
            item(key = "dev") { SameDeveloper(app, sameDeveloper) }
        }
        item(key = "info") { Info(app, detail) }
    }
}

@Composable
private fun Header(app: StoreApp, origin: String) {
    val navigator = LocalNavigator.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(app.iconUrl, app.pkg, 96.dp, Modifier.sharedIcon("icon:${app.pkg}:$origin"))
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(app.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 2)
            val developer = app.developer ?: app.operator
            if (developer != null) {
                Text(
                    developer,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { navigator.push(Route.Developer(developer, app.pkg)) }
                )
            }
            if (app.official) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(R.drawable.ic_verified_filled),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.detail_official), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun Stats(app: StoreApp, detail: StoreDetail?) {
    val context = LocalContext.current
    val cells = buildList {
        app.rating?.let {
            val count = if (app.ratingCount > 0) {
                pluralStringResource(R.plurals.rating_count, app.ratingCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), compact(app.ratingCount))
            } else {
                stringResource(R.string.detail_rating)
            }
            add(Format.rating(it) + " ★" to count)
        }
        app.downloadDesc?.let { add(it to stringResource(R.string.detail_downloads)) }
        app.sizeBytes.takeIf { it > 0 }?.let { add(Format.size(context, it) to stringResource(R.string.detail_size)) }
        (detail?.category ?: app.category)?.let { add(it to stringResource(R.string.detail_category)) }
    }
    if (cells.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        cells.forEachIndexed { index, (value, label) ->
            if (index > 0) {
                Box(Modifier.width(1.dp).fillMaxHeight(0.6f).background(MaterialTheme.colorScheme.outlineVariant))
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

/** 主按鈕：依狀態在 安裝／更新 → 進度＋取消 → 安裝中 → 開啟 之間變化。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PrimaryAction(app: StoreApp, status: AppStatus, download: DownloadState?) {
    val context = LocalContext.current
    val actions = LocalActions.current
    val state = actionStateOf(status, download)
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).animateContentSize()) {
        when (state) {
            ActionState.Queued, is ActionState.Downloading -> Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val label = if (state is ActionState.Downloading && !state.indeterminate) {
                        stringResource(R.string.status_downloading_percent, (state.fraction * 100).toInt())
                    } else {
                        stringResource(R.string.status_queued)
                    }
                    Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (download != null && download.speedBps > 0) {
                        val eta = if (download.total > download.bytes) (download.total - download.bytes) / download.speedBps else 0
                        Text(
                            Format.speed(context, download.speedBps) + " · " + Format.eta(eta),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (state is ActionState.Downloading && !state.indeterminate) {
                    LinearWavyProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
                } else {
                    LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
                }
                OutlinedButton(onClick = { actions.cancel(app.pkg) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
            ActionState.Working -> Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                LoadingIndicator(Modifier.size(40.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(if (download?.phase == DownloadPhase.VERIFYING) R.string.status_verifying else R.string.status_installing),
                    style = MaterialTheme.typography.titleSmall
                )
            }
            ActionState.Open -> FilledTonalButton(
                onClick = { actions.onAction(app, state) },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) { Text(stringResource(R.string.action_open), style = MaterialTheme.typography.titleMedium) }
            ActionState.CertMismatch -> OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text(stringResource(R.string.action_cert_mismatch))
            }
            else -> Button(
                onClick = { actions.onAction(app, state) },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Glyph(if (state == ActionState.Ready) R.drawable.ic_install_mobile else R.drawable.ic_download, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                val label = when (state) {
                    ActionState.Update -> R.string.action_update
                    ActionState.Retry -> R.string.action_retry
                    else -> R.string.action_install
                }
                val size = app.sizeBytes.takeIf { it > 0 && state != ActionState.Ready }?.let { " · " + Format.size(context, it) }.orEmpty()
                Text(stringResource(label) + size, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun StatusLine(app: StoreApp, status: AppStatus, download: DownloadState?) {
    val context = LocalContext.current
    val (text, error) = when {
        download?.phase == DownloadPhase.FAILED -> Problems.message(context, download.error) to true
        status is AppStatus.Installed -> when (val verdict = status.verdict) {
            Verdict.Available -> stringResource(R.string.version_arrow, status.app.versionName, app.versionName) to false
            is Verdict.NewBuild -> stringResource(R.string.version_new_build, status.app.versionName, verdict.versionCode) to false
            Verdict.Ignored -> stringResource(R.string.detail_ignored, app.versionName) to false
            Verdict.CertMismatch -> stringResource(R.string.detail_cert_mismatch) to true
            Verdict.Regression -> stringResource(R.string.detail_regression) to false
            Verdict.UpToDate -> stringResource(R.string.detail_up_to_date, status.app.versionName) to false
        }
        app.versionName.isNotBlank() -> stringResource(R.string.detail_version_line, app.versionName) to false
        else -> return
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

@Composable
private fun Screenshots(urls: List<String>) {
    val navigator = LocalNavigator.current
    val px = with(LocalDensity.current) { 220.dp.roundToPx() }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(urls) { index, url ->
            val image = rememberImage(url, px)
            val width = image?.let { 220.dp * (it.width.toFloat() / it.height) } ?: 124.dp
            Box(
                Modifier
                    .height(220.dp)
                    .width(width)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable { navigator.push(Route.Screenshots(urls, index)) }
            ) {
                if (image != null) {
                    Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
        }
    }
}

@Composable
private fun About(slogan: String?, description: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    SectionHeader(stringResource(R.string.detail_about))
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).animateContentSize()) {
        if (slogan != null) {
            Text(slogan, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 8.dp))
        }
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 5,
            overflow = TextOverflow.Ellipsis
        )
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
            Text(stringResource(if (expanded) R.string.action_collapse else R.string.action_expand))
        }
    }
}

@Composable
private fun Ratings(page: ReviewPage, onSeeAll: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(
            stringResource(R.string.detail_ratings),
            action = if (page.total > 0) stringResource(R.string.action_see_all) else null,
            onAction = onSeeAll
        )
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            page.score?.let { score ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
                    Text(Format.rating(score), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.SemiBold)
                    Text("★".repeat(score.toInt().coerceIn(0, 5)), color = MaterialTheme.colorScheme.primary)
                    if (page.total > 0) {
                        Text(
                            pluralStringResource(R.plurals.reviews_count, page.total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), compact(page.total)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
            }
            val total = page.distribution.values.sum().coerceAtLeast(1)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (star in 5 downTo 1) {
                    val fraction = (page.distribution[star] ?: 0L).toFloat() / total
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$star", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(14.dp))
                        Box(
                            Modifier
                                .weight(1f)
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(fraction)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                }
            }
        }
        page.reviews.take(3).forEach { ReviewItem(it) }
    }
}

@Composable
fun ReviewItem(review: Review) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                review.user.ifBlank { stringResource(R.string.reviews_anonymous) },
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "★".repeat(review.stars.coerceIn(0, 5)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.weight(1f))
            Text(Format.date(review.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(review.content, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        if (review.likes > 0) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(R.drawable.ic_thumb_up_filled),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
                Text(compact(review.likes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

@Composable
private fun SameDeveloper(app: StoreApp, apps: List<StoreApp>) {
    val navigator = LocalNavigator.current
    val developer = app.developer ?: app.operator ?: ""
    val origin = "dev:${app.pkg}"
    SectionHeader(
        stringResource(R.string.detail_same_developer),
        action = stringResource(R.string.action_see_all),
        onAction = { navigator.push(Route.Developer(developer, app.pkg)) }
    )
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(apps, key = { it.pkg }) { other ->
            Column(
                Modifier
                    .width(80.dp)
                    .clickable { navigator.push(Route.Detail(other.pkg, other, origin)) }
                    .padding(vertical = 6.dp)
            ) {
                AppIcon(other.iconUrl, other.pkg, 80.dp, Modifier.sharedIcon("icon:${other.pkg}:$origin"))
                Spacer(Modifier.height(6.dp))
                Text(other.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Info(app: StoreApp, detail: StoreDetail?) {
    val context = LocalContext.current
    val actions = LocalActions.current
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        SectionHeader(stringResource(R.string.detail_info))
        if (app.versionName.isNotBlank()) InfoRow(stringResource(R.string.detail_version), app.versionName)
        detail?.updateTimeSec?.takeIf { it > 0 }?.let { InfoRow(stringResource(R.string.detail_updated), Format.date(it)) }
        app.developer?.let { InfoRow(stringResource(R.string.detail_developer), it) }
        (detail?.operator ?: app.operator)?.let { InfoRow(stringResource(R.string.detail_operator), it) }
        app.icp?.let { InfoRow(stringResource(R.string.detail_icp), it) }
        if (app.detailed) {
            InfoRow(
                stringResource(R.string.detail_abi),
                stringResource(if (Device.abiTag(app) == "arm64") R.string.detail_abi64 else R.string.detail_abi32)
            )
        }
        InfoRow(stringResource(R.string.detail_package), app.pkg) {
            context.getSystemService<ClipboardManager>()?.setPrimaryClip(ClipData.newPlainText("package", app.pkg))
            actions.message(R.string.detail_package_copied)
        }
    }
}

private fun compact(value: Long): String =
    CompactDecimalFormat.getInstance(Locale.getDefault(), CompactDecimalFormat.CompactStyle.SHORT).format(value)
