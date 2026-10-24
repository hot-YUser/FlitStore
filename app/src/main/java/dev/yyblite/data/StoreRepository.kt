package dev.yyblite.data

import android.content.Context
import android.util.Log
import android.util.LruCache
import dev.yyblite.core.store.DetailClient
import dev.yyblite.core.store.DiscoverClient
import dev.yyblite.core.store.DiscoverJson
import dev.yyblite.core.store.Feed
import dev.yyblite.core.store.Library
import dev.yyblite.core.store.ListSource
import dev.yyblite.core.store.LookupResult
import dev.yyblite.core.store.ReviewPage
import dev.yyblite.core.store.StoreApp
import dev.yyblite.core.store.StoreClient
import dev.yyblite.core.store.StoreDetail
import dev.yyblite.core.util.KvFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** 非同步資料的三態：載入中、有資料（可能正在背景刷新或刷新失敗）、失敗。 */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val data: T, val refreshing: Boolean = false, val error: Throwable? = null) : Load<T>
    data class Failed(val error: Throwable) : Load<Nothing>
}

val <T> Load<T>.dataOrNull: T? get() = (this as? Load.Ready)?.data

data class DetailState(
    val app: StoreApp? = null,
    val loadingApp: Boolean = true,
    val notInStore: Boolean = false,
    val appError: Throwable? = null,
    val detail: StoreDetail? = null,
    val loadingDetail: Boolean = true
)

/**
 * 商店內容（探索、清單、詳情、搜尋、評論）。
 * 探索資料有磁碟快取，冷啟動先顯示上次內容；詳情與搜尋只放記憶體。
 */
class StoreRepository(ctx: Context) {
    private val disk by lazy { KvFile(File(ctx.cacheDir, "discover.json")) }
    private val io = Executors.newFixedThreadPool(4) { r -> Thread(r, "store-io").apply { isDaemon = true } }
    private val flows = ConcurrentHashMap<String, MutableStateFlow<Load<Any>>>()
    private val fetchedAt = ConcurrentHashMap<String, Long>()
    private val details = object : LruCache<String, MutableStateFlow<DetailState>>(24) {}
    private val detailAt = ConcurrentHashMap<String, Long>()

    fun feed(): StateFlow<Load<Feed>> =
        cached("feed", DiscoverJson::encodeFeed, DiscoverJson::decodeFeed) { DiscoverClient.feed() }

    fun library(game: Boolean, categoryId: String?): StateFlow<Load<Library>> =
        cached("lib:$game:${categoryId.orEmpty()}", DiscoverJson::encodeLibrary, DiscoverJson::decodeLibrary) {
            DiscoverClient.library(game, categoryId)
        }

    fun list(source: ListSource): StateFlow<Load<List<StoreApp>>> =
        cached("list:${key(source)}", DiscoverJson::encodeApps, DiscoverJson::decodeApps) { DiscoverClient.list(source) }

    fun refreshFeed() = refresh("feed")

    fun refreshLibrary(game: Boolean, categoryId: String?) = refresh("lib:$game:${categoryId.orEmpty()}")

    fun refreshList(source: ListSource) = refresh("list:${key(source)}")

    /** 搜尋只放記憶體；同一關鍵字在本次啟動內重複搜尋直接命中。 */
    fun search(query: String): StateFlow<Load<List<StoreApp>>> =
        memory("search:$query") { StoreClient.search(query) }

    fun retrySearch(query: String) = refresh("search:$query")

    /** 同開發者：優先用詳情頁的官方清單，沒有種子套件時退回搜尋並要求公司名完全相符。 */
    fun developerApps(name: String, seedPkg: String?): StateFlow<Load<List<StoreApp>>> =
        memory("dev:$name:${seedPkg.orEmpty()}") {
            val fromCard = seedPkg?.let { runCatching { DetailClient.fetch(it).sameDeveloper }.getOrNull() }.orEmpty()
            fromCard.ifEmpty {
                val wanted = name.replace(" ", "")
                StoreClient.search(name).filter { it.developer?.replace(" ", "") == wanted }
            }
        }

    fun retryDeveloperApps(name: String, seedPkg: String?) = refresh("dev:$name:${seedPkg.orEmpty()}")

