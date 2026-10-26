package dev.yyblite

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import dev.yyblite.core.store.StoreClient
import dev.yyblite.core.store.StoreLink
import dev.yyblite.data.InstallerKind
import dev.yyblite.data.Settings as AppSettings
import dev.yyblite.download.DownloadState
import dev.yyblite.platform.AppVisibility
import dev.yyblite.platform.LauncherIcon
import dev.yyblite.ui.HomeScreen
import dev.yyblite.ui.HomeTab
import dev.yyblite.ui.common.AppActions
import dev.yyblite.ui.common.DialogState
import dev.yyblite.ui.common.LocalActions
import dev.yyblite.ui.common.LocalDownloads
import dev.yyblite.ui.common.LocalUpdates
import dev.yyblite.ui.detail.DetailScreen
import dev.yyblite.ui.detail.ScreenshotsScreen
import dev.yyblite.ui.lists.AppsScreen
import dev.yyblite.ui.lists.DeveloperScreen
import dev.yyblite.ui.lists.ReviewsScreen
import dev.yyblite.ui.mine.AboutScreen
import dev.yyblite.ui.mine.DiagnosticsScreen
import dev.yyblite.ui.mine.DownloadsScreen
import dev.yyblite.ui.mine.IgnoredScreen
import dev.yyblite.ui.mine.InstalledScreen
import dev.yyblite.ui.mine.SettingsScreen
import dev.yyblite.ui.nav.LocalNavigator
import dev.yyblite.ui.nav.NavHost
import dev.yyblite.ui.nav.Navigator
import dev.yyblite.ui.nav.Route
import dev.yyblite.ui.search.SearchScreen
import dev.yyblite.ui.theme.YybTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 返回堆疊與目前分頁跨設定變更（旋轉、深色模式切換）保留。 */
class MainModel : ViewModel() {
    val navigator = Navigator()
    var tab by mutableStateOf(HomeTab.Explore)
    var initialized = false
}

class AppActivity : ComponentActivity() {
    private lateinit var model: MainModel

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[MainModel::class.java]
        if (!model.initialized) {
            model.tab = startTab(graph.settings)
            model.initialized = true
        }
        if (savedInstanceState == null) handle(intent)
        graph.updates.refreshIfStale()
        setContent { YybTheme { App(this, model) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.onStart()
    }

    override fun onStop() {
        AppVisibility.onStop()
        if (!isChangingConfigurations) LauncherIcon.applyPending(this)
        super.onStop()
    }

    /** 設定指定的分頁；選了「上次使用」就回到上次離開時的分頁。 */
    private fun startTab(settings: AppSettings): HomeTab {
        val name = if (settings.startTab == AppSettings.START_LAST) settings.lastTab else settings.startTab
        return HomeTab.entries.firstOrNull { it.name == name } ?: HomeTab.Explore
    }

    private fun handle(intent: Intent?) {
        if (intent?.getStringExtra(EXTRA_TAB) == TAB_UPDATES) {
            model.navigator.popToHome()
            model.tab = HomeTab.Updates
        }
        // 從應用寶網頁、market:// 等商店連結進來時直接開該應用的詳情頁；
        // 只帶內部編號的連結先在背景換成套件名稱，查不到就停在首頁。
        val link = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return
        val pkg = StoreLink.packageName(link)
        if (pkg != null) {
            model.navigator.push(Route.Detail(pkg))
            return
        }
        val appId = StoreLink.appId(link) ?: return
        lifecycleScope.launch {
            val resolved = withContext(Dispatchers.IO) { runCatching { StoreClient.packageForAppId(appId) }.getOrNull() }
            if (resolved != null) model.navigator.push(Route.Detail(resolved))
        }
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_UPDATES = "updates"
    }
}

@Composable
private fun App(activity: ComponentActivity, model: MainModel) {
    val graph = activity.graph
    val navigator = model.navigator
    val updates by graph.updates.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val dialogs = remember { DialogState() }

    // 下載狀態放進 SnapshotStateMap：每一列只讀自己的鍵，進度更新只重組那一列。
    val downloads = remember { mutableStateMapOf<String, DownloadState>() }
    LaunchedEffect(Unit) {
        graph.downloads.states.collect { next ->
            downloads.keys.retainAll(next.keys)
            next.forEach { (pkg, state) -> if (downloads[pkg] != state) downloads[pkg] = state }
        }
    }

    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        afterPermission?.invoke()
        afterPermission = null
    }
    val actions = remember {
        AppActions(activity, graph, snackbar, scope, dialogs) { proceed ->
            val needs = Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            if (needs) {
                afterPermission = proceed
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                proceed()
            }
        }
    }

