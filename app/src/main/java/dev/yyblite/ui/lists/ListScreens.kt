package dev.yyblite.ui.lists

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.yyblite.R
import dev.yyblite.core.store.DetailClient.ReviewFilter
import dev.yyblite.core.store.ListSource
import dev.yyblite.core.store.Review
import dev.yyblite.core.store.StoreApp
import dev.yyblite.data.Load
import dev.yyblite.data.StoreRepository
import dev.yyblite.graph
import dev.yyblite.ui.common.AppRow
import dev.yyblite.ui.common.ErrorBox
import dev.yyblite.ui.common.LoadingBox
import dev.yyblite.ui.common.MessageBox
import dev.yyblite.ui.common.SubScreen
import dev.yyblite.ui.detail.ReviewItem
import dev.yyblite.ui.nav.LocalNavigator
import dev.yyblite.ui.nav.Route
import kotlinx.coroutines.flow.StateFlow

/** 排行或分類的完整清單（商店首頁資料，最多約 20 筆）。 */
@Composable
fun AppsScreen(route: Route.Apps) {
    val repository = LocalContext.current.graph.store
    val flow = remember(route.source) { repository.list(route.source) }
    AppListScreen(
        title = route.title,
        flow = flow,
        ranked = route.source is ListSource.Rank,
        origin = "list:${route.source}",
        onRetry = { repository.refreshList(route.source) }
    )
}

@Composable
fun DeveloperScreen(route: Route.Developer) {
    val repository = LocalContext.current.graph.store
    val flow = remember(route) { repository.developerApps(route.name, route.seedPkg) }
    AppListScreen(
        title = route.name,
        flow = flow,
        ranked = false,
        origin = "developer:${route.name}",
        onRetry = { repository.retryDeveloperApps(route.name, route.seedPkg) }
    )
}

@Composable
fun AppListScreen(
    title: String,
    flow: StateFlow<Load<List<StoreApp>>>,
    ranked: Boolean,
    origin: String,
    onRetry: () -> Unit
) {
    val navigator = LocalNavigator.current
    val load by flow.collectAsState()
    SubScreen(title) { padding ->
        when (val state = load) {
            Load.Loading -> Box(Modifier.padding(padding)) { LoadingBox() }
            is Load.Failed -> Box(Modifier.padding(padding)) { ErrorBox(state.error, onRetry) }
            is Load.Ready -> if (state.data.isEmpty()) {
                Box(Modifier.padding(padding)) { MessageBox(R.drawable.ic_apps, stringResource(R.string.list_empty)) }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                    itemsIndexed(state.data, key = { _, app -> app.pkg }) { index, app ->
                        AppRow(
                            app = app,
                            origin = origin,
                            rank = if (ranked) index + 1 else null,
                            onClick = { navigator.push(Route.Detail(app.pkg, app, origin)) }
                        )
                    }
                }
            }
        }
    }
}

/** 全部評論：篩選 + 捲到底自動載入下一頁。 */
@Composable
fun ReviewsScreen(route: Route.Reviews) {
    val repository = LocalContext.current.graph.store
    var filter by rememberSaveable { mutableStateOf(ReviewFilter.ALL) }
    val pager = remember(filter) { ReviewPager(repository, route.pkg, filter) }
    val list = rememberLazyListState()
    val nearEnd by remember(pager) {
        derivedStateOf {
            val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= list.layoutInfo.totalItemsCount - 4
        }
    }
    LaunchedEffect(pager, nearEnd) { if (nearEnd) pager.loadNext() }

    SubScreen(stringResource(R.string.reviews_title, route.name)) { padding ->
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = padding) {
            item(key = "filters") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(ReviewFilter.entries) { option ->
                        FilterChip(selected = option == filter, onClick = { filter = option }, label = { Text(stringResource(label(option))) })
                    }
                }
            }
            if (pager.total > 0) {
                item(key = "total") {
                    Text(
                        pluralStringResource(R.plurals.reviews_count, pager.total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), pager.total.toString()),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }
            items(pager.items.size) { index -> ReviewItem(pager.items[index]) }
            item(key = "footer") {
                when {
                    pager.loading -> LoadingBox()
                    pager.error != null -> ErrorBox(pager.error, pager::retry)
                    pager.items.isEmpty() -> MessageBox(R.drawable.ic_thumb_up_filled, stringResource(R.string.reviews_empty))
                }
            }
        }
    }
}

private fun label(filter: ReviewFilter): Int = when (filter) {
    ReviewFilter.ALL -> R.string.reviews_all
    ReviewFilter.LATEST -> R.string.reviews_latest
    ReviewFilter.POSITIVE -> R.string.reviews_positive
    ReviewFilter.NEUTRAL -> R.string.reviews_neutral
    ReviewFilter.NEGATIVE -> R.string.reviews_negative
}

/** 評論分頁狀態；回呼切回主執行緒更新 Compose 狀態。 */
private class ReviewPager(
    private val repository: StoreRepository,
    private val pkg: String,
    private val filter: ReviewFilter
) {
    private val main = Handler(Looper.getMainLooper())
    val items = mutableStateListOf<Review>()
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<Throwable?>(null)
        private set
    var total by mutableStateOf(0L)
        private set
    private var page = 0
    private var hasNext = true

    fun loadNext() {
        if (loading || !hasNext || error != null) return
        loading = true
        repository.reviews(pkg, page, filter) { result ->
            main.post {
                loading = false
                result.onSuccess {
                    items += it.reviews
                    total = it.total
                    hasNext = it.hasNext && it.reviews.isNotEmpty()
                    page++
                }.onFailure { error = it }
            }
        }
    }

    fun retry() {
        error = null
        loadNext()
    }
}
