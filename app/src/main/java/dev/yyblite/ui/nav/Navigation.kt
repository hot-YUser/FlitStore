package dev.yyblite.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import dev.yyblite.core.store.ListSource
import dev.yyblite.core.store.StoreApp

sealed interface Route {
    data object Home : Route
    data class Detail(val pkg: String, val seed: StoreApp? = null) : Route
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

/** 返回堆疊：id 遞增，每個頁面各自保存狀態，同一路由重複開啟也互不干擾。 */
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

/** 官方 Navigation 3：返回堆疊由 [Navigator] 持有，轉場與預測式返回都用 NavDisplay 的預設行為。 */
@Composable
fun NavHost(navigator: Navigator, content: @Composable (Entry) -> Unit) {
    NavDisplay(
        backStack = navigator.stack,
        onBack = { navigator.pop() },
        entryProvider = { entry -> NavEntry(entry, contentKey = entry.id) { content(it) } }
    )
}
