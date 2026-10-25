package dev.yyblite.ui.nav

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
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

private const val BACK_MS = 400
// 手勢只拖動前半段（0..BACK_MS/2）；拖到約三分之一螢幕寬時，卡片的縮放與圓角就已到位。
private const val CARD_AT = BACK_MS * 35 / 200
private val CARD_CORNER = 28.dp
private val CARD_SHIFT = 24.dp

/**
 * 以 SeekableTransitionState 驅動頁面切換：前進用彈簧動畫；返回時目前頁縮成圓角卡片、
 * 往手勢方向偏移，上一頁在下方放大歸位，與系統跨頁的預測式返回同一種樣子。
 * 手勢只拖動前半段，卡片保持不透明；放手後才滑出、淡出，取消則回彈。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun NavHost(navigator: Navigator, content: @Composable (Entry) -> Unit) {
    val holder = rememberSaveableStateHolder()
    val top = navigator.stack.last()
    val transitionState = remember { SeekableTransitionState(top) }
    var gesturing by remember { mutableStateOf(false) }
    var edge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    val shift = with(LocalDensity.current) { CARD_SHIFT.roundToPx() }

    LaunchedEffect(top) {
        if (!gesturing && transitionState.targetState != top) transitionState.animateTo(top)
    }

    PredictiveBackHandler(enabled = navigator.stack.size > 1) { events ->
        val previous = navigator.stack[navigator.stack.lastIndex - 1]
        gesturing = true
        try {
            events.collect { event ->
                edge = event.swipeEdge
                transitionState.seekTo(event.progress / 2, previous)
            }
            navigator.pop()
            transitionState.animateTo(previous)
        } catch (e: CancellationException) {
            transitionState.animateTo(transitionState.currentState)
            throw e
        } finally {
            gesturing = false
            edge = BackEventCompat.EDGE_LEFT
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

    val nav = rememberTransition(transitionState, label = "nav")
    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransition provides this) {
            nav.AnimatedContent(
                transitionSpec = {
                    if (targetState.id > initialState.id) {
                        (slideInHorizontally { it / 5 } + fadeIn()) togetherWith
                            (scaleOut(targetScale = 0.94f) + fadeOut())
                    } else {
                        val sign = if (edge == BackEventCompat.EDGE_RIGHT) -1 else 1
                        val card = scaleOut(keyframes { durationMillis = BACK_MS; 0.9f at CARD_AT }, targetScale = 0.9f) +
                            slideOutHorizontally(
                                keyframes { durationMillis = BACK_MS; IntOffset(sign * shift, 0) at BACK_MS / 2 using FastOutLinearInEasing }
                            ) { sign * it } +
                            fadeOut(keyframes { durationMillis = BACK_MS; 1f at BACK_MS / 2 })
                        (scaleIn(keyframes { durationMillis = BACK_MS; 0.94f at BACK_MS / 2 }, initialScale = 0.92f) togetherWith card)
                            .apply { targetContentZIndex = -1f }
                    }
                },
                contentKey = { it.id }
            ) { entry ->
                // 往回離開的頁面才變成圓角卡片；回到的上一頁在卡片下方由暗轉亮。
                // 返回時圖示跟著卡片一起收走，所以共用元素只在前進時啟用。
                val backward = nav.currentState.id > nav.targetState.id
                val leaving = entry.id > nav.targetState.id
                val returning = entry.id == nav.targetState.id && backward
                val corner by transition.animateDp({ keyframes { durationMillis = BACK_MS; CARD_CORNER at CARD_AT } }, label = "corner") {
                    if (it == EnterExitState.PostExit && leaving) CARD_CORNER else 0.dp
                }
                val scrim by transition.animateFloat({ tween(BACK_MS, easing = LinearEasing) }, label = "scrim") {
                    if (it == EnterExitState.PreEnter && returning) 0.32f else 0f
                }
                CompositionLocalProvider(LocalNavAnimation provides if (backward) null else this) {
                    Box(
                        Modifier
                            .graphicsLayer {
                                shape = RoundedCornerShape(corner)
                                clip = corner > 0.dp
                            }
                            .drawWithContent {
                                drawContent()
                                if (scrim > 0f) drawRect(Color.Black, alpha = scrim)
                            }
                    ) {
                        holder.SaveableStateProvider(entry.id) { content(entry) }
                    }
                }
            }
        }
    }
}

/** 前進時列表圖示飛成詳情頁頁首圖示的共用元素轉場；[key] 要包含來源，避免同畫面重複。 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedIcon(key: String): Modifier {
    val shared = LocalSharedTransition.current ?: return this
    val animation = LocalNavAnimation.current ?: return this
    return with(shared) {
        this@sharedIcon.sharedElement(rememberSharedContentState(key), animation)
    }
}
