package dev.yyblite.core.util

import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 以記憶體 Map 為準的鍵值檔：讀寫只碰 ConcurrentHashMap，
 * 落盤合併到單一背景執行緒，防抖後以「寫暫存檔 → rename」原子替換。
 *
 * 取代「每次讀整份 JSON、改一筆、整份寫回」的作法：那在 128 條執行緒同時寫入時會互相覆蓋，
 * 每筆更新也都要重寫整份檔案。
 */
class KvFile(
    private val file: File,
    private val debounceMs: Long = 400,
    private val writer: ScheduledExecutorService = SHARED_WRITER
) {
    private val map = ConcurrentHashMap<String, String>()
    private val scheduled = AtomicBoolean(false)
    private val fileLock = Any()

    init {
        val text = runCatching { if (file.isFile) file.readText() else null }.getOrNull()
        if (!text.isNullOrBlank()) {
            runCatching {
                val json = JSONObject(text)
                for (key in json.keys()) map[key] = json.getString(key)
            }
        }
    }

    operator fun get(key: String): String? = map[key]

    fun put(key: String, value: String) {
        if (map.put(key, value) != value) schedule()
    }

    fun remove(key: String) {
        if (map.remove(key) != null) schedule()
    }

    fun removeAll(keys: Collection<String>) {
        var changed = false
        for (key in keys) if (map.remove(key) != null) changed = true
        if (changed) schedule()
    }

    fun keys(): Set<String> = map.keys.toSet()

    fun snapshot(): Map<String, String> = HashMap(map)

    /** 立刻寫入（例如背景 Worker 結束前）。 */
    fun flush() {
        scheduled.set(false)
        write()
    }

    private fun schedule() {
        if (scheduled.compareAndSet(false, true)) {
            writer.schedule({
                if (scheduled.compareAndSet(true, false)) write()
            }, debounceMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun write() {
        synchronized(fileLock) {
            val json = JSONObject()
            for ((key, value) in map) json.put(key, value)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) tmp.delete()
            }
        }
    }

    companion object {
        private val SHARED_WRITER: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "kv-writer").apply { isDaemon = true } }
    }
}
