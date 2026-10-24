package dev.yyblite.platform

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * 簽章憑證 MD5，與應用寶 `signatureMd5` 同一算法。
 * Android 的簽章輪替（v3 lineage）會把舊憑證留在 signingCertificateHistory，
 * 所以一律回傳「目前 + 歷史」整串，比對時任一相符即可。
 */
object Signatures {

    fun installed(ctx: Context, pkg: String): List<String> = try {
        md5s(ctx.packageManager.getPackageInfo(pkg, flags()))
    } catch (_: PackageManager.NameNotFoundException) {
        emptyList()
    }

    fun archive(ctx: Context, apk: File): List<String> {
        if (!apk.exists()) return emptyList()
        return md5s(ctx.packageManager.getPackageArchiveInfo(apk.absolutePath, flags()))
    }

    /** 商店未提供簽章、或讀不到已安裝簽章時視為相容（交給安裝器最終判斷）。 */
    fun accepts(known: List<String>, expected: String?): Boolean =
        expected.isNullOrBlank() || known.isEmpty() || known.any { it.equals(expected, ignoreCase = true) }

    private fun flags(): Int =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES

    private fun md5s(info: PackageInfo?): List<String> {
        if (info == null) return emptyList()
        val signers = if (Build.VERSION.SDK_INT >= 28) {
            val signing = info.signingInfo ?: return emptyList()
            signing.apkContentsSigners.orEmpty().toList() + signing.signingCertificateHistory.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            info.signatures.orEmpty().toList()
        }
        return signers.map { md5(it.toByteArray()) }.distinct()
    }

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
}
