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

    /** 已安裝套件的簽章憑證 MD5。 */
    fun installedCertificateMd5(ctx: Context, pkg: String): String? {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info = try {
            pm.getPackageInfo(pkg, flags)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        val signer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()
        } ?: return null
        return md5(signer.toByteArray())
    }

    /** 商店提供的憑證與本機不一致時視為可疑更新。 */
    fun matchesInstalled(ctx: Context, expectedMd5: String?, pkg: String): Boolean {
        if (expectedMd5.isNullOrBlank()) return true
        val actual = installedCertificateMd5(ctx, pkg) ?: return true
        return actual.equals(expectedMd5, ignoreCase = true)
    }

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

        return md5(signer.toByteArray())
    }

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
}
