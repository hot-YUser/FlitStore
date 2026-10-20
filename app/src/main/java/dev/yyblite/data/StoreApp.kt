package dev.yyblite.data

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
    val slogan: String? = null,
    val ratingCount: Long = 0
)
