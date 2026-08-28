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
        val extendRatio: Float = 0.25f,   // 每个方向的延展比例
        val featherWidth: Int = 60,        // 融合宽度，比之前大一些
        val direction: ExtendDirection = ExtendDirection.TOP,
        val mode: Mode = Mode.LIGHT
    )

    fun process(src: Bitmap, targetW: Int, targetH: Int, config: Config = Config()): Bitmap {
        if (targetW <= 0 || targetH <= 0) return src

        val extendRatio = config.extendRatio.coerceIn(0.05f, 0.6f)
        val topExtendH = if (config.direction == ExtendDirection.TOP || config.direction == ExtendDirection.BOTH)
            (targetH * extendRatio).toInt().coerceAtLeast(0) else 0
        val bottomExtendH = if (config.direction == ExtendDirection.BOTTOM || config.direction == ExtendDirection.BOTH)
            (targetH * extendRatio).toInt().coerceAtLeast(0) else 0

        // 最终画布 = 原图缩放后高度 + 上方延展 + 下方延展
        val scaledW = targetW
        val scaledH = (src.height * targetW.toFloat() / src.width).toInt().coerceAtLeast(1)
        val canvasH = scaledH + topExtendH + bottomExtendH

        val out = Bitmap.createBitmap(targetW, canvasH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(if (config.mode == Mode.LIGHT) Color.WHITE else Color.BLACK)

        val scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)

        // 原图绘制位置
        val srcDrawY = topExtendH

        // 上方延展
        if (topExtendH > 0) {
            drawExtension(canvas, scaled, targetW, topExtendH, config, isTop = true)
        }

        // 下方延展
        if (bottomExtendH > 0) {
            drawExtension(canvas, scaled, targetW, bottomExtendH, config, isTop = false)
        }

        // 画原图
        canvas.drawBitmap(scaled, 0f, srcDrawY.toFloat(),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))

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
        val feather = config.featherWidth.coerceIn(16, 200)
        val blurRadius = config.blurRadius.coerceIn(0, 80)

        // 取原图边缘区域（顶部或底部 1/4），比之前 1/35 一条好太多
        val sampleH = max(6, scaled.height / 4)
        val sampleY = if (isTop) 0 else (scaled.height - sampleH).coerceAtLeast(0)
        val edgeStrip = Bitmap.createBitmap(scaled, 0, sampleY, scaled.width, sampleH)

        // 拉伸到延展区尺寸
        val stretched = Bitmap.createScaledBitmap(edgeStrip, targetW, extendH, true)
        edgeStrip.recycle()

        // 多层模糊模拟 iOS 景深
        // 第一层：强模糊做底色
        val heavyBlur = stackBlur(Bitmap.createBitmap(stretched), blurRadius)
        // 第二层：轻模糊做中间层
        val lightBlur = stackBlur(Bitmap.createBitmap(stretched), max(8, blurRadius / 3))

        if (stretched !== edgeStrip) stretched.recycle()

        val drawY = if (isTop) 0 else (canvas.height - extendH).toFloat()

        // 画强模糊底色
        canvas.drawBitmap(heavyBlur, 0f, drawY,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))

        // 画轻模糊，用渐变遮罩让它与底色融合
        val blendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        }
        val gradientYStart = if (isTop) drawY + extendH * 0.3f else drawY + extendH * 0.7f
        val gradientYEnd = if (isTop) drawY + extendH * 0.7f else drawY + extendH * 0.3f
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
        val edgeAvg = if (isTop) sampleTopEdgeColor(scaled) else sampleBottomEdgeColor(scaled)
        val tone = lighten(edgeAvg, factor = 0.6f)
        val tonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(30, Color.red(tone), Color.green(tone), Color.blue(tone))
        }
        canvas.drawRect(0f, drawY, targetW.toFloat(), drawY + extendH, tonePaint)

        // 与原图接缝融合 —— 三层渐变
        val layerId = canvas.saveLayer(0f, drawY, targetW.toFloat(), drawY + extendH, null)

        // 先画模糊内容（已经在上面画了，这里用遮罩控制透明度）
        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        }

        if (isTop) {
            // 顶部延展：底部淡出，让原图透上来
            maskPaint.shader = LinearGradient(
                0f, drawY + extendH - feather,
                0f, drawY + extendH,
                intArrayOf(Color.BLACK, Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, drawY + extendH - feather, targetW.toFloat(), drawY + extendH, maskPaint)

            // 再叠一层从模糊到原图的柔和过渡（超出接缝向上）
            val softBlend = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, drawY + extendH - feather * 2,
                    0f, drawY + extendH + feather / 2,
                    intArrayOf(Color.TRANSPARENT, Color.argb(40, Color.red(tone), Color.green(tone), Color.blue(tone))),
                    floatArrayOf(0.3f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, drawY + extendH - feather * 2, targetW.toFloat(),
                drawY + extendH + feather / 2, softBlend)
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

            val softBlend = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, drawY - feather / 2,
                    0f, drawY + feather * 2,
                    intArrayOf(Color.argb(40, Color.red(tone), Color.green(tone), Color.blue(tone)), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.7f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, drawY - feather / 2, targetW.toFloat(),
                drawY + feather * 2, softBlend)
        }

        maskPaint.shader = null
        maskPaint.xfermode = null
        canvas.restoreToCount(layerId)

        heavyBlur.recycle()
        lightBlur.recycle()
    }

    private fun sampleBottomEdgeColor(src: Bitmap, ratio: Float = 0.15f): Int {
        val h = max(1, (src.height * ratio).toInt())
        var r = 0L; var g = 0L; var b = 0L; var count = 0L
        val stepX = max(1, src.width / 64)
        val stepY = max(1, h / 8)
        val startY = src.height - h
        for (y in startY until src.height step stepY) {
            for (x in 0 until src.width step stepX) {
                val p = src.getPixel(x, y)
                r += Color.red(p); g += Color.green(p); b += Color.blue(p)
                count++
            }
        }
        if (count == 0L) return Color.WHITE
        return Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }

    private fun sampleTopEdgeColor(src: Bitmap, ratio: Float = 0.15f): Int {
        val h = max(1, (src.height * ratio).toInt())
        var r = 0L; var g = 0L; var b = 0L; var count = 0L
        val stepX = max(1, src.width / 64)
        val stepY = max(1, h / 8)
        for (y in 0 until h step stepY) {
            for (x in 0 until src.width step stepX) {
                val p = src.getPixel(x, y)
                r += Color.red(p); g += Color.green(p); b += Color.blue(p)
                count++
            }
        }
        if (count == 0L) return Color.WHITE
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

    /* ================= 栈模糊（不变） ================= */
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
