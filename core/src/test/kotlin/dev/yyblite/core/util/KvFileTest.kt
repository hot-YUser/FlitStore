package dev.yyblite.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class KvFileTest {

    /**
     * 64 條執行緒同時寫入、覆寫、刪除隨機鍵（資料在執行時產生），
     * 落盤後重新載入必須與每條執行緒最後一次寫入的結果完全一致。
     */
    @Test
    fun concurrentWritesSurviveReload() {
        val dir = Files.createTempDirectory("kv").toFile()
        val file = File(dir, "store.json")
        val kv = KvFile(file, debounceMs = 5)
        val threads = 64
        val expected = Array(threads) { HashMap<String, String?>() }
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) { t ->
            Thread {
                val random = Random(t)
                start.await()
                repeat(400) {
                    // 每條執行緒只碰自己的鍵空間，最終值才有唯一答案。
                    val key = "t$t-k${random.nextInt(50)}"
                    if (random.nextInt(5) == 0) {
                        kv.remove(key)
                        expected[t][key] = null
                    } else {
                        val value = "v${random.nextLong()}\"quoted\" 中文"
                        kv.put(key, value)
                        expected[t][key] = value
                    }
                }
                done.countDown()
            }.start()
        }
        start.countDown()
        done.await(30, TimeUnit.SECONDS)
        kv.flush()

        val reloaded = KvFile(file)
        for (map in expected) for ((key, value) in map) {
            if (value == null) assertNull(key, reloaded[key]) else assertEquals(key, value, reloaded[key])
        }
        assertEquals(expected.sumOf { m -> m.values.count { it != null } }, reloaded.keys().size)
        dir.deleteRecursively()
    }

    @Test
    fun corruptFileStartsEmpty() {
        val file = File.createTempFile("kvfile", ".json")
        file.writeText("{not json")
        assertEquals(0, KvFile(file).keys().size)
        file.delete()
    }
}
