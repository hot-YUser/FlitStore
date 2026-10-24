package dev.yyblite.download

import android.content.Context
import java.io.File

/**
 * 下載檔位置：`<external files>/apk/<套件>/<版本>_<abi>.apk`。
 * 每個套件一個資料夾，刪除時不會像舊版的檔名前綴比對那樣誤刪 `com.foo_bar`。
 */
object ApkFiles {
    private val UNSAFE = Regex("[^A-Za-z0-9._-]")

    fun root(ctx: Context): File? = ctx.getExternalFilesDir("apk")

    fun dir(ctx: Context, pkg: String): File? = root(ctx)?.let { File(it, safe(pkg)) }

    fun file(ctx: Context, pkg: String, version: String, abi: String): File? =
        dir(ctx, pkg)?.let { File(it, "${safe(version)}_$abi.apk") }

    /** 已下載完成的 APK（不含 .part），依修改時間新到舊。 */
    fun list(ctx: Context): List<File> =
        root(ctx)?.listFiles()?.filter { it.isDirectory }
            ?.flatMap { dir -> dir.listFiles()?.filter { it.isFile && it.name.endsWith(".apk") }.orEmpty() }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    fun packageOf(file: File): String = file.parentFile?.name.orEmpty()

    /** 刪除套件的下載檔；[keep] 是仍在使用中的檔案（例如正在下載的 .part）。 */
    fun deleteFor(ctx: Context, pkg: String, keep: Set<File> = emptySet()) {
        val dir = dir(ctx, pkg) ?: return
        dir.listFiles()?.forEach { if (it !in keep) it.delete() }
        if (dir.listFiles().isNullOrEmpty()) dir.delete()
    }

    fun deleteAll(ctx: Context, keep: Set<File> = emptySet()) {
        root(ctx)?.listFiles()?.forEach { dir ->
            if (dir.isDirectory) deleteFor(ctx, dir.name, keep) else dir.delete()
        }
    }

    fun totalBytes(ctx: Context): Long =
        root(ctx)?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    /** v0.1.0 把檔案直接放在 apk/ 底下，升級後清掉。 */
    fun removeLegacy(ctx: Context) {
        root(ctx)?.listFiles()?.forEach { if (it.isFile) it.delete() }
    }

    private fun safe(value: String): String = value.replace(UNSAFE, "_").take(80).ifBlank { "_" }
}
