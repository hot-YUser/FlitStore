package dev.yyblite.core

import org.junit.Assume
import java.io.IOException

/** 打真實端點的測試：網路或 CDN 不可達時略過，不把外部問題當成程式錯誤。 */
fun <T> live(block: () -> T): T = try {
    block()
} catch (e: IOException) {
    Assume.assumeNoException("network unavailable: ${e.message}", e)
    throw e
}
