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

    data class Config(
        val blurRadius: Int = 28,
        // 延展比例：仅作为"最大延展高度占比"上限使用，实际延展高度 = 屏幕高度 - 原图缩放后高度
        // （即刚好填满原图未能覆盖的顶部留白），所以拖动此滑块基本不影响常规竖图的视觉效果。
        val extendRatio: Float = 0.25f,
        val featherWidth: Int = 40,
        // topOnly 保留字段，iOS 风格恒为"仅顶部延展"，此值实际固定为 true
        val topOnly: Boolean = true,
        val mode: Mode = Mode.LIGHT
    )

    fun process(src: Bitmap, targetW: Int, targetH: Int, config: Config = Config()): Bitmap {
        if (targetW <= 0 || targetH <= 0) return src

        val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(if (config.mode == Mode.LIGHT) Color.WHITE else Color.BLACK)

        // 原图按目标宽度等比缩放，横向铺满，避免右边白竖条
        val scaledW = targetW
        val scaledH = (src.height * targetW.toFloat() / src.width).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)

        // ====== iOS 17 风格：原图底部对齐，延展高度 = 屏幕高度 - 原图高度 ======
        // 即顶部留白部分全部用"采样 + 模糊 + 羽化"填充，原图严格贴合屏幕底部。
        val extendH = (targetH - scaledH).coerceAtLeast(0)
            .coerceAtMost((targetH * config.extendRatio.coerceIn(0.05f, 0.6f)).toInt())
        val feather = config.featherWidth.coerceIn(8, 160)

        // 原图绘制 Y：底部对齐（= 屏幕高度 - 原图高度）
        val srcDrawY = targetH - scaledH

        if (extendH > 0) {
            drawTopExtension(canvas, scaled, targetW, extendH, config.blurRadius, feather)
        }

        // 原图贴底部画，无白边
        canvas.drawBitmap(
            scaled,
            0f,
            srcDrawY.toFloat(),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )

        if (scaled !== src) scaled.recycle()
        return out
    }

    private fun drawTopExtension(
        canvas: Canvas,
        scaled: Bitmap,
        targetW: Int,
        extendH: Int,
        blurRadius: Int,
        feather: Int
    ) {
        if (extendH <= 0) return

        // 取原图顶部一片区域做纵向延续（加宽到约 1/7，纹理延续更连贯，避免细线断裂）
        val stripH = max(6, scaled.height / 7)
        val topStrip = Bitmap.createBitmap(scaled, 0, 0, scaled.width, stripH)

        // 拉伸到 targetW × extendH，作为延展区的模糊底色
        val continuous = Bitmap.createScaledBitmap(topStrip, targetW, extendH, true)
        topStrip.recycle()

        val soft = stackBlur(continuous, blurRadius.coerceIn(0, 80))
        if (soft !== continuous) continuous.recycle()

        // 画模糊底色（顶部对齐，y = 0）
        canvas.drawBitmap(soft, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))

        // 轻色调统一（采样原图顶部边缘主色，半透明覆盖融合）
        val topAvg = sampleTopEdgeColor(scaled, ratio = 0.18f)
        val tone = lighten(topAvg, factor = 0.55f)
        val tonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(26, Color.red(tone), Color.green(tone), Color.blue(tone))
        }
        canvas.drawRect(0f, 0f, targetW.toFloat(), extendH.toFloat(), tonePaint)

        // 轻微提亮
        val lift = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(18, 255, 255, 255)
        }
        canvas.drawRect(0f, 0f, targetW.toFloat(), extendH.toFloat(), lift)

        // 接缝渐变融合：延展区底部用 DST_OUT 淡出，让下方清晰原图自然透上来
        val layerId = canvas.saveLayer(0f, 0f, targetW.toFloat(), extendH.toFloat(), null)
        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            shader = LinearGradient(
                0f,
                (extendH - feather).toFloat(),
                0f,
                extendH.toFloat(),
                intArrayOf(Color.BLACK, Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, (extendH - feather).toFloat(), targetW.toFloat(), extendH.toFloat(), maskPaint)
        maskPaint.shader = null
        maskPaint.xfermode = null
        canvas.restoreToCount(layerId)

        // 原图顶部再叠一层柔和覆盖，消除硬边
        val blend = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                (extendH - feather).toFloat(),
                0f,
                (extendH + feather.coerceAtMost(scaled.height)).toFloat(),
                intArrayOf(
                    Color.argb(0, 255, 255, 255),
                    Color.argb(12, Color.red(tone), Color.green(tone), Color.blue(tone))
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(
            0f,
            (extendH - feather).toFloat(),
            targetW.toFloat(),
            (extendH + feather.coerceAtMost(scaled.height)).toFloat(),
            blend
        )

        if (soft !== scaled) soft.recycle()
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

    /* ================= 栈模糊（模运算防越界） ================= */

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
