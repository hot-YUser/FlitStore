package dev.yyblite.ui.explore

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.yyblite.R
import dev.yyblite.core.store.Banner
import dev.yyblite.core.store.Feed
import dev.yyblite.core.store.Section
import dev.yyblite.core.store.StoreApp
import dev.yyblite.data.Load
import dev.yyblite.graph
import dev.yyblite.ui.Format
import dev.yyblite.ui.common.AppIcon
import dev.yyblite.ui.common.AppRow
import dev.yyblite.ui.common.ErrorBox
import dev.yyblite.ui.common.Glyph
import dev.yyblite.ui.common.LoadingBox
import dev.yyblite.ui.common.SectionHeader
import dev.yyblite.ui.common.rememberImage
import dev.yyblite.ui.nav.LocalNavigator
import dev.yyblite.ui.nav.Route
import dev.yyblite.ui.nav.sharedIcon
import kotlinx.coroutines.launch

/** 探索：頂部搜尋入口 + 「推薦｜應用｜遊戲」，左右滑動或點按切換。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExploreTab(bottom: PaddingValues) {
    val navigator = LocalNavigator.current
    val pager = rememberPagerState { 3 }
    val scope = rememberCoroutineScope()
    val labels = listOf(R.string.explore_for_you, R.string.explore_apps, R.string.explore_games)
    Column(Modifier.fillMaxSize()) {
        Surface(
            onClick = { navigator.push(Route.Search) },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth()
                .height(56.dp)
                .sharedIcon("search-bar")
        ) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph(R.drawable.ic_search)
                Spacer(Modifier.width(16.dp))
                Text(
                    stringResource(R.string.search_hint),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
        ) {
            labels.forEachIndexed { index, label ->
                ToggleButton(
                    checked = pager.currentPage == index,
                    onCheckedChange = { scope.launch { pager.animateScrollToPage(index) } },
                    modifier = Modifier.weight(1f),
                    shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        labels.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    }
                ) {
                    Text(stringResource(label), maxLines = 1)
                }
            }
        }
        HorizontalPager(pager, Modifier.weight(1f)) { page ->
            when (page) {
                0 -> FeedPage(bottom)
                1 -> LibraryPage(game = false, bottom = bottom)
                else -> LibraryPage(game = true, bottom = bottom)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedPage(bottom: PaddingValues) {
    val repository = LocalContext.current.graph.store
    val load by remember { repository.feed() }.collectAsState()
    var pulling by remember { mutableStateOf(false) }
    val refreshing = (load as? Load.Ready)?.refreshing == true
    LaunchedEffect(refreshing) { if (!refreshing) pulling = false }
    PullToRefreshBox(
        isRefreshing = pulling && refreshing,
        onRefresh = {
            pulling = true
            repository.refreshFeed()
        },
        modifier = Modifier.fillMaxSize()
    ) {
        when (val state = load) {
            Load.Loading -> LoadingBox()
            is Load.Failed -> ErrorBox(state.error, repository::refreshFeed)
            is Load.Ready -> FeedList(state.data, bottom)
        }
    }
}

@Composable
private fun FeedList(feed: Feed, bottom: PaddingValues) {
    val navigator = LocalNavigator.current
    val titles = feed.sections.map { sectionTitle(it) }
    LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState(), contentPadding = bottom) {
        if (feed.banners.isNotEmpty()) item(key = "banners") { BannerCarousel(feed.banners) }
        feed.sections.forEachIndexed { index, section ->
            val title = titles[index]
            if (title.isBlank()) return@forEachIndexed
            item(key = "h:${section.key}") {
                SectionHeader(
                    title,
                    action = section.more?.let { stringResource(R.string.action_see_all) },
                    onAction = section.more?.let { more -> { navigator.push(Route.Apps(more, title)) } }
                )
            }
            when {
                section.ranked -> itemsIndexed(section.apps, key = { _, app -> "r:${section.key}:${app.pkg}" }) { index, app ->
                    AppRow(app, origin = section.key, rank = index + 1, onClick = { open(navigator, app, section.key) })
                }
                section.key == HOT_APPS -> items(section.apps, key = { "a:${section.key}:${it.pkg}" }) { app ->
                    AppRow(app, origin = section.key, onClick = { open(navigator, app, section.key) })
                }
                else -> item(key = "row:${section.key}") { AppCardRow(section) }
            }
        }
    }
}

@Composable
private fun sectionTitle(section: Section): String = when (section.key) {
    "yybn_home_editor_choice" -> stringResource(R.string.section_editor_choice)
    "yybn_home_hotgame_rank" -> stringResource(R.string.section_top_games)
    "yybn_home_popapp_rank" -> stringResource(R.string.section_popular_apps)
    "yybn_home_newgame_rank" -> stringResource(R.string.section_new_games)
    HOT_APPS -> stringResource(R.string.section_trending_apps)
    else -> section.title
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BannerCarousel(banners: List<Banner>) {
    val navigator = LocalNavigator.current
    HorizontalMultiBrowseCarousel(
        state = rememberCarouselState { banners.size },
        preferredItemWidth = 300.dp,
        itemSpacing = 8.dp,
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = Modifier.fillMaxWidth().height(196.dp).padding(top = 8.dp)
    ) { index ->
        val banner = banners[index]
        val px = with(LocalDensity.current) { 196.dp.roundToPx() }
        val image = rememberImage(banner.image, px)
        Box(
            Modifier
                .fillMaxSize()
                .maskClip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable { open(navigator, banner.app, "banner") }
        ) {
            if (image != null) {
                Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.72f)))
            )
            Row(
                Modifier.align(Alignment.BottomStart).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIcon(banner.app.iconUrl, banner.app.pkg, 40.dp, Modifier.sharedIcon("icon:${banner.app.pkg}:banner"))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(banner.app.name, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    banner.title?.let {
                        Text(
                            it,
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppCardRow(section: Section) {
    val navigator = LocalNavigator.current
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(section.apps, key = { it.pkg }) { app ->
            Column(
                Modifier
                    .width(88.dp)
                    .clickable { open(navigator, app, section.key) }
                    .padding(vertical = 6.dp)
            ) {
                AppIcon(app.iconUrl, app.pkg, 88.dp, Modifier.sharedIcon("icon:${app.pkg}:${section.key}"))
                Spacer(Modifier.height(8.dp))
                Text(app.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    app.rating?.let { "★ " + Format.rating(it) } ?: app.category.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

/** 應用庫／遊戲庫：分類 chips + 清單。分類只顯示首頁資料（商店的分頁 API 目前取不到）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryPage(game: Boolean, bottom: PaddingValues) {
    val repository = LocalContext.current.graph.store
    val navigator = LocalNavigator.current
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    val base by remember { repository.library(game, null) }.collectAsState()
    val current by remember(category) { repository.library(game, category) }.collectAsState()
    val categories = (base as? Load.Ready)?.data?.categories.orEmpty()
    val origin = "lib:$game:${category.orEmpty()}"
    LazyColumn(Modifier.fillMaxSize(), contentPadding = bottom) {
        if (categories.isNotEmpty()) {
            item(key = "chips") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(selected = category == null, onClick = { category = null }, label = { Text(stringResource(R.string.category_all)) })
                    }
                    items(categories, key = { it.id }) { item ->
                        FilterChip(selected = category == item.id, onClick = { category = item.id }, label = { Text(item.name) })
                    }
                }
            }
        }
        when (val state = current) {
            Load.Loading -> item { LoadingBox() }
            is Load.Failed -> item { ErrorBox(state.error, { repository.refreshLibrary(game, category) }) }
            is Load.Ready -> items(state.data.apps, key = { "$origin:${it.pkg}" }) { app ->
                AppRow(app, origin = origin, onClick = { open(navigator, app, origin) })
            }
        }
    }
}

private const val HOT_APPS = "YYB_HOME_HOT_APP"

private fun open(navigator: dev.yyblite.ui.nav.Navigator, app: StoreApp, origin: String) =
    navigator.push(Route.Detail(app.pkg, app, origin))
