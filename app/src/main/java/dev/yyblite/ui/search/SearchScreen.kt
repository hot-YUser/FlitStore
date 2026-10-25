package dev.yyblite.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.yyblite.R
import dev.yyblite.data.Load
import dev.yyblite.graph
import dev.yyblite.ui.common.AppRow
import dev.yyblite.ui.common.ErrorBox
import dev.yyblite.ui.common.Glyph
import dev.yyblite.ui.common.LoadingBox
import dev.yyblite.ui.common.MessageBox
import dev.yyblite.ui.nav.LocalNavigator
import dev.yyblite.ui.nav.Route
import dev.yyblite.ui.nav.sharedIcon

/** 搜尋：空白時顯示搜尋紀錄，送出後顯示結果；失敗與「沒有結果」分開呈現。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen() {
    val graph = LocalContext.current.graph
    val navigator = LocalNavigator.current
    val keyboard = LocalSoftwareKeyboardController.current
    val text = rememberTextFieldState()
    var submitted by rememberSaveable { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf(graph.settings.searchHistory) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (submitted == null) {
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }
    LaunchedEffect(text) { snapshotFlow { text.text.isBlank() }.collect { blank -> if (blank) submitted = null } }

    fun submit(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        text.setTextAndPlaceCursorAtEnd(trimmed)
        submitted = trimmed
        graph.settings.addSearch(trimmed)
        history = graph.settings.searchHistory
        keyboard?.hide()
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // 這一頁本身就是展開的搜尋畫面：不用 SearchBar(state)，否則輸入框一取得焦點就會等著
        // 不存在的展開版搜尋列接手，焦點隨即被清掉，鍵盤也跳不出來。
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .sharedIcon("search-bar")
        ) {
            SearchBarDefaults.InputField(
                state = text,
                onSearch = ::submit,
                expanded = true,
                onExpandedChange = {},
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = {
                    IconButton(onClick = { navigator.pop() }) {
                        Glyph(R.drawable.ic_arrow_back, contentDescription = stringResource(R.string.action_back))
                    }
                },
                trailingIcon = {
                    if (text.text.isNotEmpty()) {
                        IconButton(onClick = {
                            text.clearText()
                            runCatching { focus.requestFocus() }
                            keyboard?.show()
                        }) {
                            Glyph(R.drawable.ic_close, contentDescription = stringResource(R.string.action_clear))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
            )
        }
        val current = submitted
        if (current == null) {
            History(history, onPick = ::submit, onClear = {
                graph.settings.clearSearchHistory()
                history = emptyList()
            })
        } else {
            Results(current)
        }
    }
}

@Composable
private fun History(history: List<String>, onPick: (String) -> Unit, onClear: () -> Unit) {
    if (history.isEmpty()) return
    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.search_history),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onClear) { Text(stringResource(R.string.action_clear)) }
            }
        }
        items(history) { entry ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(entry) }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Glyph(R.drawable.ic_history)
                Spacer(Modifier.width(16.dp))
                Text(entry, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun Results(query: String) {
    val graph = LocalContext.current.graph
    val navigator = LocalNavigator.current
    val load by remember(query) { graph.store.search(query) }.collectAsState()
    when (val state = load) {
        Load.Loading -> LoadingBox()
        is Load.Failed -> ErrorBox(state.error, { graph.store.retrySearch(query) })
        is Load.Ready -> if (state.data.isEmpty()) {
            MessageBox(R.drawable.ic_search, stringResource(R.string.search_empty, query))
        } else {
            LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
                items(state.data, key = { it.pkg }) { app ->
                    AppRow(app, origin = "search", onClick = { navigator.push(Route.Detail(app.pkg, app, "search")) })
                }
            }
        }
    }
}
