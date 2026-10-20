package dev.yyblite.data

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 以固定 4 條執行緒查詢商店版本，每筆間隔 200ms 節流。
 * 回呼皆在背景執行緒，呼叫端負責切回主執行緒。
 */
class UpdateChecker(private val threads: Int = 4) {
    private val pool = Executors.newFixedThreadPool(threads) { r ->
        Thread(r, "update-check").apply { isDaemon = true }
    }

    fun check(
        apps: List<InstalledApp>,
        onResult: (InstalledApp, LookupResult) -> Unit,
        onDone: () -> Unit
    ) {
        if (apps.isEmpty()) {
            onDone()
            return
        }
        val remaining = AtomicInteger(apps.size)
        for (app in apps) {
            pool.execute {
                try {
                    var result = StoreClient.lookup(app.pkg)
                    if (result is LookupResult.Failed) {
                        Thread.sleep(400)
                        result = StoreClient.lookup(app.pkg)
                    }
                    runCatching { onResult(app, result) }
                } finally {
                    try {
                        Thread.sleep(120)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    if (remaining.decrementAndGet() == 0) onDone()
                }
            }
        }
    }

    fun shutdown() {
        pool.shutdownNow()
    }
}
