package dev.yyblite.core.store

data class StoreApp(
    val pkg: String,
    val name: String,
    val versionName: String,
    val versionCode: String?,
    val apkUrl: String,
    val apkUrl64: String?,
    val apkMd5: String,
    val signatureMd5: String?,
    val sizeBytes: Long,
    val sizeDesc: String?,
    val iconUrl: String?,
    val developer: String?,
    val downloadDesc: String?,
    val rating: Double?,
    val screenshots: List<String>,
    val editorIntro: String?,
    val official: Boolean = false,
    val icp: String? = null,
    val ratingCount: Long = 0,
    val category: String? = null,
    val operator: String? = null,
    /** 來自 simple.jsp 的完整資料（含簽章、64 位連結）；搜尋與探索清單只有基本欄位，下載前要補查。 */
    val detailed: Boolean = false
)

sealed interface LookupResult {
    data class Found(val app: StoreApp) : LookupResult
    data object NotInStore : LookupResult
    data class Failed(val error: Throwable) : LookupResult {
        val reason: String get() = error.message ?: error.javaClass.simpleName
    }
}

data class Review(
    val user: String,
    val content: String,
    /** 秒；0 表示未知。由介面依語系格式化。 */
    val createdAt: Long,
    val stars: Int,
    val likes: Long
)

data class ReviewPage(
    val reviews: List<Review>,
    val hasNext: Boolean,
    val total: Long,
    val score: Double?,
    val distribution: Map<Int, Long>
)

data class StoreDetail(
    val appId: String? = null,
    val operator: String? = null,
    val updateTimeSec: Long? = null,
    val category: String? = null,
    val description: String? = null,
    val sameDeveloper: List<StoreApp> = emptyList(),
    val reviewPage: ReviewPage? = null
)

data class Category(val id: String, val name: String)

/** 可展開成完整清單的來源：排行頁或分類頁。 */
sealed interface ListSource {
    /** sj.qq.com 的排行頁路徑，例如 `popular-app-list`。 */
    data class Rank(val path: String) : ListSource

    data class InCategory(val game: Boolean, val categoryId: String) : ListSource
}

data class Banner(val app: StoreApp, val image: String, val title: String?)

/** [key] 是商店的 cardId，介面據此決定在地化標題；認不得時用 [title]。 */
data class Section(
    val key: String,
    val title: String,
    val apps: List<StoreApp>,
    val more: ListSource? = null,
    val ranked: Boolean = false
)

data class Feed(val banners: List<Banner>, val sections: List<Section>)

data class Library(val categories: List<Category>, val apps: List<StoreApp>)
