package com.wallpaperextend.processor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

object WallpaperProcessor {
    enum class Mode { LIGHT, DARK }

    data class Config(
        val blurRadius: Int = 32,
        val extendRatio: Float = 0.37f,
        val featherWidth: Int = 100,
        val topOnly: Boolean = true,
        val mode: Mode = Mode.LIGHT
    )

    fun process(src: Bitmap, targetW: Int, targetH: Int, config: Config = Config()): Bitmap {
        if (targetW <= 0 || targetH <= 0) return src

        val edgeColor = sampleTopEdgeColor(src, ratio = 0.05f)
        val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(edgeColor)

        val scaledW = targetW
        val scaledH = ceil(src.height * targetW.toFloat() / src.width).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)

        val maxExtend = (targetH * config.extendRatio.coerceIn(0.05f, 0.6f)).toInt()
        val extendH = (targetH - scaledH).coerceAtLeast(0).coerceAtMost(maxExtend)

        val srcDrawY = (targetH - scaledH).toFloat()

        if (extendH > 0) {
            drawTopExtension(
                canvas, scaled, src, targetW, extendH,
                config.blurRadius, config.featherWidth
            )
        }

        canvas.drawBitmap(
            scaled,
            0f,
            srcDrawY,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )

        if (srcDrawY + scaledH < targetH) {
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = edgeColor }
            canvas.drawRect(
                0f,
                (srcDrawY + scaledH).coerceAtMost(targetH.toFloat()),
                0f + targetW,
                targetH.toFloat(),
                fill
            )
        }

        if (scaled !== src) scaled.recycle()
        return out
    }

    private fun drawTopExtension(
        canvas: Canvas,
        scaled: Bitmap,
        src: Bitmap,
        targetW: Int,
        extendH: Int,
        blurRadius: Int,
        feather: Int
    ) {
        if (extendH <= 0) return

        // 1. 采样
        val stripH = max(8, scaled.height / 6)
        val topStrip = Bitmap.createBitmap(scaled, 0, 0, scaled.width, stripH)

        // 2. 旋转 180°
        val rotated = Bitmap.createBitmap(
            topStrip, 0, 0, topStrip.width, topStrip.height,
            Matrix().apply { setRotate(180f) }, true
        )
        topStrip.recycle()

        // 3. 拉伸
        val stretched = Bitmap.createScaledBitmap(rotated, targetW, extendH, true)
        rotated.recycle()

        // 4. 模糊
        val blurred = stackBlur(stretched, blurRadius.coerceIn(0, 80))
        if (blurred !== stretched) stretched.recycle()

        // 5. 极淡色调底色（防色差，不产生倒影）
        val topAvg = sampleTopEdgeColor(src, ratio = 0.12f)
        val tone = lighten(topAvg, factor = 0.2f)
        val tonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(20, Color.red(tone), Color.green(tone), Color.blue(tone))
        }
        canvas.drawRect(0f, 0f, targetW.toFloat(), extendH.toFloat(), tonePaint)

        // 6. 绘制模糊层
        canvas.drawBitmap(
            blurred,
            0f, 0f,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )

        // 7. 单一渐变融合（DST_OUT，模糊层靠近原图处淡出）
        val effectiveFeather = feather.coerceAtMost(extendH)
        if (effectiveFeather > 0) {
            val blendTop = (extendH - effectiveFeather).toFloat()
            val blendBottom = extendH.toFloat()

            val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                shader = LinearGradient(
                    0f, blendTop,
                    0f, blendBottom,
                    Color.TRANSPARENT,
                    Color.BLACK,
                    Shader.TileMode.CLAMP
                )
            }

            val layerId = canvas.saveLayer(
                0f, blendTop,
                targetW.toFloat(), extendH.toFloat(), null
            )
            canvas.drawRect(
                0f, blendTop,
                targetW.toFloat(), extendH.toFloat(),
                fadePaint
            )
            canvas.restoreToCount(layerId)
        }

        // 8. 微渐变柔化接缝（最多 4px，不产生倒影）
        val microFeather = min(4, effectiveFeather / 20)
        if (microFeather > 0) {
            val microPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, extendH.toFloat(),
                    0f, (extendH + microFeather).toFloat(),
                    Color.argb(15, Color.red(tone), Color.green(tone), Color.blue(tone)),
                    Color.argb(0, Color.red(tone), Color.green(tone), Color.blue(tone)),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(
                0f, extendH.toFloat(),
                targetW.toFloat(), (extendH + microFeather).toFloat(),
                microPaint
            )
        }

        if (blurred !== scaled) blurred.recycle()
    }

    private fun sampleTopEdgeColor(src: Bitmap, ratio: Float = 0.1f): Int {
        val h = max(1, (src.height * ratio).toInt())
        var r = 0L; var g = 0L; var b = 0L; var count = 0L
        val stepX = max(1, src.width / 48)
        val stepY = max(1, h / 4)
        for (y in 0 until h step stepY) {
            for (x in 0 until src.width step stepX) {
                val p = src.getPixel(x, y)
                r += Color.red(p); g += Color.green(p); b += Color.blue(p)
                count++
            }
        }
        if (count == 0L) return Color.BLACK
        return Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }

    private fun lighten(c: Int, factor: Float = 0.5f): Int {
        val f = factor.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(c) + (255 - Color.red(c)) * f).toInt().coerceIn(0, 255),
            (Color.green(c) + (255 - Color.green(c)) * f).toInt().coerceIn(0, 255),
            (Color.blue(c) + (255 - Color.blue(c)) * f).toInt().coerceIn(0, 255)
        )
    }

    /* ================= 栈模糊 ================= */
    private fun stackBlur(b: Bitmap, radius: Int): Bitmap {
        if (radius <= 0) return b
        val w = b.width; val h = b.height
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
        val rad = radius.coerceIn(1, (w - 1) / 2)
        val div = (2 * rad + 1).coerceAtLeast(1)
        val dv = IntArray(256 * div) { it / div }
        for (y in 0 until h) {
            var sumR = 0; var sumG = 0; var sumB = 0; var sumA = 0
            for (i in -rad..rad) {
                val xi = (i + w) % w
                val p = pixels[y * w + xi]
                sumR += Color.red(p); sumG += Color.green(p); sumB += Color.blue(p); sumA += Color.alpha(p)
            }
            for (x in 0 until w) {
                val outIdx = y * w + x
                pixels[outIdx] = Color.argb(
                    dv[sumA.coerceIn(0, 255 * div)],
                    dv[sumR.coerceIn(0, 255 * div)],
                    dv[sumG.coerceIn(0, 255 * div)],
                    dv[sumB.coerceIn(0, 255 * div)]
                )
                val xiOut = (x - rad + w) % w
                val xiIn = (x + rad + 1 + w) % w
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
        val rad = radius.coerceIn(1, (h - 1) / 2)
        val div = (2 * rad + 1).coerceAtLeast(1)
        val dv = IntArray(256 * div) { it / div }
        for (x in 0 until w) {
            var sumR = 0; var sumG = 0; var sumB = 0; var sumA = 0
            for (i in -rad..rad) {
                val yi = ((i + h) % h) * w + x
                sumR += Color.red(pixels[yi]); sumG += Color.green(pixels[yi]); sumB += Color.blue(pixels[yi]); sumA += Color.alpha(pixels[yi])
            }
            for (y in 0 until h) {
                val outIdx = y * w + x
                pixels[outIdx] = Color.argb(
                    dv[sumA.coerceIn(0, 255 * div)],
                    dv[sumR.coerceIn(0, 255 * div)],
                    dv[sumG.coerceIn(0, 255 * div)],
                    dv[sumB.coerceIn(0, 255 * div)]
                )
                val yiOut = ((y - rad + h) % h) * w + x
                val yiIn = ((y + rad + 1 + h) % h) * w + x
                sumR += Color.red(pixels[yiIn]) - Color.red(pixels[yiOut])
                sumG += Color.green(pixels[yiIn]) - Color.green(pixels[yiOut])
                sumB += Color.blue(pixels[yiIn]) - Color.blue(pixels[yiOut])
                sumA += Color.alpha(pixels[yiIn]) - Color.alpha(pixels[yiOut])
            }
        }
    }
}
