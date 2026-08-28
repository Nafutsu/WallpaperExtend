package com.example.wallpaper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader

object WallpaperProcessor {

    enum class Mode { LIGHT, DARK }

    data class Config(
        val blurRadius: Int = 28,
        val extendRatio: Float = 0.25f,
        val featherWidth: Int = 40,
        val topOnly: Boolean = true,
        val mode: Mode = Mode.LIGHT
    )

    fun process(src: Bitmap, targetW: Int, targetH: Int, config: Config = Config()): Bitmap {
        if (targetW <= 0 || targetH <= 0) return src

        val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        val bg = when (config.mode) {
            Mode.LIGHT -> Color.WHITE
            Mode.DARK -> Color.BLACK
        }
        canvas.drawColor(bg)

        val extendH = (targetH * config.extendRatio.coerceIn(0.05f, 0.6f)).toInt().coerceAtLeast(0)
        val feather = config.featherWidth.coerceIn(8, 160)

        // 先按目标宽度等比缩放原图，保持原图内容完整，横向填满，纵向按比。
        // 如果要“像PS拉伸构图”，可以后面改 fitCenter，但现在先保证无白边。
        val scale = targetW.toFloat() / src.width.toFloat()
        val scaledW = targetW
        val scaledH = (src.height * scale).toInt()
        val scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)

        val srcDrawY = extendH
        val srcDrawX = 0
        val srcBottom = srcDrawY + scaledH

        if (config.topOnly && extendH > 0) {
            drawTopExtensionPsLike(canvas, scaled, targetW, extendH, config.blurRadius, feather)
        }

        // 原图区域：直接画，x=0 宽=targetW，避免右边白竖条/黑背景
        val paintSrc = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(scaled, srcDrawX.toFloat(), srcDrawY.toFloat(), paintSrc)

        // 如果原图高度不足屏幕，底部不处理/保留背景；你需求是顶部延展，底部不动。
        // 若需要底部也延展，可对称加 drawBottomExtensionPsLike。

        if (scaled !== src) scaled.recycle()
        return out
    }

    private fun drawTopExtensionPsLike(
        canvas: Canvas,
        scaled: Bitmap,
        targetW: Int,
        extendH: Int,
        blurRadius: Int,
        feather: Int
    ) {
        val stripH = maxOf(6, scaled.height / 35)
        val topStrip = Bitmap.createBitmap(scaled, 0, 0, scaled.width, stripH)

        // 强制宽度 = targetW，高度 = extendH，避免右边白竖条
        val continuous = Bitmap.createScaledBitmap(topStrip, targetW, extendH, true)
        topStrip.recycle()

        val soft = stackBlur(continuous, blurRadius.coerceIn(0, 80))
        if (soft !== continuous) continuous.recycle()

        val topAvg = sampleTopEdgeColor(scaled, ratio = 0.18f)
        val tone = lighten(topAvg, factor = 0.55f)
        val toneAlpha = 26

        // 基础延展
        canvas.drawBitmap(soft, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))

        // 轻色调统一，不用 SRC_ATOP
        val tonePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        tonePaint.color = Color.argb(toneAlpha, Color.red(tone), Color.green(tone), Color.blue(tone))
        canvas.drawRect(0f, 0f, targetW.toFloat(), extendH.toFloat(), tonePaint)

        // 轻微提亮/去脏
        val lift = Paint(Paint.ANTI_ALIAS_FLAG)
        lift.color = Color.argb(18, 255, 255, 255)
        canvas.drawRect(0f, 0f, targetW.toFloat(), extendH.toFloat(), lift)

        // 接缝渐变融合：从 extendH-feather 到 extendH，延展层淡出，让原图透上来
        // 先保存当前画布，对刚画的延展区域做蒙版
        val layerId = canvas.saveLayer(0f, 0f, targetW.toFloat(), extendH.toFloat(), null)

        // 黑色矩形盖住延展底部，用渐变透明擦除
        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        maskPaint.shader = LinearGradient(
            0f, (extendH - feather).toFloat(),
            0f, extendH.toFloat(),
            Color.BLACK,
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, (extendH - feather).toFloat(), targetW.toFloat(), extendH.toFloat(), maskPaint)
        maskPaint.shader = null
        maskPaint.xfermode = null
        canvas.restoreToCount(layerId)

        // 再在原图顶部画一层从透明到原图/浅色的柔和覆盖，消除硬边感
        val blendPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        blendPaint.shader = LinearGradient(
            0f, (extendH - feather).toFloat(),
            0f, (extendH + feather.coerceAtMost(scaled.height.toFloat())).coerceAtLeast(extendH.toFloat()),
            Color.argb(0, 255, 255, 255),
            Color.argb(12, Color.red(tone), Color.green(tone), Color.blue(tone)),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(
            0f,
            (extendH - feather).toFloat(),
            targetW.toFloat(),
            (extendH + feather.coerceAtMost(scaled.height)).toFloat(),
            blendPaint
        )

        if (soft !== scaled) soft.recycle()
    }

    private fun sampleTopEdgeColor(src: Bitmap, ratio: Float = 0.15f): Int {
        val h = maxOf(1, (src.height * ratio).toInt())
        var r = 0L; var g = 0L; var b = 0L; var count = 0L
        val stepX = maxOf(1, src.width / 64)
        val stepY = maxOf(1, h / 8)
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
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        val nr = (r + (255 - r) * factor).toInt().coerceIn(0, 255)
        val ng = (g + (255 - g) * factor).toInt().coerceIn(0, 255)
        val nb = (b + (255 - b) * factor).toInt().coerceIn(0, 255)
        return Color.rgb(nr, ng, nb)
    }

    private fun stackBlur(b: Bitmap, radius: Int): Bitmap {
        if (radius <= 0) return b
        val w = b.width; val h = b.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(b, 0f, 0f, null)
        val pixels = IntArray(w * h)
        out.getPixels(pixels, 0, w, 0, 0, w, h)
        stackBlurH(pixels, w, h, radius)
        stackBlurV(pixels, w, h, radius)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun stackBlurH(pixels: IntArray, w: Int, h: Int, radius: Int) {
        val div = (2 * radius + 1).coerceAtLeast(1)
        val dv = IntArray(256 * div) { it / div }
        for (y in 0 until h) {
            var sumR = 0; var sumG = 0; var sumB = 0; var sumA = 0
            for (i in -radius..radius) {
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
        val dv = IntArray(256 * div) { it / div }
        for (x in 0 until w) {
            var sumR = 0; var sumG = 0; var sumB = 0; var sumA = 0
            for (i in -radius..radius) {
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
