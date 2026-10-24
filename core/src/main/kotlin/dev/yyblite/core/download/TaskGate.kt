package dev.yyblite.core.download

import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/** 同時最多 [permits] 個任務在跑，其餘依序排隊（公平鎖）。 */
class TaskGate(permits: Int, name: String) {
    private val gate = Semaphore(permits, true)
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, name).apply { isDaemon = true }
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
