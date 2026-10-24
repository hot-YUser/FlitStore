package dev.yyblite.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import dev.yyblite.R
import dev.yyblite.ui.common.LocalUpdates
import dev.yyblite.ui.explore.ExploreTab
import dev.yyblite.ui.mine.MineTab
import dev.yyblite.ui.updates.UpdatesTab

enum class HomeTab(@StringRes val label: Int, @DrawableRes val icon: Int, @DrawableRes val selectedIcon: Int) {
    Explore(R.string.tab_explore, R.drawable.ic_explore, R.drawable.ic_explore_filled),
    Updates(R.string.tab_updates, R.drawable.ic_update, R.drawable.ic_update_filled),
    Mine(R.string.tab_mine, R.drawable.ic_account_circle, R.drawable.ic_account_circle_filled)
}

/** 底部三分頁；各分頁的捲動位置在切換時保留。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(tab: HomeTab, onTab: (HomeTab) -> Unit) {
    val holder = rememberSaveableStateHolder()
    val offered = LocalUpdates.current.offered.size
    Scaffold(
        bottomBar = {
            ShortNavigationBar {
                HomeTab.entries.forEach { item ->
                    val selected = item == tab
                    ShortNavigationBarItem(
                        selected = selected,
                        onClick = { onTab(item) },
                        icon = {
                            val glyph = @Composable {
                                Icon(painterResource(if (selected) item.selectedIcon else item.icon), contentDescription = null)
                            }
                            if (item == HomeTab.Updates && offered > 0) {
                                BadgedBox(badge = { Badge { Text("$offered") } }) { glyph() }
                            } else {
                                glyph()
                            }
                        },
                        label = { Text(stringResource(item.label)) }
                    )
                }
            }
        }
    ) { padding ->
        AnimatedContent(
            targetState = tab,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "tab"
        ) { current ->
            holder.SaveableStateProvider(current.name) {
                val bottom = PaddingValues(bottom = padding.calculateBottomPadding())
                when (current) {
                    HomeTab.Explore -> ExploreTab(bottom)
                    HomeTab.Updates -> UpdatesTab(bottom)
                    HomeTab.Mine -> MineTab(bottom)
                }
            }
        }
    }
}
