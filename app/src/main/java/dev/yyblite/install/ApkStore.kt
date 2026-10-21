package dev.yyblite.install

import android.content.Context
import java.io.File

/** 安裝成功後清除下載檔，避免佔用空間。 */
object ApkStore {
    fun dir(ctx: Context): File? = ctx.getExternalFilesDir("apk")

    fun delete(files: List<File>) {
        files.forEach { file ->
            runCatching { if (file.exists()) file.delete() }
        }
    }

    /** 套件安裝完成後，刪除該套件所有下載檔（含殘留 .part）。 */
    fun deleteFor(ctx: Context, pkg: String) {
        val dir = dir(ctx) ?: return
        dir.listFiles()?.forEach { file ->
            if (file.name.startsWith("${pkg}_") && (file.name.endsWith(".apk") || file.name.endsWith(".part"))) {
                runCatching { file.delete() }
            }
        }
    }
}
