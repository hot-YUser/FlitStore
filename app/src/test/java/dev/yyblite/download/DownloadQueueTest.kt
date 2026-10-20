package dev.yyblite.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DownloadQueueTest {

    @Test
    fun gate() {
        val current = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val done = CountDownLatch(5)

        repeat(5) {
            DownloadQueue.submit {
                val running = current.incrementAndGet()
                peak.updateAndGet { maxOf(it, running) }
                try {
                    Thread.sleep(100)
                } finally {
                    current.decrementAndGet()
                    done.countDown()
                }
            }
        }

        assertTrue("佇列未在時限內完成", done.await(10, TimeUnit.SECONDS))
        assertEquals(2, peak.get())
    }
}
