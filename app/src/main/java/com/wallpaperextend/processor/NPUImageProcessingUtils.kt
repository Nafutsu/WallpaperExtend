package com.wallpaperextend.processor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import kotlin.math.max

object ImageProcessingUtils {

    /**
     * 采样图片顶部区域的平均色（RGB）
     */
    fun sampleTopAverageColor(src: Bitmap, ratio: Float = 0.1f): FloatArray {
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        var r = 0f; var g = 0f; var b = 0f; var count = 0
        val stepX = max(1, src.width / 40)
        val stepY = max(1, h / 4)
        for (y in 0 until h step stepY) {
            for (x in 0 until src.width step stepX) {
                val pixel = src.getPixel(x, y)
                r += Color.red(pixel)
                g += Color.green(pixel)
                b += Color.blue(pixel)
                count++
            }
        }
        return if (count == 0) floatArrayOf(0f, 0f, 0f)
        else floatArrayOf(r / count, g / count, b / count)
    }

    /**
     * 将 Bitmap 的颜色映射到目标平均色（线性缩放）
     */
    fun matchColorToTarget(bitmap: Bitmap, targetAvg: FloatArray): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w == 0 || h == 0) return bitmap

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // 计算当前平均色
        var sr = 0f; var sg = 0f; var sb = 0f
        for (p in pixels) {
            sr += Color.red(p)
            sg += Color.green(p)
            sb += Color.blue(p)
        }
        val count = pixels.size.toFloat()
        val srcAvg = floatArrayOf(sr / count, sg / count, sb / count)

        // 防止除零
        val scaleR = if (srcAvg[0] > 1f) targetAvg[0] / srcAvg[0] else 1f
        val scaleG = if (srcAvg[1] > 1f) targetAvg[1] / srcAvg[1] else 1f
        val scaleB = if (srcAvg[2] > 1f) targetAvg[2] / srcAvg[2] else 1f

        for (i in pixels.indices) {
            val r = (Color.red(pixels[i]) * scaleR).toInt().coerceIn(0, 255)
            val g = (Color.green(pixels[i]) * scaleG).toInt().coerceIn(0, 255)
            val b = (Color.blue(pixels[i]) * scaleB).toInt().coerceIn(0, 255)
            pixels[i] = Color.rgb(r, g, b)
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }

    /**
     * 在 Bitmap 底部应用羽化渐变（从下往上 feaatherWidth 像素渐变透明）
     */
    fun applyFeather(bitmap: Bitmap, featherWidth: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w == 0 || h == 0 || featherWidth <= 0) return bitmap

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawBitmap(bitmap, 0f, 0f, null)

        val actualFeather = featherWidth.coerceAtMost(h)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, (h - actualFeather).toFloat(),
                0f, h.toFloat(),
                Color.argb(255, 255, 255, 255),
                Color.argb(0, 255, 255, 255),
                Shader.TileMode.CLAMP
            )
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawRect(
            0f, (h - actualFeather).toFloat(),
            w.toFloat(), h.toFloat(),
            paint
        )
        return result
    }
}
