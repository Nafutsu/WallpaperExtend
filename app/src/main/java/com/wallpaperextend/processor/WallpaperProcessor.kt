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

object WallpaperProcessor {

    enum class Mode { LIGHT, DARK }
    enum class ExtendDirection { TOP, BOTTOM, BOTH }

    data class Config(
        val blurRadius: Int = 28,
        val extendRatio: Float = 0.25f,
        val featherWidth: Int = 60,
        val direction: ExtendDirection = ExtendDirection.TOP,
        val mode: Mode = Mode.LIGHT
    )

    fun process(src: Bitmap, targetW: Int, targetH: Int, config: Config = Config()): Bitmap {
        if (targetW <= 0 || targetH <= 0) return src

        val ratio: Float = config.extendRatio.coerceIn(0.05f, 0.6f)

        val topExtendH: Int = if (config.direction == ExtendDirection.TOP || config.direction == ExtendDirection.BOTH) {
            (targetH * ratio).toInt().coerceAtLeast(0)
        } else 0

        val bottomExtendH: Int = if (config.direction == ExtendDirection.BOTTOM || config.direction == ExtendDirection.BOTH) {
            (targetH * ratio).toInt().coerceAtLeast(0)
        } else 0

        val scaledW: Int = targetW
        val scaledH: Int = (src.height.toFloat() * targetW.toFloat() / src.width.toFloat()).toInt().coerceAtLeast(1)
        val canvasH: Int = scaledH + topExtendH + bottomExtendH

        val out = Bitmap.createBitmap(targetW, canvasH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(if (config.mode == Mode.LIGHT) Color.WHITE else Color.BLACK)

        val scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)

        val srcDrawY: Int = topExtendH

        // 上方延展
        if (topExtendH > 0) {
            drawExtension(canvas, scaled, targetW, topExtendH, config, isTop = true)
        }

        // 下方延展
        if (bottomExtendH > 0) {
            drawExtension(canvas, scaled, targetW, bottomExtendH, config, isTop = false)
        }

        // 画原图
        val srcPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(scaled, 0f, srcDrawY.toFloat(), srcPaint)

        if (scaled !== src) scaled.recycle()
        return out
    }

