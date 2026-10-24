package dev.yyblite.ui.nav

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import dev.yyblite.core.store.ListSource
import dev.yyblite.core.store.StoreApp
import kotlin.coroutines.cancellation.CancellationException

sealed interface Route {
    data object Home : Route
    data class Detail(val pkg: String, val seed: StoreApp? = null, val origin: String = "") : Route
    data class Reviews(val pkg: String, val name: String) : Route
    data class Apps(val source: ListSource, val title: String) : Route
    data class Developer(val name: String, val seedPkg: String?) : Route
    data class Screenshots(val urls: List<String>, val start: Int) : Route
    data object Search : Route
    data object Settings : Route
    data object Downloads : Route
    data object Installed : Route
    data object Ignored : Route
    data object Diagnostics : Route
    data object About : Route
}

@Immutable
data class Entry(val route: Route, val id: Long)

/** 不依賴 navigation 函式庫的小型返回堆疊；id 遞增，用來判斷前進或返回動畫。 */
class Navigator {
    private var nextId = 1L
    val stack = mutableStateListOf(Entry(Route.Home, 0))

    fun push(route: Route) {
        stack.add(Entry(route, nextId++))
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun popToHome() {
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("Navigator not provided") }

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransition = compositionLocalOf<SharedTransitionScope?> { null }

val LocalNavAnimation = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * 以 SeekableTransitionState 驅動頁面切換：一般前進／返回用彈簧動畫，
 * 預測式返回手勢則依手指進度即時拖動同一段轉場，放手後完成或回彈。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun NavHost(navigator: Navigator, content: @Composable (Entry) -> Unit) {
    val holder = rememberSaveableStateHolder()
    val top = navigator.stack.last()
    val transitionState = remember { SeekableTransitionState(top) }
    var gesturing by remember { mutableStateOf(false) }

    LaunchedEffect(top) {
        if (!gesturing && transitionState.targetState != top) transitionState.animateTo(top)
    }

    PredictiveBackHandler(enabled = navigator.stack.size > 1) { events ->
        val previous = navigator.stack[navigator.stack.lastIndex - 1]
        gesturing = true
        try {
            events.collect { event -> transitionState.seekTo(event.progress, previous) }
            navigator.pop()
            transitionState.animateTo(previous)
        } catch (e: CancellationException) {
            transitionState.animateTo(transitionState.currentState)
            throw e
        } finally {
            gesturing = false
        }
    }

    // 已離開堆疊的頁面，丟掉它保存的捲動位置等狀態。
    val known = remember { HashSet<Long>() }
    val alive = navigator.stack.map { it.id }.toSet()
    LaunchedEffect(alive) {
        (known - alive).forEach(holder::removeState)
        known.retainAll(alive)
        known.addAll(alive)
    }

    val transition = rememberTransition(transitionState, label = "nav")
    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransition provides this) {
            transition.AnimatedContent(
                transitionSpec = {
                    if (targetState.id > initialState.id) {
                        (slideInHorizontally { it / 5 } + fadeIn()) togetherWith
                            (scaleOut(targetScale = 0.94f) + fadeOut())
                    } else {
                        (scaleIn(initialScale = 0.94f) + fadeIn()) togetherWith
                            (slideOutHorizontally { it / 5 } + fadeOut())
                    }
                },
                contentKey = { it.id }
            ) { entry ->
                CompositionLocalProvider(LocalNavAnimation provides this) {
                    holder.SaveableStateProvider(entry.id) { content(entry) }
                }
            }
        }
    }
}

/** 列表圖示與詳情頁頁首圖示之間的共用元素轉場；[key] 要包含來源，避免同畫面重複。 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedIcon(key: String): Modifier {
    val shared = LocalSharedTransition.current ?: return this
    val animation = LocalNavAnimation.current ?: return this
    return with(shared) {
        this@sharedIcon.sharedElement(rememberSharedContentState(key), animation)
    }
}
