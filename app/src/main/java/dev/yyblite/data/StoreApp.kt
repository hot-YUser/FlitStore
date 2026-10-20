package dev.yyblite.data

data class StoreApp(
    val pkg: String,
    val name: String,
    val versionName: String,
    val apkUrl: String,
    val apkMd5: String,
    val sizeBytes: Long,
    val iconUrl: String?,
    val developer: String?
)