    private fun drawExtension(
        canvas: Canvas,
        scaled: Bitmap,
        targetW: Int,
        extendH: Int,
        config: Config,
        isTop: Boolean
    ) {
        val feather: Int = config.featherWidth.coerceIn(16, 200)
        val blurRadius: Int = config.blurRadius.coerceIn(0, 80)

        // 取原图边缘 1/4 区域
        val sampleH: Int = max(6, scaled.height / 4)
        val sampleY: Int = if (isTop) 0 else (scaled.height - sampleH).coerceAtLeast(0)
        val edgeStrip = Bitmap.createBitmap(scaled, 0, sampleY, scaled.width, sampleH)

        // 拉伸到延展区尺寸
        val stretched = Bitmap.createScaledBitmap(edgeStrip, targetW, extendH, true)
        edgeStrip.recycle()

        // 双层模糊
        val heavyBlur = stackBlur(Bitmap.createBitmap(stretched), blurRadius)
        val lightBlur = stackBlur(Bitmap.createBitmap(stretched), max(8, blurRadius / 3))

        stretched.recycle()

        val drawY: Float = if (isTop) 0f else (canvas.height - extendH).toFloat()

        // 画强模糊底色
        val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(heavyBlur, 0f, drawY, bitmapPaint)

        // 画轻模糊，用 SCREEN 混合
        val blendPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        blendPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        val gradientYStart: Float = if (isTop) drawY + (extendH * 0.3f) else drawY + (extendH * 0.7f)
        val gradientYEnd: Float = if (isTop) drawY + (extendH * 0.7f) else drawY + (extendH * 0.3f)
        blendPaint.shader = LinearGradient(
            0f, gradientYStart, 0f, gradientYEnd,
            intArrayOf(Color.TRANSPARENT, Color.argb(120, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawBitmap(lightBlur, 0f, drawY, blendPaint)
        blendPaint.shader = null
        blendPaint.xfermode = null

        // 颜色统一覆盖
        val edgeAvg: Int = if (isTop) sampleTopEdgeColor(scaled) else sampleBottomEdgeColor(scaled)
        val tone: Int = lighten(edgeAvg, factor = 0.6f)
        val tonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(30, Color.red(tone), Color.green(tone), Color.blue(tone))
        }
        canvas.drawRect(0f, drawY, targetW.toFloat(), drawY + extendH, tonePaint)

        // 接缝融合
        val layerId: Int = canvas.saveLayer(0f, drawY, targetW.toFloat(), drawY + extendH, null)

        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)

        if (isTop) {
            // 顶部延展：底部淡出
            maskPaint.shader = LinearGradient(
                0f, drawY + (extendH - feather),
                0f, drawY + extendH,
                intArrayOf(Color.BLACK, Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, drawY + (extendH - feather), targetW.toFloat(), drawY + extendH, maskPaint)

            // 柔和过渡
            val softBlend = Paint(Paint.ANTI_ALIAS_FLAG)
            softBlend.shader = LinearGradient(
                0f, drawY + (extendH - feather * 2),
                0f, drawY + extendH + (feather / 2),
                intArrayOf(Color.TRANSPARENT, Color.argb(40, Color.red(tone), Color.green(tone), Color.blue(tone))),
                floatArrayOf(0.3f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, drawY + (extendH - feather * 2), targetW.toFloat(),
                drawY + extendH + (feather / 2), softBlend)
        } else {
            // 底部延展：顶部淡出
            maskPaint.shader = LinearGradient(
                0f, drawY,
                0f, drawY + feather,
                intArrayOf(Color.TRANSPARENT, Color.BLACK),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, drawY, targetW.toFloat(), drawY + feather, maskPaint)

            val softBlend = Paint(Paint.ANTI_ALIAS_FLAG)
            softBlend.shader = LinearGradient(
                0f, drawY - (feather / 2),
                0f, drawY + (feather * 2),
                intArrayOf(Color.argb(40, Color.red(tone), Color.green(tone), Color.blue(tone)), Color.TRANSPARENT),
                floatArrayOf(0f, 0.7f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, drawY - (feather / 2), targetW.toFloat(),
                drawY + (feather * 2), softBlend)
        }

        maskPaint.shader = null
        maskPaint.xfermode = null
        canvas.restoreToCount(layerId)

        heavyBlur.recycle()
        lightBlur.recycle()
    }

    private fun sampleBottomEdgeColor(src: Bitmap, ratio: Float = 0.15f): Int {
        val h: Int = max(1, (src.height * ratio).toInt())
        var r: Long = 0; var g: Long = 0; var b: Long = 0; var count: Long = 0
        val stepX: Int = max(1, src.width / 64)
        val stepY: Int = max(1, h / 8)
        val startY: Int = src.height - h
        for (y in startY until src.height step stepY) {
            for (x in 0 until src.width step stepX) {
                val p: Int = src.getPixel(x, y)
                r += Color.red(p); g += Color.green(p); b += Color.blue(p)
                count++
            }
        }
        if (count == 0L) return Color.WHITE
        return Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }

    private fun sampleTopEdgeColor(src: Bitmap, ratio: Float = 0.15f): Int {
        val h: Int = max(1, (src.height * ratio).toInt())
        var r: Long = 0; var g: Long = 0; var b: Long = 0; var count: Long = 0
        val stepX: Int = max(1, src.width / 64)
        val stepY: Int = max(1, h / 8)
        for (y in 0 until h step stepY) {
            for (x in 0 until src.width step stepX) {
                val p: Int = src.getPixel(x, y)
                r += Color.red(p); g += Color.green(p); b += Color.blue(p)
                count++
            }
        }
        if (count == 0L) return Color.WHITE
        return Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }

    private fun lighten(c: Int, factor: Float = 0.5f): Int {
        val f: Float = factor.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(c) + (255 - Color.red(c)) * f).toInt().coerceIn(0, 255),
            (Color.green(c) + (255 - Color.green(c)) * f).toInt().coerceIn(0, 255),
            (Color.blue(c) + (255 - Color.blue(c)) * f).toInt().coerceIn(0, 255)
        )
    }

    /* ================= 栈模糊 ================= */
    private fun stackBlur(b: Bitmap, radius: Int): Bitmap {
        if (radius <= 0) return b
        val w: Int = b.width
        val h: Int = b.height
        if (w <= 0 || h <= 0) return b
        val pixels = IntArray(w * h)
        b.getPixels(pixels, 0, w, 0, 0, w, h)
        stackBlurH(pixels, w, h, radius)
        stackBlurV(pixels, w, h, radius)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun stackBlurH(pixels: IntArray, w: Int, h: Int, radius: Int) {
        val rad: Int = radius.coerceIn(1, (w - 1) / 2)
        val div: Int = (2 * rad + 1).coerceAtLeast(1)
        val dv = IntArray(256 * div) { it / div }
        for (y in 0 until h) {
            var sumR = 0; var sumG = 0; var sumB = 0; var sumA = 0
            for (i in -rad..rad) {
                val xi: Int = (i + w) % w
                val p: Int = pixels[y * w + xi]
                sumR += Color.red(p); sumG += Color.green(p); sumB += Color.blue(p); sumA += Color.alpha(p)
            }
            for (x in 0 until w) {
                val outIdx: Int = y * w + x
                pixels[outIdx] = Color.argb(
                    dv[sumA.coerceIn(0, 255 * div)],
                    dv[sumR.coerceIn(0, 255 * div)],
                    dv[sumG.coerceIn(0, 255 * div)],
                    dv[sumB.coerceIn(0, 255 * div)]
                )
                val xiOut: Int = (x - rad + w) % w
                val xiIn: Int = (x + rad + 1 + w) % w
                val pOut: Int = pixels[y * w + xiOut]
                val pIn: Int = pixels[y * w + xiIn]
                sumR += Color.red(pIn) - Color.red(pOut)
                sumG += Color.green(pIn) - Color.green(pOut)
                sumB += Color.blue(pIn) - Color.blue(pOut)
                sumA += Color.alpha(pIn) - Color.alpha(pOut)
            }
        }
    }

    private fun stackBlurV(pixels: IntArray, w: Int, h: Int, radius: Int) {
        val rad: Int = radius.coerceIn(1, (h - 1) / 2)
        val div: Int = (2 * rad + 1).coerceAtLeast(1)
        val dv = IntArray(256 * div) { it / div }
        for (x in 0 until w) {
            var sumR = 0; var sumG = 0; var sumB = 0; var sumA = 0
            for (i in -rad..rad) {
                val yi: Int = ((i + h) % h) * w + x
                sumR += Color.red(pixels[yi]); sumG += Color.green(pixels[yi]); sumB += Color.blue(pixels[yi]); sumA += Color.alpha(pixels[yi])
            }
            for (y in 0 until h) {
                val outIdx: Int = y * w + x
                pixels[outIdx] = Color.argb(
                    dv[sumA.coerceIn(0, 255 * div)],
                    dv[sumR.coerceIn(0, 255 * div)],
                    dv[sumG.coerceIn(0, 255 * div)],
                    dv[sumB.coerceIn(0, 255 * div)]
                )
                val yiOut: Int = ((y - rad + h) % h) * w + x
                val yiIn: Int = ((y + rad + 1 + h) % h) * w + x
                sumR += Color.red(pixels[yiIn]) - Color.red(pixels[yiOut])
                sumG += Color.green(pixels[yiIn]) - Color.green(pixels[yiOut])
                sumB += Color.blue(pixels[yiIn]) - Color.blue(pixels[yiOut])
                sumA += Color.alpha(pixels[yiIn]) - Color.alpha(pixels[yiOut])
            }
        }
    }
}
