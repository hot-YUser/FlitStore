package dev.yyblite.download

import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/**
 * 程序內全域下載閘門：同時最多 2 個檔案在跑（每個檔案內部再開 32 段）。
 */
object DownloadQueue {
    private val gate = Semaphore(2, true)
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "dl-queue").apply { isDaemon = true }
    }

    fun submit(task: () -> Unit) {
        pool.execute {
            gate.acquire()
            try {
                task()
            } finally {
                gate.release()
            }
        }
    }
}