    CompositionLocalProvider(
        LocalNavigator provides navigator,
        LocalUpdates provides updates,
        LocalDownloads provides downloads,
        LocalActions provides actions
    ) {
        // Surface 提供底色與 LocalContentColor，沒有自己 Scaffold 的頁面（搜尋）文字顏色才會正確。
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) {
            NavHost(navigator) { entry ->
                when (val route = entry.route) {
                    Route.Home -> HomeScreen(model.tab) {
                        model.tab = it
                        graph.settings.lastTab = it.name
                    }
                    is Route.Detail -> DetailScreen(route)
                    is Route.Reviews -> ReviewsScreen(route)
                    is Route.Apps -> AppsScreen(route)
                    is Route.Developer -> DeveloperScreen(route)
                    is Route.Screenshots -> ScreenshotsScreen(route)
                    Route.Search -> SearchScreen()
                    Route.Settings -> SettingsScreen()
                    Route.Downloads -> DownloadsScreen()
                    Route.Installed -> InstalledScreen()
                    Route.Ignored -> IgnoredScreen()
                    Route.Diagnostics -> DiagnosticsScreen()
                    Route.About -> AboutScreen()
                }
            }
            SnackbarHost(
                snackbar,
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = if (navigator.stack.size == 1) 80.dp else 0.dp)
            )
        }
        }

        dialogs.downgrade?.let { (info, confirm) ->
            AlertDialog(
                onDismissRequest = { dialogs.downgrade = null },
                title = { Text(stringResource(R.string.downgrade_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.downgrade_message,
                            info.installedVersion ?: "?", info.installedCode,
                            info.targetVersion ?: "?", info.targetCode
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        dialogs.downgrade = null
                        confirm()
                    }) { Text(stringResource(R.string.downgrade_confirm)) }
                },
                dismissButton = { TextButton(onClick = { dialogs.downgrade = null }) { Text(stringResource(R.string.action_cancel)) } }
            )
        }

        OverlayPrompt(activity, offered = updates.offered.isNotEmpty() && !updates.checking)
    }
}

/**
 * 只有使用外部安裝器、且真的有更新時才問一次：允許懸浮視窗後，
 * 背景下載完成也能直接開啟安裝器（Root／Shizuku 不需要）。
 */
@Composable
private fun OverlayPrompt(activity: ComponentActivity, offered: Boolean) {
    val graph = activity.graph
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(offered) {
        if (!offered || graph.settings.overlayAsked || Settings.canDrawOverlays(activity)) return@LaunchedEffect
        val status = withContext(Dispatchers.IO) { graph.installer.status() }
        if (status.effective == InstallerKind.EXTERNAL) {
            graph.settings.overlayAsked = true
            show = true
        }
    }
    if (!show) return
    AlertDialog(
        onDismissRequest = { show = false },
        title = { Text(stringResource(R.string.overlay_title)) },
        text = { Text(stringResource(R.string.overlay_message)) },
        confirmButton = {
            TextButton(onClick = {
                show = false
                activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${activity.packageName}".toUri()))
            }) { Text(stringResource(R.string.overlay_open)) }
        },
        dismissButton = { TextButton(onClick = { show = false }) { Text(stringResource(R.string.overlay_later)) } }
    )
}
