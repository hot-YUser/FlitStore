package dev.yyblite.core.update

import dev.yyblite.core.store.StoreApp

data class InstalledApp(
    val pkg: String,
    val label: String,
    val versionName: String,
    val versionCode: Long
)

/** 一個已安裝應用相對於商店版本的結論。 */
sealed interface Verdict {
    /** 可以直接提供更新（列表、徽章、通知、自動更新都算它）。 */
    val offered: Boolean get() = false

    data object UpToDate : Verdict
    data object Available : Verdict { override val offered get() = true }

    /** versionName 相同、商店重新打包出更大的 versionCode（由 Range 探測得知）。 */
    data class NewBuild(val versionCode: Long) : Verdict { override val offered get() = true }

    /** 商店 APK 的簽章不在已安裝套件的簽章歷史裡，覆蓋安裝必定失敗。 */
    data object CertMismatch : Verdict

    /** 商店標示較新，但下載後發現 APK 的 versionCode 比已安裝的舊。 */
    data object Regression : Verdict

    data object Ignored : Verdict
}

/**
 * 唯一的更新判定。前景刷新、單筆刷新、重試、背景檢查、詳情頁都走這裡，
 * 所以不會再有某一條路徑漏掉某個檢查。
 */
object UpdatePolicy {

    /**
     * 商店版本是否較新：versionCode 優先。
     *
     * 應用寶的私有更新協定是由 client 上傳本機 versionCode，伺服器回傳需要更新的清單；
     * 公開頁面的 versionCode 常是 0，因此呼叫端可用 [probedVersionCode] 傳入從 APK
     * AndroidManifest 探測到的建置號。只有兩種 code 都拿不到時才退回 versionName。
     */
    fun isNewer(
        store: StoreApp,
        installedVersionName: String?,
        installedVersionCode: Long,
        probedVersionCode: Long? = null
    ): Boolean {
        val storeCode = store.versionCode?.toLongOrNull() ?: 0L
        if (storeCode > 0 && installedVersionCode > 0) return storeCode > installedVersionCode
        val probed = probedVersionCode?.takeIf { it > 0 }
        if (probed != null && installedVersionCode > 0) return probed > installedVersionCode
        return VersionOrder.compare(store.versionName, installedVersionName) > 0
    }

    /**
     * @param certOk 商店簽章是否在已安裝套件的簽章歷史中（商店未提供簽章時視為 true）
     * @param regression 這個商店版本是否已被標記為版本回退
     * @param ignored 使用者是否忽略了這個版本或整個應用
     * @param probedBuild 從 APK Manifest 探測到的 versionCode；商店頁沒有 versionCode 時的主要依據
     * @param requireVersionCode 探測結果還沒出來：商店頁也沒有 versionCode 時先不提供更新
     */
    fun evaluate(
        store: StoreApp,
        installed: InstalledApp,
        certOk: Boolean,
        regression: Boolean,
        ignored: Boolean,
        probedBuild: Long? = null,
        requireVersionCode: Boolean = false
    ): Verdict {
        val probed = probedBuild?.takeIf { it > 0 }
        val storeCode = store.versionCode?.toLongOrNull() ?: 0L
        if (requireVersionCode && storeCode <= 0 && probed == null) return Verdict.UpToDate
        val sameName = VersionOrder.compare(store.versionName, installed.versionName) == 0
        val build = probed?.takeIf { sameName && it > installed.versionCode }
        val newer = build != null ||
            isNewer(store, installed.versionName, installed.versionCode, probed)
        return when {
            !newer && build == null -> Verdict.UpToDate
            regression -> Verdict.Regression
            ignored -> Verdict.Ignored
            !certOk -> Verdict.CertMismatch
            build != null -> Verdict.NewBuild(build)
            else -> Verdict.Available
        }
    }
}
