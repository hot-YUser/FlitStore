package dev.yyblite.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object ThumbCache {
    private const val MAX_BYTES = 8 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val pool = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "thumb").apply { isDaemon = true }
    }

    fun load(url: String?, view: ImageView) {
        view.tag = url
        if (url.isNullOrBlank()) {
            view.setImageResource(android.R.drawable.sym_def_app_icon)
            return
        }
        val cached = cache.get(url)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageResource(android.R.drawable.sym_def_app_icon)
        pool.execute {
            val bitmap = fetch(url) ?: return@execute
            cache.put(url, bitmap)
            view.post {
                if (view.tag == url) view.setImageBitmap(bitmap)
            }
        }
    }

    private fun fetch(url: String): Bitmap? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7)")
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }
}
