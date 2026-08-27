package com.wallpaperextend.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.exifinterface.media.ExifInterface as AndroidXExif

object ImageLoader {

    /** 从 Uri 加载并做采样缩放，避免 OOM */
    fun loadFromUri(context: Context, uri: Uri, maxSide: Int = 1600): Bitmap {
        val inputStream = context.contentResolver.openInputStream(uri)!!
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(inputStream, null, opts)
        inputStream.close()

        opts.inSampleSize = calculateInSampleSize(opts.outWidth, opts.outHeight, maxSide)
        opts.inJustDecodeBounds = false

        val stream2 = context.contentResolver.openInputStream(uri)!!
        var bitmap = BitmapFactory.decodeStream(stream2, null, opts)!!
        stream2.close()

        // 处理 EXIF 旋转
        bitmap = rotateByExif(context, uri, bitmap)
        return bitmap
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxSide: Int): Int {
        var sample = 1
        val larger = width.coerceAtLeast(height)
        while (larger / (sample * 2) >= maxSide) sample *= 2
        return sample
    }

    private fun rotateByExif(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        return try {
            val stream = context.contentResolver.openInputStream(uri)!!
            val exif = AndroidXExif(stream)
            stream.close()
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                else -> return bitmap
            }
            val rotated = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )
            if (rotated != bitmap) bitmap.recycle()
            rotated
        } catch (e: Exception) {
            bitmap
        }
    }
}
