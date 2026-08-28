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

    fun saveToGallery(context: Context, bitmap: Bitmap, filename: String): Boolean {
        if (bitmap.isRecycled || bitmap.width == 0 || bitmap.height == 0) {
            return false
        }

        return try {
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveWithMediaStore(context, bitmap, filename)
            } else {
                saveLegacy(context, bitmap, filename)
            }
            result
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
            val out = resolver.openOutputStream(uri)
            if (out == null) {
                resolver.delete(uri, null, null)
                return false
            }
            out.use {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) {
                    resolver.delete(uri, null, null)
                    return false
                }
            }
            val finish = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(uri, finish, null, null)
            return true
        } catch (e: Exception) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            return false
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, bitmap: Bitmap, filename: String): Boolean {
        return try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "WallpaperExtend"
            )
            if (!dir.exists() && !dir.mkdirs()) return false

            val file = File(dir, filename)
            FileOutputStream(file).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    return false
                }
            }
            MediaStore.Images.Media.insertImage(
                context.contentResolver,
                file.absolutePath,
                file.name,
                null
            )
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
