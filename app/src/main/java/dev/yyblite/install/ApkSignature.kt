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

    /**
     * 商店憑證是否可被已安裝套件接受。Android 的簽章輪替（v3 lineage）會把舊憑證
     * 保留在 signingCertificateHistory，因此要比對整段歷史，而不是只看目前簽章。
     */
    fun matchesInstalled(ctx: Context, expectedMd5: String?, pkg: String): Boolean {
        if (expectedMd5.isNullOrBlank()) return true
        val known = installedCertificateMd5s(ctx, pkg)
        if (known.isEmpty()) return true
        val ok = known.any { it.equals(expectedMd5, ignoreCase = true) }
        if (!ok) {
            android.util.Log.w("YYBLite", "cert not in history $pkg expected=$expectedMd5 known=$known")
        }
        return ok
    }

    /** 已安裝套件的目前簽章與歷史簽章（簽章輪替）之 MD5。 */
    fun installedCertificateMd5s(ctx: Context, pkg: String): List<String> {
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
            return emptyList()
        }
        val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return emptyList()
            (signing.apkContentsSigners?.toList().orEmpty() +
                signing.signingCertificateHistory?.toList().orEmpty())
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.toList().orEmpty()
        }
        return signers.map { md5(it.toByteArray()) }.distinct()
    }

    /** APK 內目前簽章 + lineage 歷史簽章的 MD5（v3 簽章輪替）。 */
    fun certificateMd5s(ctx: Context, apk: File): List<String> {
        if (!apk.exists()) return emptyList()
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: return emptyList()
        val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return emptyList()
            (signing.apkContentsSigners?.toList().orEmpty() +
                signing.signingCertificateHistory?.toList().orEmpty())
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.toList().orEmpty()
        }
        return signers.map { md5(it.toByteArray()) }.distinct()
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
