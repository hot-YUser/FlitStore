package dev.yyblite.ui.common

import android.os.Handler
import android.os.Looper
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.yyblite.R
import dev.yyblite.core.store.StoreApp
import dev.yyblite.data.AppStatus
import dev.yyblite.download.DownloadPhase
import dev.yyblite.graph
import dev.yyblite.ui.Format
import dev.yyblite.ui.image.ThumbCache

private val mainHandler = Handler(Looper.getMainLooper())

/** 依顯示尺寸載入圖片；網路圖還沒到時先用已安裝 App 的圖示擋住，避免閃出預設圖。 */
@Composable
fun rememberImage(url: String?, sizePx: Int, fallbackPkg: String? = null): ImageBitmap? {
    val context = LocalContext.current
    var image by remember(url, fallbackPkg) {
        mutableStateOf(url?.let { ThumbCache.memory(it, sizePx) } ?: fallbackPkg?.let(ThumbCache::cachedInstalled))
    }
    DisposableEffect(url, sizePx, fallbackPkg) {
        var active = true
        if (url != null && ThumbCache.memory(url, sizePx) == null) {
            ThumbCache.load(context, url, sizePx) { bitmap -> mainHandler.post { if (active) image = bitmap } }
        } else if (url == null && fallbackPkg != null && image == null) {
            ThumbCache.loadInstalled(context, fallbackPkg) { bitmap -> mainHandler.post { if (active) image = bitmap } }
        }
        onDispose { active = false }
    }
    return image
}

@Composable
fun AppIcon(url: String?, pkg: String?, size: Dp, modifier: Modifier = Modifier) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val image = rememberImage(url, px, pkg)
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.26f))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center
    ) {
        if (image != null) {
            Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(
                painterResource(R.drawable.ic_android),
                contentDescription = null,
                modifier = Modifier.size(size * 0.5f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun Glyph(@DrawableRes id: Int, modifier: Modifier = Modifier, contentDescription: String? = null) =
    Icon(painterResource(id), contentDescription, modifier)

/** 讀取全域的更新與下載狀態，推導這個商店應用此刻該顯示的按鈕。 */
@Composable
fun rememberActionState(app: StoreApp): ActionState {
    val updates = LocalUpdates.current
    val download = LocalDownloads.current[app.pkg]
    val repository = LocalContext.current.graph.updates
    val status = remember(app, updates) { repository.statusOf(app, updates) }
    return actionStateOf(status, download)
}

@Composable
fun rememberStatus(app: StoreApp): AppStatus {
    val updates = LocalUpdates.current
    val repository = LocalContext.current.graph.updates
    return remember(app, updates) { repository.statusOf(app, updates) }
}

/**
 * 通用的應用列：圖示、名稱、副標、右側動作按鈕；下載中在副標下方顯示波浪進度條。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppRow(
    app: StoreApp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    rank: Int? = null,
    selected: Boolean? = null,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val context = LocalContext.current
    val actions = LocalActions.current
    val state = rememberActionState(app)
    val download = LocalDownloads.current[app.pkg]
    Row(
        modifier
            .fillMaxWidth()
            .background(if (selected == true) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (rank != null) {
            Text(
                "$rank",
                modifier = Modifier.width(28.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (rank <= 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box {
            AppIcon(app.iconUrl, app.pkg, 52.dp)
            if (selected == true) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(painterResource(R.drawable.ic_check), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(app.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = when {
                download?.phase == DownloadPhase.DOWNLOADING && download.speedBps > 0 ->
                    Format.speed(context, download.speedBps) + " · " + Format.size(context, download.total)
                download?.phase == DownloadPhase.FAILED -> dev.yyblite.platform.Problems.message(context, download.error)
                else -> supporting ?: summary(context, app)
            }
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = if (download?.phase == DownloadPhase.FAILED) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            AnimatedVisibility(download?.phase == DownloadPhase.DOWNLOADING) {
                LinearWavyProgressIndicator(
                    progress = { download?.fraction ?: 0f },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        when {
            selected != null -> Unit
            trailing != null -> trailing()
            else -> ActionButton(state, onClick = { actions.onAction(app, state) })
        }
    }
}

/** 已安裝但沒有商店資料的列（例如檢查失敗）。 */
@Composable
fun InstalledRow(
    pkg: String,
    label: String,
    supporting: String,
    onClick: () -> Unit,
    supportingColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(null, pkg, 52.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(supporting, style = MaterialTheme.typography.bodySmall, color = supportingColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

fun summary(context: android.content.Context, app: StoreApp): String = listOfNotNull(
    app.rating?.let { "★ " + Format.rating(it) },
    app.category,
    app.sizeBytes.takeIf { it > 0 }?.let { Format.size(context, it) }
).joinToString(" · ").ifBlank { app.developer.orEmpty() }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ActionButton(state: ActionState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    when (state) {
        ActionState.Queued, is ActionState.Downloading -> Box(modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (state is ActionState.Downloading && !state.indeterminate) {
                CircularWavyProgressIndicator(progress = { state.fraction }, modifier = Modifier.size(40.dp))
            } else {
                CircularWavyProgressIndicator(modifier = Modifier.size(40.dp))
            }
            IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
                Glyph(R.drawable.ic_stop, Modifier.size(18.dp), stringResource(R.string.action_cancel))
            }
        }
        ActionState.Working -> Box(modifier.size(48.dp), contentAlignment = Alignment.Center) {
            LoadingIndicator(Modifier.size(40.dp))
        }
        ActionState.Open -> FilledTonalButton(onClick = onClick, modifier = modifier.widthIn(min = 76.dp), shapes = ButtonDefaults.shapes()) {
            Text(stringResource(R.string.action_open))
        }
        ActionState.CertMismatch -> OutlinedButton(onClick = {}, enabled = false, modifier = modifier) {
            Text(stringResource(R.string.action_cert_mismatch))
        }
        else -> Button(onClick = onClick, modifier = modifier.widthIn(min = 76.dp), shapes = ButtonDefaults.shapes()) {
            Text(
                stringResource(
                    when (state) {
                        ActionState.Update -> R.string.action_update
                        ActionState.Retry -> R.string.action_retry
                        else -> R.string.action_install
                    }
                )
            )
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 20.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(action)
                Glyph(R.drawable.ic_chevron_right, Modifier.size(18.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        LoadingIndicator(Modifier.size(56.dp))
    }
}

@Composable
fun MessageBox(
    @DrawableRes icon: Int,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(painterResource(icon), null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body != null) {
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        if (action != null && onAction != null) {
            FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}

/** 載入失敗：離線與其他錯誤用不同圖示，附「重試」。 */
@Composable
fun ErrorBox(error: Throwable?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val offline = error is java.net.UnknownHostException || error is java.net.ConnectException
    MessageBox(
        icon = if (offline) R.drawable.ic_wifi_off else R.drawable.ic_error,
        title = dev.yyblite.platform.Problems.message(context, error),
        modifier = modifier,
        action = stringResource(R.string.action_retry),
        onAction = onRetry
    )
}

@Composable
fun InfoRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            label,
            modifier = Modifier.width(112.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

val ListPadding = PaddingValues(bottom = 24.dp)

@Composable
fun VerticalSpace(height: Dp) = Spacer(Modifier.height(height))
