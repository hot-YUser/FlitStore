package dev.yyblite.ui.detail

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.yyblite.R
import dev.yyblite.ui.common.rememberImage
import dev.yyblite.ui.nav.LocalNavigator
import dev.yyblite.ui.nav.Route

/** 全螢幕截圖：左右翻頁，點一下或返回關閉。 */
@Composable
fun ScreenshotsScreen(route: Route.Screenshots) {
    val navigator = LocalNavigator.current
    val pager = rememberPagerState(initialPage = route.start) { route.urls.size }
    val px = LocalWindowInfo.current.containerSize.height
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            val image = rememberImage(route.urls[page], px)
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(remember { MutableInteractionSource() }, indication = null) { navigator.pop() },
                contentAlignment = Alignment.Center
            ) {
                if (image != null) {
                    Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
            }
        }
        IconButton(onClick = { navigator.pop() }, modifier = Modifier.systemBarsPadding().padding(8.dp)) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.action_close), tint = Color.White)
        }
        Text(
            "${pager.currentPage + 1} / ${route.urls.size}",
            color = Color.White,
            modifier = Modifier.align(Alignment.BottomCenter).systemBarsPadding().padding(16.dp)
        )
    }
}
