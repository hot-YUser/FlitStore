package dev.yyblite.ui.common

import android.app.Activity
import android.os.Handler
import android.os.Looper
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.staticCompositionLocalOf
import dev.yyblite.AppGraph
import dev.yyblite.R
import dev.yyblite.core.store.StoreApp
import dev.yyblite.core.update.Verdict
import dev.yyblite.data.AppStatus
import dev.yyblite.data.InstallerKind
import dev.yyblite.data.UpdatesState
import dev.yyblite.download.DownloadPhase
import dev.yyblite.download.DownloadState
import dev.yyblite.download.InstallFailedException
import dev.yyblite.install.DowngradeInfo
import dev.yyblite.install.InstallHandoff
import dev.yyblite.install.InstallOutcome
import dev.yyblite.platform.InstalledApps
import dev.yyblite.platform.Problems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors

/** 列表與詳情頁按鈕的狀態：由「本機狀態 + 下載狀態」唯一推導。 */
sealed interface ActionState {
    data object Install : ActionState
    data object Update : ActionState
    data object Open : ActionState
    data object Queued : ActionState
    data class Downloading(val fraction: Float, val indeterminate: Boolean) : ActionState
    data object Working : ActionState
    data object Ready : ActionState
    data object Retry : ActionState
    data object CertMismatch : ActionState
}

fun actionStateOf(status: AppStatus, download: DownloadState?): ActionState {
    if (download != null) {
        return when (download.phase) {
            DownloadPhase.QUEUED -> ActionState.Queued
            DownloadPhase.DOWNLOADING -> ActionState.Downloading(download.fraction, download.total <= 0)
            DownloadPhase.VERIFYING, DownloadPhase.INSTALLING -> ActionState.Working
            DownloadPhase.READY -> ActionState.Ready
            DownloadPhase.FAILED -> ActionState.Retry
        }
    }
    return when (status) {
        AppStatus.NotInstalled -> ActionState.Install
        is AppStatus.Installed -> when (status.verdict) {
            Verdict.Available, is Verdict.NewBuild, Verdict.Ignored -> ActionState.Update
            Verdict.CertMismatch -> ActionState.CertMismatch
            Verdict.UpToDate, Verdict.Regression -> ActionState.Open
        }
    }
}

val LocalUpdates = compositionLocalOf { UpdatesState() }
val LocalDownloads = staticCompositionLocalOf<SnapshotStateMap<String, DownloadState>> { error("downloads not provided") }
val LocalActions = staticCompositionLocalOf<AppActions> { error("actions not provided") }

/** 需要使用者確認的對話框（目前只有降級安裝）。 */
class DialogState {
    var downgrade by mutableStateOf<Pair<DowngradeInfo, () -> Unit>?>(null)
}

/**
 * 所有「按下去會發生事情」的動作：下載、取消、安裝、開啟。
 * 畫面只負責呼叫，結果用 Snackbar 回報。
 */
class AppActions(
    private val activity: Activity,
    private val graph: AppGraph,
    private val snackbar: SnackbarHostState,
    private val scope: CoroutineScope,
    val dialogs: DialogState,
    private val ensureNotifications: (() -> Unit) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "ui-actions").apply { isDaemon = true } }

    fun onAction(app: StoreApp, state: ActionState) {
        when (state) {
            ActionState.Install, ActionState.Update -> download(app)
            ActionState.Queued, is ActionState.Downloading -> graph.downloads.cancel(app.pkg)
            ActionState.Ready -> install(app.pkg)
            ActionState.Retry -> retry(app)
            ActionState.Open -> open(app.pkg)
            ActionState.Working, ActionState.CertMismatch -> Unit
        }
    }

    fun download(app: StoreApp) = ensureNotifications { graph.downloads.enqueue(app) }

    fun downloadAll(apps: List<StoreApp>) = ensureNotifications { apps.forEach(graph.downloads::enqueue) }

    fun cancel(pkg: String) = graph.downloads.cancel(pkg)

    /** 安裝失敗且檔案還在就重裝；下載失敗就重新下載。 */
    fun retry(app: StoreApp) {
        val state = graph.downloads.states.value[app.pkg]
        val file = graph.downloads.readyFile(app.pkg)
        if (state?.error is InstallFailedException && file != null) {
            install(app.pkg)
        } else {
            graph.downloads.dismiss(app.pkg, deleteFile = false)
            download(app)
        }
    }

    fun install(pkg: String) {
        val file = graph.downloads.readyFile(pkg)
        if (file == null) {
            message(activity.getString(R.string.error_file_missing))
            return
        }
        install(pkg, file)
    }

    fun install(pkg: String, file: File) {
        io.execute {
            val downgrade = graph.installer.downgradeInfo(file)
            main.post {
                if (downgrade != null) {
                    dialogs.downgrade = downgrade to { installNow(pkg, file) }
                } else {
                    installNow(pkg, file)
                }
            }
        }
    }

    fun open(pkg: String) {
        val intent = InstalledApps.launchIntent(activity, pkg)
        if (intent != null) activity.startActivity(intent) else message(activity.getString(R.string.error_cannot_open))
    }

    fun message(@androidx.annotation.StringRes id: Int) = message(activity.getString(id))

    fun message(text: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text)
        }
    }

    fun error(error: Throwable?) = message(Problems.message(activity, error))

    private fun installNow(pkg: String, file: File) {
        graph.downloads.installManually(pkg, file) { outcome ->
            main.post {
                when (outcome) {
                    is InstallOutcome.Installed -> message(
                        activity.getString(
                            if (outcome.via == InstallerKind.ROOT) R.string.installed_via_root else R.string.installed_via_shizuku
                        )
                    )
                    InstallOutcome.Handoff -> InstallHandoff.install(activity, file)
                    is InstallOutcome.Failed -> message(activity.getString(R.string.error_install, outcome.message))
                    InstallOutcome.Busy -> Unit
                }
            }
        }
    }
}
