package dev.yyblite.core.store

import dev.yyblite.core.live
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverClientTest {

    private val packageName = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

    private fun assertInstallable(apps: List<StoreApp>) {
        assertTrue("清單不應為空", apps.isNotEmpty())
        apps.forEach { assertTrue("不是 Android 包名：${it.pkg}", packageName.matches(it.pkg)) }
        assertTrue("包名應去重", apps.map { it.pkg }.toSet().size == apps.size)
    }

    @Test
    fun feedHasBannersAndRanks() {
        val feed = live { DiscoverClient.feed() }
        assertTrue("應有橫幅", feed.banners.isNotEmpty())
        assertTrue("應有可展開的排行", feed.sections.any { it.ranked && it.more != null })
        feed.sections.forEach { assertInstallable(it.apps) }
        feed.banners.forEach { assertTrue(it.image.startsWith("https://")) }
    }

    @Test
    fun appLibraryAndCategory() {
        val library = live { DiscoverClient.library(game = false) }
        assertTrue("應有分類", library.categories.size >= 5)
        assertInstallable(library.apps)
        val category = library.categories.first { it.id != "0" }
        assertInstallable(live { DiscoverClient.list(ListSource.InCategory(false, category.id)) })
    }

    @Test
    fun gameLibrary() {
        val library = live { DiscoverClient.library(game = true) }
        assertTrue(library.categories.isNotEmpty())
        assertInstallable(library.apps)
    }

    @Test
    fun rankPage() {
        assertInstallable(live { DiscoverClient.list(ListSource.Rank("popular-app-list")) })
    }
}
