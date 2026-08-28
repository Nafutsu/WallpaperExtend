package com.wallpaperextend.processor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import jp.co.cyberagent.android.gpuimage.GPUImage
import jp.co.cyberagent.android.gpuimage.filter.GPUImageGaussianBlurFilter
import kotlin.math.ceil
import kotlin.math.min

object WallpaperProcessor {

    enum class Mode { LIGHT, DARK }

    data class Config(
        val blurRadius: Float = 20f,
        val extendRatio: Float = 0.37f,
        val featherWidth: Int = 150,
        val topOnly: Boolean = true,
        val mode: Mode = Mode.LIGHT,
        val saturationBoost: Float = 1.1f,
        val brightnessOffset: Float = 0f,
        val overlayStrength: Float = 0.15f
    )

    fun process(
        context: Context,
        src: Bitmap,
        targetW: Int,
        targetH: Int,
        config: Config = Config()
    ): Bitmap {
        if (targetW <= 0 || targetH <= 0) return src

        val edgeColor = sampleTopEdgeColor(src, ratio = 0.08f)
        val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(edgeColor)

        val scaledW = targetW
        val scaledH =
            ceil(src.height * targetW.toFloat() / src.width).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)

        val maxExtend = (targetH * config.extendRatio.coerceIn(0.05f, 0.6f)).toInt()
        val extendH = (targetH - scaledH).coerceAtLeast(0).coerceAtMost(maxExtend)

        val srcDrawY = (targetH - scaledH).toFloat()
        canvas.drawBitmap(
            scaled,
            0f,
            srcDrawY,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )

        if (extendH > 0) {
            drawTopExtension(context, canvas, scaled, src, targetW, extendH, config)
        }

        if (srcDrawY + scaledH < targetH) {
            val fill = Paint(Paint(ANTI_ALIAS_FLAG)).apply { color = edgeColor }
            canvas.drawRect(
                0f,
                (srcDrawY + scaledH).coerceAtMost(targetH.toFloat()),
                targetW.toFloat(),
                targetH.toFloat(),
                fill
            )
        }

        if (scaled !== src) scaled.recycle()
        return out
    }

    private fun drawTopExtension(
        context: Context,
        canvas: Canvas,
        scaled: Bitmap,
        src: Bitmap,
        targetW: Int,
        extendH: Int,
        config: Config
    ) {
        if (extendH <= 0) return

        // 1. 取原图顶部区域
        val sourceH = maxOf(16, scaled.height / 4)
        val topSource = Bitmap.createBitmap(
            scaled, 0, 0, scaled.width, minOf(sourceH, scaled.height)
        )

        // 2. 拉伸到延展区尺寸
        val stretched = Bitmap.createScaledBitmap(topSource, targetW, extendH, true)
        topSource.recycle()

        // 3. GPU 多层模糊
        val blurred = try {
            multiLayerGpuBlur(context, stretched, config.blurRadius)
        } catch (e: Exception) {
            // GPU 失败降级到 CPU
            stackBlur(stretched, config.blurRadius.toInt().coerceIn(1, 80))
        }
        if (blurred !== stretched) stretched.recycle()

        // 4. 智能色调蒙版
        val topAvg = sampleTopEdgeColor(src, ratio = 0.12f)
        val luminance = calculateLuminance(topAvg)
        val overlayAlpha = (config.overlayStrength * 255).toInt()

        val overlayColor = when {
            luminance > 0.7 -> Color.argb(overlayAlpha, 255, 255, 255)
            luminance < 0.3 -> Color.argb(overlayAlpha, 0, 0, 0)
            else -> {
                val tone = lighten(topAvg, config.brightnessOffset + 0.1f)
                Color.argb(overlayAlpha, Color.red(tone), Color.green(tone), Color.blue(tone))
            }
        }

        // 5. 绘制
        val effectiveFeather = config.featherWidth.coerceIn(50, 200)
        val overlayBottom = extendH + effectiveFeather
        val overlayBmp = Bitmap.createScaledBitmap(blurred, targetW, overlayBottom, true)
        if (overlayBmp !== blurred) blurred.recycle()

        // 色调蒙版
        val tonePaint = Paint(ANTI_ALIAS_FLAG).apply { color = overlayColor }
        canvas.drawRect(0f, 0f, targetW.toFloat(), overlayBottom.toFloat(), tonePaint)

        // 模糊层
        canvas.drawBitmap(
            overlayBmp,
            0f, 0f,
            Paint(ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )

        // 6. 渐变融合（DST_IN）
        val fadePaint = Paint(ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, extendH.toFloat(),
                0f, overlayBottom.toFloat(),
                Color.argb(255, 255, 255, 255),
                Color.argb(0, 255, 255, 255),
                Shader.TileMode.CLAMP
            )
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }

        val layerId = canvas.saveLayer(
            0f, extendH.toFloat(),
            targetW.toFloat(), overlayBottom.toFloat(),
            null
        )
        canvas.drawRect(
            0f, extendH.toFloat(),
            targetW.toFloat(), overlayBottom.toFloat(),
            fadePaint
        )
        canvas.restoreToCount(layerId)

        if (overlayBmp !== scaled) overlayBmp.recycle()
    }

    // ==================== GPUImage 模糊 ====================

    private fun multiLayerGpuBlur(context: Context, bitmap: Bitmap, radius: Float): Bitmap {
        val r1 = minOf(radius, 25f)
        val result = gpuBlur(context, bitmap, r1)

        // 需要更强模糊时叠加第二层
        return if (radius > 25f) {
            val r2 = minOf(radius * 0.6f, 25f)
            val second = gpuBlur(context, result, r2)
            if (second !== result) result.recycle()
            second
        } else {
            result
        }
    }

    private fun gpuBlur(context: Context, bitmap: Bitmap, radius: Float): Bitmap {
        val gpuImage = GPUImage(context)
        gpuImage.setImage(bitmap)
        val filter = GPUImageGaussianBlurFilter(radius)
        gpuImage.setFilter(filter)
        return gpuImage.bitmapWithFilterApplied ?: bitmap
    }

    // ==================== 色彩工具 ====================

    private fun calculateLuminance(color: Int): Float {
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    private fun sampleTopEdgeColor(src: Bitmap, ratio: Float = 0.1f): Int {
        val h = maxOf(1, (src.height * ratio).toInt())
        var r = 0L; var g = 0L; var b = 0L; var count = 0L
        val stepX = maxOf(1, src.width / 48)
        val stepY = maxOf(1, h / 4)
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
        val f = factor.coerceIn(-1f, 1f)
        return if (f >= 0) {
            Color.rgb(
                (Color.red(c) + (255 - Color.red(c)) * f).toInt().coerceIn(0, 255),
                (Color.green(c) + (255 - Color.green(c)) * f).toInt().coerceIn(0, 255),
                (Color.blue(c) + (255 - Color.blue(c)) * f).toInt().coerceIn(0, 255)
            )
        } else {
            val df = -f
            Color.rgb(
                (Color.red(c) * (1 - df)).toInt().coerceIn(0, 255),
                (Color.green(c) * (1 - df)).toInt().coerceIn(0, 255),
                (Color.blue(c) * (1 - df)).toInt().coerceIn(0, 255)
            )
        }
    }

    // ==================== CPU 降级模糊 ====================

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
                pixels[y * w + x] = Color.argb(
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
                pixels[y * w + x] = Color.argb(
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

    private const val ANTI_ALIAS_FLAG = Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
}
