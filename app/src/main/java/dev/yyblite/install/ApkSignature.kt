package dev.yyblite.install

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * 取得 APK 簽章憑證的 MD5，與應用寶 `signatureMd5`（憑證 MD5）同一算法。
 * 下載完成後比對，避免 CDN 回錯檔或快取污染。
 */
object ApkSignature {

    fun certificateMd5(ctx: Context, apk: File): String? {
        if (!apk.exists()) return null
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: return null
        val signer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()
        } ?: return null

        val digest = MessageDigest.getInstance("MD5").digest(signer.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
