package com.wallpaperextend.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

object ImageSaver {

    /**
     * 保存 Bitmap 到相册 Pictures/WallpaperExtend。
     * - Android 10+：MediaStore + IS_PENDING 原子可见
     * - Android 9-：直接写文件 + 媒体库扫描
     *
     * @return true 成功，false 失败（异常已内部捕获并记录）
     */
    fun saveToGallery(context: Context, bitmap: Bitmap, filename: String): Boolean {
        if (bitmap.isRecycled || bitmap.width == 0 || bitmap.height == 0) return false

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveWithMediaStore(context, bitmap, filename)
            } else {
                saveLegacy(context, bitmap, filename)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun saveWithMediaStore(context: Context, bitmap: Bitmap, filename: String): Boolean {
        val resolver = context.contentResolver
        val relativePath = "${Environment.DIRECTORY_PICTURES}/WallpaperExtend"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false

        try {
            resolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw RuntimeException("compress returned false")
                }
            } ?: throw RuntimeException("openOutputStream returned null")

            // 提交：关闭 pending，对外可见
            val finish = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(uri, finish, null, null)
            true
        } catch (e: Exception) {
            // 失败清理占位 URI
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, bitmap: Bitmap, filename: String): Boolean {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "WallpaperExtend"
        )
        if (!dir.exists() && !dir.mkdirs()) return false

        val file = File(dir, filename)
        FileOutputStream(file).use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw RuntimeException("compress returned false")
            }
        }

        // 通知媒体库扫描（insertImage 内部会处理）
        MediaStore.Images.Media.insertImage(
            context.contentResolver,
            file.absolutePath,
            file.name,
            null
        )
        return true
    }
}
