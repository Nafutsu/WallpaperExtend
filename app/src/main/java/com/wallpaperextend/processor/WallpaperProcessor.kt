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
import kotlin.math.roundToInt

object WallpaperProcessor {

    enum class Mode { LIGHT, DARK }

    data class Config(
        val blurRadius: Int = 30,
        val extendRatio: Float = 0.25f,
        val featherWidth: Int = 40,
        val topOnly: Boolean = true,
        val mode: Mode = Mode.LIGHT
    )

    fun process(src: Bitmap, targetW: Int, targetH: Int, config: Config = Config()): Bitmap {
        val safe = ensureOpaque(src)
        val scaled = scaleToWidth(safe, targetW)
        val srcW = scaled.width
        val srcH = scaled.height

        val extendH = if (config.topOnly) {
            (targetH * config.extendRatio.coerceIn(0f, 0.6f)).roundToInt().coerceAtLeast(0)
        } else {
            0
        }

        val outH = extendH + srcH
        val out = Bitmap.createBitmap(targetW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)

        if (extendH > 0) {
            drawTopExtension(canvas, scaled, targetW, extendH, config)
            drawFeather(canvas, targetW, extendH, config.featherWidth.coerceIn(8, 80))
        }

        val drawX = ((targetW - srcW) / 2f).coerceAtLeast(0f)
        canvas.drawBitmap(scaled, drawX, extendH.toFloat(), null)

        if (safe !== src) safe.recycle()
        if (scaled !== src && scaled !== safe) scaled.recycle()

        return out
    }

    /* ================= 顶部延展 ================= */

    private fun drawTopExtension(
        canvas: Canvas, src: Bitmap, w: Int, extendH: Int, config: Config
    ) {
        // 取原图顶部一条
        val stripH = max(8, src.height / 40)
        val topStrip = Bitmap.createBitmap(src, 0, 0, src.width, min(stripH, src.height))

        // 纵向拉伸
        val stretched = Bitmap.createScaledBitmap(topStrip, w, extendH, true)
        topStrip.recycle()

        // 高斯模糊
        val blurred = stackBlur(stretched, config.blurRadius.coerceIn(1, 120))
        stretched.recycle()

        // 画模糊底色
        canvas.drawBitmap(blurred, 0f, 0f, null)

        // 轻色调统一：用半透明纯色覆盖，不用 SRC_ATOP
        val topAvg = sampleTopColor(src, ratio = 0.2f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(30, Color.red(topAvg), Color.green(topAvg), Color.blue(topAvg))
        }
        canvas.drawRect(0f, 0f, w.toFloat(), extendH.toFloat(), paint)

        // 轻微提亮
        paint.color = Color.argb(15, 255, 255, 255)
        canvas.drawRect(0f, 0f, w.toFloat(), extendH.toFloat(), paint)

        blurred.recycle()
    }

    /** 羽化：只让原图顶边柔和过渡，不压暗 */
    private fun drawFeather(canvas: Canvas, w: Int, extendH: Int, featherWidth: Int) {
        val feather = featherWidth.coerceIn(0, extendH)
        val startY = (extendH - feather).toFloat()
        val endY = extendH.toFloat()
        if (endY <= startY) return

        canvas.save()
        canvas.clipRect(0f, startY, w.toFloat(), endY)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            shader = LinearGradient(
                0f, startY, 0f, endY,
                intArrayOf(Color.TRANSPARENT, Color.BLACK),
                null, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, startY, w.toFloat(), endY, paint)
        paint.shader = null
        canvas.restore()
    }

    /* ================= 取色 ================= */

    private fun sampleTopColor(src: Bitmap, ratio: Float): Int {
        val sample = Bitmap.createScaledBitmap(src, 32, 32, true)
        var r = 0; var g = 0; var b = 0; var count = 0
        val endY = (sample.height * ratio).roundToInt().coerceAtLeast(1)
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

    private fun extractDominantColor(src: Bitmap, preferLight: Boolean): Int {
        val sample = Bitmap.createScaledBitmap(src, 64, 64, true)
        val counts = HashMap<Int, Int>()
        for (y in 0 until sample.height) {
            for (x in 0 until sample.width) {
                val c = sample.getPixel(x, y)
                if (Color.alpha(c) < 128) continue
                val key = Color.rgb(
                    (Color.red(c) / 32) * 32,
                    (Color.green(c) / 32) * 32,
                    (Color.blue(c) / 32) * 32
                )
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        sample.recycle()
        val sorted = counts.entries.sortedByDescending { it.value }
        var r = 0; var g = 0; var b = 0; var total = 0
        for (i in 0 until min(3, sorted.size)) {
            val (color, count) = sorted[i]
            val lum = 0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)
            val weight = count * (3 - i) * if (preferLight) (1 + lum / 255f) else 1f
            r += (Color.red(color) * weight).roundToInt()
            g += (Color.green(color) * weight).roundToInt()
            b += (Color.blue(color) * weight).roundToInt()
            total += weight.roundToInt()
        }
        if (total == 0) return Color.rgb(225, 232, 240)
        return Color.rgb(
            (r / total).coerceIn(0, 255),
            (g / total).coerceIn(0, 255),
            (b / total).coerceIn(0, 255)
        )
    }

    private fun lighten(c1: Int, c2: Int, factor: Float): Int {
        val f = factor.coerceIn(0f, 1f)
        return Color.rgb(
            ((Color.red(c1) * (1 - f) + Color.red(c2) * f)).roundToInt().coerceIn(0, 255),
            ((Color.green(c1) * (1 - f) + Color.green(c2) * f)).roundToInt().coerceIn(0, 255),
            ((Color.blue(c1) * (1 - f) + Color.blue(c2) * f)).roundToInt().coerceIn(0, 255)
        )
    }

    /* ================= 工具 ================= */

    private fun backgroundBaseColor(mode: Mode): Int =
        if (mode == Mode.DARK) Color.rgb(18, 20, 24) else Color.rgb(245, 248, 252)

    private fun ensureOpaque(src: Bitmap): Bitmap {
        if (!src.hasAlpha()) return src
        val b = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(b).apply {
            drawColor(Color.WHITE)
            drawBitmap(src, 0f, 0f, null)
        }
        return b
    }

    private fun scaleToWidth(src: Bitmap, targetW: Int): Bitmap {
        if (src.width == targetW) return src
        val targetH = (targetW.toFloat() / src.width * src.height).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, targetW, targetH, true)
    }

    /* ================= 栈模糊 ================= */

    private fun stackBlur(s: Bitmap, radius: Int): Bitmap {
        val r = radius.coerceIn(1, 255)
        val w = s.width
        val h = s.height
        if (w <= 0 || h <= 0) return s

        val MAX_DIM = 1024
        val work = if (max(w, h) > MAX_DIM) {
            val scale = MAX_DIM.toFloat() / max(w, h)
            Bitmap.createScaledBitmap(s, (w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1), true)
        } else {
            s
        }
        val ww = work.width
        val hh = work.height
        val size = ww * hh

        val pixels = IntArray(size)
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
        val dv = IntArray(256 * div)
        for (i in 0 until 256 * div) dv[i] = i / div

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
