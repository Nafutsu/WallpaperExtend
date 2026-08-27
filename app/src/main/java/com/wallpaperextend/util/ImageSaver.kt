package com.wallpaperextend.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

object ImageSaver {

    /**
     * 保存 Bitmap 到相册（Download/WallpaperExtend 目录）
     * - Android 10+：使用 MediaStore，无需 WRITE_EXTERNAL_STORAGE
     * - Android 9-：直接写文件
     * @return 保存后的文件路径（或 null）
     */
    fun saveToGallery(context: Context, bitmap: Bitmap, filename: String = generateName()): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+：MediaStore
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/WallpaperExtend"
                    )
                }
                val uri = resolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
                ) ?: return null
                resolver.openOutputStream(uri).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out!!)
                }
                Toast.makeText(context, "已保存到相册 /WallpaperExtend", Toast.LENGTH_SHORT).show()
                uri.toString()
            } else {
                // Android 9-：直接写文件
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "WallpaperExtend"
                )
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, filename)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                // 通知媒体库扫描
                MediaStore.Images.Media.insertImage(
                    context.contentResolver, file.absolutePath, file.name, null
                )
                Toast.makeText(context, "已保存到 ${file.absolutePath}", Toast.LENGTH_SHORT).show()
                file.absolutePath
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "保存失败：${e.message}", Toast.LENGTH_LONG).show()
            null
        }
    }

    private fun generateName(): String {
        val time = System.currentTimeMillis()
        return "wallpaper_extend_$time.png"
    }
}