    /** 版本資料與詳情頁並行抓取；5 分鐘內重開同一頁直接用記憶體。 */
    fun detail(pkg: String, seed: StoreApp?, force: Boolean = false): StateFlow<DetailState> {
        val existing = synchronized(details) { details.get(pkg) }
        val fresh = System.currentTimeMillis() - (detailAt[pkg] ?: 0L) < DETAIL_TTL_MS
        if (existing != null && fresh && !force) return existing
        val flow = existing ?: MutableStateFlow(DetailState(app = seed?.takeIf { it.detailed }))
        synchronized(details) { details.put(pkg, flow) }
        detailAt[pkg] = System.currentTimeMillis()
        flow.update { it.copy(app = it.app ?: seed, loadingApp = true, appError = null, notInStore = false) }
        io.execute {
            when (val result = StoreClient.lookup(pkg)) {
                is LookupResult.Found -> flow.update { it.copy(app = result.app, loadingApp = false, appError = null) }
                LookupResult.NotInStore -> flow.update { it.copy(loadingApp = false, notInStore = true) }
                is LookupResult.Failed -> {
                    ErrorLog.record("detail", "$pkg: ${result.reason}")
                    flow.update { it.copy(loadingApp = false, appError = result.error) }
                }
            }
        }
        io.execute {
            val detail = runCatching { DetailClient.fetch(pkg) }
                .onFailure { Log.w(TAG, "detail $pkg: ${it.message}") }
                .getOrNull()
            flow.update { it.copy(detail = detail ?: it.detail, loadingDetail = false) }
        }
        return flow
    }

    fun reviews(pkg: String, page: Int, filter: DetailClient.ReviewFilter, done: (Result<ReviewPage>) -> Unit) {
        io.execute { done(runCatching { DetailClient.fetchReviews(pkg, page, filter) }) }
    }

    private fun <T : Any> cached(
        key: String,
        encode: (T) -> String,
        decode: (String) -> T,
        fetch: () -> T
    ): StateFlow<Load<T>> {
        var created = false
        val flow = flows.getOrPut(key) {
            created = true
            MutableStateFlow<Load<Any>>(Load.Loading)
        }
        if (created) {
            io.execute {
                disk[key]?.let { text ->
                    runCatching { decode(text) }.getOrNull()?.let { flow.value = Load.Ready(it, refreshing = true) }
                }
                fetchInto(key, flow, fetch, encode)
            }
        } else if (System.currentTimeMillis() - (fetchedAt[key] ?: 0L) > DISCOVER_TTL_MS) {
            refresh(key)
        }
        @Suppress("UNCHECKED_CAST")
        return flow as StateFlow<Load<T>>
    }

    private fun <T : Any> memory(key: String, fetch: () -> T): StateFlow<Load<T>> {
        var created = false
        val flow = flows.getOrPut(key) {
            created = true
            MutableStateFlow<Load<Any>>(Load.Loading)
        }
        if (created) io.execute { fetchInto(key, flow, fetch, null) }
        @Suppress("UNCHECKED_CAST")
        return flow as StateFlow<Load<T>>
    }

    private val fetchers = ConcurrentHashMap<String, () -> Any>()
    private val encoders = ConcurrentHashMap<String, (Any) -> String>()

    private fun <T : Any> fetchInto(
        key: String,
        flow: MutableStateFlow<Load<Any>>,
        fetch: () -> T,
        encode: ((T) -> String)?
    ) {
        fetchers[key] = fetch
        @Suppress("UNCHECKED_CAST")
        if (encode != null) encoders[key] = encode as (Any) -> String
        try {
            val data = fetch()
            fetchedAt[key] = System.currentTimeMillis()
            flow.value = Load.Ready(data)
            encode?.let { disk.put(key, it(data)) }
        } catch (e: Exception) {
            Log.w(TAG, "load $key failed: ${e.message}")
            flow.update { current ->
                if (current is Load.Ready) current.copy(refreshing = false, error = e) else Load.Failed(e)
            }
        }
    }

    private fun refresh(key: String) {
        val flow = flows[key] ?: return
        val fetch = fetchers[key] ?: return
        val current = flow.value
        if (current is Load.Ready && current.refreshing) return
        flow.value = if (current is Load.Ready) current.copy(refreshing = true, error = null) else Load.Loading
        io.execute { fetchInto(key, flow, fetch, encoders[key]) }
    }

    private fun key(source: ListSource): String = when (source) {
        is ListSource.Rank -> "rank:${source.path}"
        is ListSource.InCategory -> "cat:${source.game}:${source.categoryId}"
    }

    private companion object {
        const val TAG = "YYBLite"
        const val DISCOVER_TTL_MS = 30L * 60 * 1000
        const val DETAIL_TTL_MS = 5L * 60 * 1000
    }
}
