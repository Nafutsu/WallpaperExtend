package com.wallpaperextend.util

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.content.ContentValues

/**
 * 保存 Bitmap 到系统相册（DCIM/WallpaperExtend/）。
 *
 * 提供两种调用形式，任选其一：
 *   - save(context, bitmap)                          // 自动生成文件名
 *   - save(context, bitmap, "xxx.png")               // 指定文件名
 *
 * @return 成功时返回文件 Uri 的字符串，失败返回 null
 */
object MediaStoreSaver {

    // ===== 两参入口（对齐 MainActivity 的调用）=====
    fun save(context: Context, bitmap: Bitmap): String? {
        val fileName = "WallpaperExtend_${System.currentTimeMillis()}.png"
        return save(context, bitmap, fileName)
    }

    // ===== 三参入口 =====
    fun save(
        context: Context,
        bitmap: Bitmap,
        fileName: String
    ): String? {
        val resolver = context.contentResolver

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DCIM}/WallpaperExtend"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val uri = resolver.insert(collection, contentValues) ?: return null

        return try {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
            uri.toString()
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                resolver.delete(uri, null, null)
            } catch (ignore: Exception) {
            }
            null
        }
    }
}
