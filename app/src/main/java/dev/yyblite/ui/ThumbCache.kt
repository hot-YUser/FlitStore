package dev.yyblite.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 圖示快取：記憶體 LRU → 磁碟（cacheDir/thumbs）→ 已安裝 App 圖示 → 系統預設圖示。
 *
 * 重新整理時先同步顯示已安裝 App 的圖示（或磁碟快取），網路抓取完成後才替換，
 * 因此列表不會先閃出系統預設 icon。磁碟檔 7 天內視為新鮮、不重抓；
 * 過期清理在 App 啟動與背景更新檢查時執行（12 小時節流）。
 */
object ThumbCache {
    private const val MAX_MEM_BYTES = 8 * 1024 * 1024
    private const val MAX_DISK_BYTES = 16L * 1024 * 1024
    private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    private const val FRESH_MS = 7L * 24 * 60 * 60 * 1000
    private const val PRUNE_INTERVAL_MS = 12L * 60 * 60 * 1000

    private val mem = object : LruCache<String, Bitmap>(MAX_MEM_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val localIcons = object : LruCache<String, Drawable>(64) {
        override fun sizeOf(key: String, value: Drawable): Int = 1
    }

    private val diskPool = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "thumb-disk").apply { isDaemon = true }
    }
    private val netPool = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "thumb-net").apply { isDaemon = true }
    }
    private val fetching = ConcurrentHashMap.newKeySet<String>()

    fun load(ctx: Context, url: String?, pkg: String?, view: ImageView) {
        val tag = url ?: "pkg:$pkg"
        view.tag = tag

        if (!url.isNullOrBlank()) {
            mem.get(url)?.let {
                view.setImageBitmap(it)
                return
            }
        }

        // 已安裝 App 的圖示可同步取得，先擋住預設 icon；商店圖示抓到後再覆蓋。
        val local = pkg?.let { installedIcon(ctx, it) }
        if (local != null) view.setImageDrawable(local) else
            view.setImageResource(android.R.drawable.sym_def_app_icon)

        if (url.isNullOrBlank()) return

        diskPool.execute {
            val file = fileFor(ctx, url)
            val bytes = runCatching { if (file.isFile) file.readBytes() else null }.getOrNull()
            val cached = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (cached != null) {
                mem.put(url, cached)
                view.post { if (view.tag == tag) view.setImageBitmap(cached) }
                if (System.currentTimeMillis() - file.lastModified() < FRESH_MS) return@execute
            }
            netPool.execute { fetchAndStore(url, file, tag, view) }
        }
    }

    /** 在背景先快取已安裝 App 圖示，讓列表綁定時不必在主執行緒做 PackageManager IPC。 */
    fun preloadInstalled(ctx: Context, pkgs: Collection<String>) {
        for (pkg in pkgs) installedIcon(ctx, pkg)
    }

    /** 定期清理：先刪超過 30 天的檔案，再依最後修改時間刪到總量 16MB 以下。 */
    fun pruneIfDue(ctx: Context) {
        val prefs = ctx.getSharedPreferences("thumb_cache", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_prune", 0L) < PRUNE_INTERVAL_MS) return
        prefs.edit().putLong("last_prune", now).apply()
        diskPool.execute { prune(ctx) }
    }

    private fun prune(ctx: Context) {
        val dir = dir(ctx)
        val now = System.currentTimeMillis()
        val files = dir.listFiles()?.filter { it.isFile } ?: return

        var kept = ArrayList<File>(files.size)
        for (file in files) {
            if (file.name.endsWith(".tmp") || now - file.lastModified() > MAX_AGE_MS) {
                file.delete()
            } else {
                kept.add(file)
            }
        }

        var total = kept.sumOf { it.length() }
        if (total <= MAX_DISK_BYTES) return
        for (file in kept.sortedBy { it.lastModified() }) {
            if (total <= MAX_DISK_BYTES) break
            total -= file.length()
            file.delete()
        }
    }

    private fun fetchAndStore(url: String, file: File, tag: String, view: ImageView) {
        if (!fetching.add(url)) return
        try {
            val bytes = fetchBytes(url) ?: return
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
            mem.put(url, bitmap)
            writeAtomic(file, bytes)
            view.post { if (view.tag == tag) view.setImageBitmap(bitmap) }
        } finally {
            fetching.remove(url)
        }
    }

    private fun fetchBytes(url: String): ByteArray? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7)")
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        android.util.Log.w("YYBLite", "thumb failed $url", e)
        null
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

    private fun installedIcon(ctx: Context, pkg: String): Drawable? {
        localIcons.get(pkg)?.let { return it }
        return runCatching {
            ctx.packageManager.getApplicationIcon(pkg).also { localIcons.put(pkg, it) }
        }.getOrNull()
    }

    private fun dir(ctx: Context): File = File(ctx.cacheDir, "thumbs")

    private fun fileFor(ctx: Context, url: String): File {
        val name = MessageDigest.getInstance("MD5")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(dir(ctx), "$name.img")
    }
}
