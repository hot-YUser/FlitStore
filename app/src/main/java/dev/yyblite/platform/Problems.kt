package dev.yyblite.platform

import android.content.Context
import dev.yyblite.R
import dev.yyblite.core.download.ChecksumException
import dev.yyblite.core.download.DownloadCancelledException
import dev.yyblite.core.download.ThrottledException
import dev.yyblite.core.net.HttpStatusException
import dev.yyblite.core.net.StoreFormatException
import dev.yyblite.download.InstallFailedException
import dev.yyblite.download.NoDownloadException
import dev.yyblite.download.SignatureMismatchException
import dev.yyblite.download.SignatureUnreadableException
import dev.yyblite.download.StorageUnavailableException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** 把 :core 與下載層的型別化例外轉成在地化、看得懂的一句話。 */
object Problems {
    fun message(ctx: Context, error: Throwable?): String = when (error) {
        null -> ctx.getString(R.string.error_unknown)
        is UnknownHostException, is ConnectException -> ctx.getString(R.string.error_offline)
        is SocketTimeoutException -> ctx.getString(R.string.error_timeout)
        is SSLException, is SocketException -> ctx.getString(R.string.error_network)
        is HttpStatusException -> ctx.getString(R.string.error_http, error.code)
        is ThrottledException -> ctx.getString(R.string.error_throttled)
        is ChecksumException -> ctx.getString(R.string.error_checksum)
        is SignatureMismatchException -> ctx.getString(R.string.error_signature)
        is SignatureUnreadableException -> ctx.getString(R.string.error_signature_unreadable)
        is StorageUnavailableException -> ctx.getString(R.string.error_storage)
        is NoDownloadException -> ctx.getString(R.string.error_no_download)
        is StoreFormatException -> ctx.getString(R.string.error_store_format)
        is InstallFailedException -> ctx.getString(R.string.error_install, error.message.orEmpty())
        is DownloadCancelledException -> ctx.getString(R.string.status_cancelled)
        is IOException -> ctx.getString(R.string.error_network_detail, error.message ?: error.javaClass.simpleName)
        else -> ctx.getString(R.string.error_generic, error.message ?: error.javaClass.simpleName)
    }
}
