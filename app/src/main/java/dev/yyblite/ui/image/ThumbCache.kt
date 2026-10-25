package dev.yyblite.ui.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
import dev.yyblite.core.net.Http
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * 圖片快取：記憶體 LRU → 磁碟（cacheDir/thumbs）→ 網路；已安裝 App 的圖示另外快取。
 * 解碼時依顯示尺寸 downsample，列表 48dp 的圖示不會吃掉整張 256px 原圖的記憶體。
 * 磁碟檔 7 天內視為新鮮，過期清理在啟動與背景檢查時執行（12 小時節流）。
 */
object ThumbCache {
    private const val MAX_MEM_BYTES = 16 * 1024 * 1024
    private const val MAX_DISK_BYTES = 32L * 1024 * 1024
    private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    private const val FRESH_MS = 7L * 24 * 60 * 60 * 1000
    private const val PRUNE_INTERVAL_MS = 12L * 60 * 60 * 1000
    private const val ICON_PX = 144
    private const val LARGE_PX = 400

    private val mem = object : LruCache<String, ImageBitmap>(MAX_MEM_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.asAndroidBitmap().allocationByteCount
    }
    private val installedIcons = object : LruCache<String, ImageBitmap>(128) {}

    private val diskPool = Executors.newFixedThreadPool(2) { r -> Thread(r, "thumb-disk").apply { isDaemon = true } }
    private val netPool = Executors.newFixedThreadPool(4) { r -> Thread(r, "thumb-net").apply { isDaemon = true } }
    private val waiting = ConcurrentHashMap<String, CopyOnWriteArrayList<(ImageBitmap) -> Unit>>()

    fun memory(url: String, sizePx: Int): ImageBitmap? = mem.get(key(url, sizePx))

    fun installed(ctx: Context, pkg: String): ImageBitmap? {
        installedIcons.get(pkg)?.let { return it }
        return runCatching {
            ctx.packageManager.getApplicationIcon(pkg).toBitmap(ICON_PX, ICON_PX).asImageBitmap()
        }.getOrNull()?.also { installedIcons.put(pkg, it) }
    }

    /** 在背景先把已安裝 App 的圖示轉好，列表綁定時不必在主執行緒做 PackageManager IPC。 */
    fun preloadInstalled(ctx: Context, pkgs: Collection<String>) {
        for (pkg in pkgs) installed(ctx, pkg)
    }

    fun cachedInstalled(pkg: String): ImageBitmap? = installedIcons.get(pkg)

    /** 快取被擠掉或還沒預載時，在背景向 PackageManager 取圖示。回呼在背景執行緒。 */
    fun loadInstalled(ctx: Context, pkg: String, onLoaded: (ImageBitmap) -> Unit) {
        installedIcons.get(pkg)?.let(onLoaded) ?: diskPool.execute { installed(ctx, pkg)?.let(onLoaded) }
    }

    /** 非同步載入；同一張圖同時被多處要求時只抓一次。回呼在背景執行緒。 */
    fun load(ctx: Context, url: String, sizePx: Int, onLoaded: (ImageBitmap) -> Unit) {
        val key = key(url, sizePx)
        mem.get(key)?.let(onLoaded) ?: run {
            val listeners = CopyOnWriteArrayList<(ImageBitmap) -> Unit>()
            val existing = waiting.putIfAbsent(key, listeners)
            (existing ?: listeners).add(onLoaded)
            if (existing != null) return
            diskPool.execute {
                val file = fileFor(ctx, url)
                val cached = runCatching { if (file.isFile) decode(file.readBytes(), sizePx) else null }.getOrNull()
                if (cached != null) {
                    deliver(key, cached)
                    if (System.currentTimeMillis() - file.lastModified() < FRESH_MS) return@execute
                }
                netPool.execute { fetch(url, file, key, sizePx, deliverIfMissing = cached == null) }
            }
        }
    }

    /** 定期清理：先刪超過 30 天的檔案，再依最後修改時間刪到總量 32MB 以下。 */
    fun pruneIfDue(ctx: Context) {
        val prefs = ctx.getSharedPreferences("thumb_cache", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_prune", 0L) < PRUNE_INTERVAL_MS) return
        prefs.edit { putLong("last_prune", now) }
        diskPool.execute { prune(ctx) }
    }

    private fun fetch(url: String, file: File, key: String, sizePx: Int, deliverIfMissing: Boolean) {
        try {
            val bytes = Http.getBytes(url)
            writeAtomic(file, bytes)
            val bitmap = decode(bytes, sizePx) ?: return
            if (deliverIfMissing) deliver(key, bitmap) else mem.put(key, bitmap)
        } catch (e: Exception) {
            Log.w("YYBLite", "image failed $url: ${e.message}")
        } finally {
            waiting.remove(key)
        }
    }

    private fun deliver(key: String, bitmap: ImageBitmap) {
        mem.put(key, bitmap)
        waiting.remove(key)?.forEach { runCatching { it(bitmap) } }
    }

    private fun decode(bytes: ByteArray, sizePx: Int): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val shortest = minOf(bounds.outWidth, bounds.outHeight)
        while (shortest / (sample * 2) >= sizePx) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            // 橫幅與截圖是不透明的照片，用 RGB_565 記憶體減半；圖示可能有透明邊，維持 ARGB。
            inPreferredConfig = if (sizePx >= LARGE_PX) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }

    private fun prune(ctx: Context) {
        val now = System.currentTimeMillis()
        val files = dir(ctx).listFiles()?.filter { it.isFile } ?: return
        val kept = ArrayList<File>(files.size)
        for (file in files) {
            if (file.name.endsWith(".tmp") || now - file.lastModified() > MAX_AGE_MS) file.delete() else kept.add(file)
        }
        var total = kept.sumOf { it.length() }
        for (file in kept.sortedBy { it.lastModified() }) {
            if (total <= MAX_DISK_BYTES) break
            total -= file.length()
            file.delete()
        }
    }

    private fun writeAtomic(file: File, bytes: ByteArray) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) tmp.delete()
            }
        }
    }

    /** 尺寸取 2 的冪次分桶，相近大小共用同一份解碼結果。 */
    private fun key(url: String, sizePx: Int): String = "$url#${Integer.highestOneBit(sizePx.coerceAtLeast(1)) shl 1}"

    private fun dir(ctx: Context): File = File(ctx.cacheDir, "thumbs")

    private fun fileFor(ctx: Context, url: String): File {
        val name = MessageDigest.getInstance("MD5").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(dir(ctx), "$name.img")
    }
}
