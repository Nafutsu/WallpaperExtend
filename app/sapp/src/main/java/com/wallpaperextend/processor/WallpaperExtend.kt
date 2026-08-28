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

object WallpaperExtend {

    /**
     * 顶部延伸（PS 风格）
     */
    fun extendTop(
        src: Bitmap,
        extendH: Int,
        featherH: Int = 80,
        blurRadius: Int = 3
    ): Bitmap {

        val w = src.width
        val h = src.height
        val eh = max(0, extendH)
        if (eh == 0) return src

        val fh = max(8, featherH)

        // 最终画布
        val out = Bitmap.createBitmap(w, h + eh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        // 1️⃣ 取顶部背景平均色
        val sampleH = max(8, (h * 0.08).toInt())
        val baseColor = averageColor(src, 0, 0, w, sampleH)

        // 背景色
        canvas.drawColor(baseColor)

        // 2️⃣ PS 式拉伸（顶部条）
        val stripH = max(16, (h * 0.18).toInt())
        val strip = Bitmap.createBitmap(src, 0, 0, w, stripH)
        val stretched = Bitmap.createScaledBitmap(strip, w, eh + fh, true)

        val stretchPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            alpha = 130
        }
        canvas.drawBitmap(stretched, 0f, 0f, stretchPaint)

        // 3️⃣ 轻模糊（不是糊成雾）
        if (blurRadius > 0) {
            val blurLayer = Bitmap.createBitmap(out, 0, 0, w, eh + fh)
            val blurred = fastBlur(blurLayer, blurRadius)
            if (blurred != null) {
                val bp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    alpha = 80
                }
                canvas.drawBitmap(blurred, 0f, 0f, bp)
                blurred.recycle()
            }
            blurLayer.recycle()
        }

        stretched.recycle()
        strip.recycle()

        // 4️⃣ 原图（清晰）
        canvas.drawBitmap(src, 0f, eh.toFloat(), null)

        // 5️⃣ 接缝羽化（画线位置）
        val grad = LinearGradient(
            0f, (eh - fh).toFloat(),
            0f, eh.toFloat(),
            intArrayOf(
                Color.argb(0, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)),
                baseColor
            ),
            null,
            Shader.TileMode.CLAMP
        )

        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = grad
        }
        canvas.drawRect(0f, (eh - fh).toFloat(), w.toFloat(), eh.toFloat(), maskPaint)

        return out
    }

    private fun averageColor(bmp: Bitmap, l: Int, t: Int, r: Int, b: Int): Int {
        var left = l.coerceIn(0, bmp.width - 1)
        var top = t.coerceIn(0, bmp.height - 1)
        var right = r.coerceIn(1, bmp.width)
        var bottom = b.coerceIn(1, bmp.height)

        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0L

        val step = 4
        for (y in top until bottom step step) {
            for (x in left until right step step) {
                val px = bmp.getPixel(x, y)
                red += Color.red(px)
                green += Color.green(px)
                blue += Color.blue(px)
                count++
            }
        }
        if (count == 0L) return Color.LTGRAY
        return Color.rgb(
            (red / count).toInt(),
            (green / count).toInt(),
            (blue / count).toInt()
        )
    }

    private fun fastBlur(sent: Bitmap, radius: Int): Bitmap? {
        if (radius <= 0) return null
        val r = radius.coerceIn(1, 25)
        val scale = 1f / (1 + r * 0.25f)
        val smallW = max(1, (sent.width * scale).toInt())
        val smallH = max(1, (sent.height * scale).toInt())

        val small = Bitmap.createBitmap(smallW, smallH, Bitmap.Config.ARGB_8888)
        Canvas(small).drawBitmap(
            sent,
            android.graphics.Matrix().apply { setScale(scale, scale) },
            Paint(Paint.FILTER_BITMAP_FLAG)
        )

        val result = Bitmap.createScaledBitmap(small, sent.width, sent.height, true)
        small.recycle()
        return result
    }
}

