package dev.yyblite.data

/**
 * 依裝置 ABI 選擇下載連結：商店對 arm64 裝置提供獨立的 apkUrl64（只含 lib/arm64-v8a），
 * 缺少時才退回通用 apkUrl（32 位 build）。
 */
object Abi {
    const val ARM64 = "arm64-v8a"

    fun chooseUrl(app: StoreApp, deviceAbis: List<String>): String =
        if (isArm64(app, deviceAbis)) app.apkUrl64!! else app.apkUrl

    /** 部分應用（如 WPS）的 apkUrl64 與 apkUrl 指向同一檔案，代表商店沒有獨立 64 位包。 */
    fun isArm64(app: StoreApp, deviceAbis: List<String>): Boolean =
        app.apkUrl64 != null && app.apkUrl64 != app.apkUrl && deviceAbis.contains(ARM64)

    private val MD5_IN_URL = Regex("/apk/([0-9A-Fa-f]{32})\\.apk")

    /** 64 位檔案有獨立 MD5，直接取自連結檔名；取不到才退回頁面欄位。 */
    fun md5For(app: StoreApp, url: String): String =
        MD5_IN_URL.find(url)?.groupValues?.get(1) ?: app.apkMd5
}
