package dev.yyblite.data

/**
 * 更新判定：商店提供有效 versionCode 時以它為主（與應用寶 AppInfoForUpdate 一致），
 * 公開頁面的 versionCode 常為 0，此時退回 versionName 數字段比較。
 */
object UpdateDecision {
    fun needsUpdate(store: StoreApp, installedVersionName: String?, installedVersionCode: Long): Boolean {
        val storeCode = store.versionCode?.toLongOrNull() ?: 0L
        if (storeCode > 0 && installedVersionCode > 0) return storeCode > installedVersionCode
        return VersionOrder.compare(store.versionName, installedVersionName) > 0
    }
}
