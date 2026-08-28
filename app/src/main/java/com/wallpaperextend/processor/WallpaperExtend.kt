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
import kotlin.math.min

object WallpaperExtend {

    fun extendTop(src: Bitmap, extendH: Int, featherH: Int, blurRadius: Int): Bitmap {
        val safe = ensureOpaque(src)
        val w = safe.width
        val h = safe.height
        val topH = extendH.coerceAtLeast(0)
        val totalH = topH + h

        val out = Bitmap.createBitmap(w, totalH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        if (topH > 0) {
            drawTopExtension(canvas, safe, w, topH, blurRadius, featherH)
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(safe, 0f, topH.toFloat(), paint)

        if (safe !== src) safe.recycle()
        return out
    }

    fun extendBottom(src: Bitmap, extendH: Int, featherH: Int, blurRadius: Int): Bitmap {
        val safe = ensureOpaque(src)
        val w = safe.width
        val h = safe.height
        val bottomH = extendH.coerceAtLeast(0)
        val totalH = h + bottomH

        val out = Bitmap.createBitmap(w, totalH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(safe, 0f, 0f, paint)

        if (bottomH > 0) {
            drawBottomExtension(canvas, safe, w, h, bottomH, blurRadius, featherH)
        }

        if (safe !== src) safe.recycle()
        return out
    }

    private fun drawTopExtension(
        canvas: Canvas, src: Bitmap, w: Int, topH: Int, blurRadius: Int, feather: Int
    ) {
        // 取原图顶部 1/4，保留纹理（修复：之前 /40 只取几像素导致死色块）
        val stripH = (src.height / 4).coerceIn(30, src.height / 2)
        val topStrip = Bitmap.createBitmap(src, 0, 0, src.width, min(stripH, src.height))
        val stretched = Bitmap.createScaledBitmap(topStrip, w, topH, true)
        topStrip.recycle()

        val blurred = stackBlur(stretched, blurRadius.coerceIn(1, 60))
        stretched.recycle()

        // 画模糊底色（铺满整个延展区）
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(blurred, 0f, 0f, paint)

        // 轻色调统一
        val topAvg = sampleTopColor(src, 0.2f)
        val tonePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        tonePaint.color = Color.argb(20,
            Color.red(topAvg), Color.green(topAvg), Color.blue(topAvg))
        canvas.drawRect(0f, 0f, w.toFloat(), topH.toFloat(), tonePaint)

        // 接缝融合：靠近原图处保留，远离处淡出
        // 修复：渐变方向改为 BLACK -> TRANSPARENT（之前反了导致黑缝）
        val f = feather.coerceIn(8, topH)
        val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        fadePaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        fadePaint.shader = LinearGradient(
            0f, (topH - f).toFloat(), 0f, topH.toFloat(),
            intArrayOf(Color.BLACK, Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, (topH - f).toFloat(), w.toFloat(), topH.toFloat(), fadePaint)
        blurred.recycle()
    }

    private fun drawBottomExtension(
        canvas: Canvas, src: Bitmap, w: Int, srcH: Int, bottomH: Int, blurRadius: Int, feather: Int
    ) {
        val stripH = (src.height / 4).coerceIn(30, src.height / 2)
        val h = min(stripH, src.height)
        val bottomStrip = Bitmap.createBitmap(src, 0, src.height - h, src.width, h)
        val stretched = Bitmap.createScaledBitmap(bottomStrip, w, bottomH, true)
        bottomStrip.recycle()

        val blurred = stackBlur(stretched, blurRadius.coerceIn(1, 60))
        stretched.recycle()

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(blurred, 0f, srcH.toFloat(), paint)

        val bottomAvg = sampleBottomColor(src, 0.2f)
        val tonePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        tonePaint.color = Color.argb(20,
            Color.red(bottomAvg), Color.green(bottomAvg), Color.blue(bottomAvg))
        canvas.drawRect(0f, srcH.toFloat(), w.toFloat(), (srcH + bottomH).toFloat(), tonePaint)

        val f = feather.coerceIn(8, bottomH)
        val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        fadePaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        fadePaint.shader = LinearGradient(
            0f, srcH.toFloat(), 0f, (srcH + f).toFloat(),
            intArrayOf(Color.BLACK, Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, srcH.toFloat(), w.toFloat(), (srcH + f).toFloat(), fadePaint)
        blurred.recycle()
    }

    private fun sampleTopColor(src: Bitmap, ratio: Float): Int {
        val sample = Bitmap.createScaledBitmap(src, 32, 32, true)
        var r = 0; var g = 0; var b = 0; var count = 0
        val endY = max(1, (sample.height * ratio).toInt())
        for (y in 0 until endY) {
            for (x in 0 until sample.width) {
                val c = sample.getPixel(x, y)
                if (Color.alpha(c) < 128) continue
                r += Color.red(c); g += Color.green(c); b += Color.blue(c); count++
            }
        }
        sample.recycle()
        if (count == 0) return Color.WHITE
        return Color.rgb(r / count, g / count, b / count)
    }

    private fun sampleBottomColor(src: Bitmap, ratio: Float): Int {
        val sample = Bitmap.createScaledBitmap(src, 32, 32, true)
        var r = 0; var g = 0; var b = 0; var count = 0
        val startY = max(0, sample.height - (sample.height * ratio).toInt())
        for (y in startY until sample.height) {
            for (x in 0 until sample.width) {
                val c = sample.getPixel(x, y)
                if (Color.alpha(c) < 128) continue
                r += Color.red(c); g += Color.green(c); b += Color.blue(c); count++
            }
        }
        sample.recycle()
        if (count == 0) return Color.WHITE
        return Color.rgb(r / count, g / count, b / count)
    }

    private fun ensureOpaque(src: Bitmap): Bitmap {
        if (!src.hasAlpha()) return src
        val b = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(b).apply {
            drawColor(Color.WHITE)
            drawBitmap(src, 0f, 0f, null)
        }
        return b
    }

    private fun stackBlur(s: Bitmap, radius: Int): Bitmap {
        val r = radius.coerceIn(1, 120)
        val w = s.width
        val h = s.height
        if (w <= 0 || h <= 0) return s

        val MAX_DIM = 1024
        val work = if (max(w, h) > MAX_DIM) {
            val scale = MAX_DIM.toFloat() / max(w, h)
            Bitmap.createScaledBitmap(s, max(1, (w * scale).toInt()), max(1, (h * scale).toInt()), true)
        } else {
            s
        }
        val ww = work.width
        val hh = work.height
        val pixels = IntArray(ww * hh)
        work.getPixels(pixels, 0, ww, 0, 0, ww, hh)

        val maxRad = (min(ww, hh) - 1) / 2
        val rad = min(r, maxRad).coerceAtLeast(1)

        try {
            stackBlurH(pixels, ww, hh, rad)
            stackBlurV(pixels, ww, hh, rad)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val out = Bitmap.createBitmap(ww, hh, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, ww, 0, 0, ww, hh)
        if (work !== s) work.recycle()
        return out
    }

    private fun stackBlurH(pixels: IntArray, w: Int, h: Int, radius: Int) {
        val div = (2 * radius + 1).coerceAtLeast(1)
        val dv = IntArray(256 * div)
        for (i in 0 until 256 * div) dv[i] = i / div

        for (y in 0 until h) {
            var sumR = 0; var sumG = 0; var sumB = 0; var sumA = 0
            for (i in -radius..radius) {
                val xi = (i + w) % w
                val p = pixels[y * w + xi]
                sumR += Color.red(p); sumG += Color.green(p); sumB += Color.blue(p); sumA += Color.alpha(p)
            }
            for (x in 0 until w) {
                pixels[y * w + x] = Color.argb(
                    dv[sumA.coerceIn(0, 255 * div)],
                    dv[sumR.coerceIn(0, 255 * div)],
                    dv[sumG.coerceIn(0, 255 * div)],
                    dv[sumB.coerceIn(0, 255 * div)]
                )
                val xiOut = (x - radius + w) % w
                val xiIn = (x + radius + 1 + w) % w
                val pOut = pixels[y * w + xiOut]
                val pIn = pixels[y * w + xiIn]
                sumR += Color.red(pIn) - Color.red(pOut)
                sumG += Color.green(pIn) - Color.green(pOut)
                sumB += Color.blue(pIn) - Color.blue(pOut)
                sumA += Color.alpha(pIn) - Color.alpha(pOut)
            }
        }
    }

    private fun stackBlurV(pixels: IntArray, w: Int, h: Int, radius: Int) {
        val div = (2 * radius + 1).coerceAtLeast(1)
        val dv = IntArray(256 * div)
        for (i in 0 until 256 * div) dv[i] = i / div

        for (x in 0 until w) {
            var sumR = 0; var sumG = 0; var sumB = 0; var sumA = 0
            for (i in -radius..radius) {
                val yi = ((i + h) % h) * w + x
                sumR += Color.red(pixels[yi]); sumG += Color.green(pixels[yi]); sumB += Color.blue(pixels[yi]); sumA += Color.alpha(pixels[yi])
            }
            for (y in 0 until h) {
                pixels[y * w + x] = Color.argb(
                    dv[sumA.coerceIn(0, 255 * div)],
                    dv[sumR.coerceIn(0, 255 * div)],
                    dv[sumG.coerceIn(0, 255 * div)],
                    dv[sumB.coerceIn(0, 255 * div)]
                )
                val yiOut = ((y - radius + h) % h) * w + x
                val yiIn = ((y + radius + 1 + h) % h) * w + x
                sumR += Color.red(pixels[yiIn]) - Color.red(pixels[yiOut])
                sumG += Color.green(pixels[yiIn]) - Color.green(pixels[yiOut])
                sumB += Color.blue(pixels[yiIn]) - Color.blue(pixels[yiOut])
                sumA += Color.alpha(pixels[yiIn]) - Color.alpha(pixels[yiOut])
            }
        }
    }
}
