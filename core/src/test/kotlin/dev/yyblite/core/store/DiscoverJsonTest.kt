package dev.yyblite.core.store

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoverJsonTest {

    private fun app(pkg: String, url: String = "") = StoreApp(
        pkg, "Name $pkg", "1.0", null, url, null, "", null, 10, null, "https://i/$pkg.png",
        "Dev", null, 4.2, emptyList(), "intro", category = "工具"
    )

    @Test
    fun feedRoundTrip() {
        val feed = Feed(
            banners = listOf(Banner(app("a.b"), "https://img/1.jpg", "Title"), Banner(app("c.d"), "https://img/2.jpg", null)),
            sections = listOf(
                Section("yybn_home_popapp_rank", "流行榜", listOf(app("e.f", "https://x/e.apk"), app("g.h")), ListSource.Rank("popular-app-list"), ranked = true),
                Section("YYB_HOME_HOT_APP", "", listOf(app("i.j")))
            )
        )
        assertEquals(feed, DiscoverJson.decodeFeed(DiscoverJson.encodeFeed(feed)))
    }

    @Test
    fun libraryAndListRoundTrip() {
        val library = Library(listOf(Category("200002", "视频"), Category("200004", "社交")), listOf(app("k.l")))
        assertEquals(library, DiscoverJson.decodeLibrary(DiscoverJson.encodeLibrary(library)))
        val list = listOf(app("m.n"), app("o.p", "https://x/o.apk"))
        assertEquals(list, DiscoverJson.decodeApps(DiscoverJson.encodeApps(list)))
    }
}
