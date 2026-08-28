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
        val featherWidth: Int = 120,
        val topOnly: Boolean = true,
        val mode: Mode = Mode.LIGHT
    )

    fun process(src: Bitmap, targetW: Int, targetH: Int, config: Config = Config()): Bitmap {
        val safe = ensureOpaque(src)
        val scaled = scaleToWidth(safe, targetW)
        val srcW = scaled.width
        val srcH = scaled.height

        val topH = (targetH * config.extendRatio.coerceIn(0f, 0.6f)).roundToInt().coerceAtLeast(0)
        val outH = (topH + srcH).coerceAtLeast(targetH)

        val out = Bitmap.createBitmap(targetW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(backgroundBaseColor(config.mode))

        val drawX = ((targetW - srcW) / 2f).coerceAtLeast(0f)

        if (topH > 0) {
            drawTopExtension(canvas, scaled, targetW, topH, config)
        }

        canvas.drawBitmap(scaled, drawX, topH.toFloat(), null)

        if (topH > 0) {
            drawTopFeather(canvas, targetW, topH, config.featherWidth.coerceAtLeast(1))
        }

        if (safe !== src) safe.recycle()
        if (scaled !== src && scaled !== safe) scaled.recycle()

        return out
    }

    /* ================= 内部 ================= */

    private fun backgroundBaseColor(mode: Mode): Int =
        if (mode == Mode.DARK) Color.rgb(18, 20, 24) else Color.rgb(245, 248, 252)

    private fun ensureOpaque(src: Bitmap): Bitmap {
        if (!src.hasAlpha()) return src
        val step = 8.coerceAtLeast(src.width / 32)
        for (y in 0 until src.height step step) {
            for (x in 0 until src.width step step) {
                if (Color.alpha(src.getPixel(x, y)) < 255) {
                    val b = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
                    Canvas(b).apply {
                        drawColor(Color.WHITE)
                        drawBitmap(src, 0f, 0f, null)
                    }
                    return b
                }
            }
        }
        return src
    }

    private fun drawTopExtension(
        canvas: Canvas, src: Bitmap, w: Int, topH: Int, config: Config
    ) {
        if (topH <= 0) return

        canvas.save()
        canvas.clipRect(0f, 0f, w.toFloat(), topH.toFloat())

        val baseColor = sampleAtmosphereColor(src, config.mode)
        canvas.drawColor(baseColor)

        val sliceH = (src.height * 0.28f).roundToInt().coerceAtLeast(1).coerceAtMost(src.height)
        val slice = Bitmap.createBitmap(src, 0, 0, src.width, sliceH)
        val blur = stackBlur(scaleToWidth(slice, w), config.blurRadius)
        slice.recycle()

        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            alpha = if (config.mode == Mode.DARK) 170 else 215
        }
        canvas.drawBitmap(blur, 0f, 0f, paint)

        val grad = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, topH.toFloat(),
                intArrayOf(
                    if (config.mode == Mode.DARK) Color.argb(90, 0, 0, 0)
                    else Color.argb(70, 255, 255, 255),
                    Color.TRANSPARENT
                ),
                null, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), topH.toFloat(), grad)
        grad.shader = null

        blur.recycle()
        canvas.restore()
    }

    private fun drawTopFeather(canvas: Canvas, w: Int, mainTop: Int, featherWidth: Int) {
        if (mainTop <= 0) return
        val feather = featherWidth.coerceIn(0, mainTop)
        val startY = (mainTop - feather).toFloat()
        val endY = mainTop.toFloat()

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

    private fun sampleAtmosphereColor(src: Bitmap, mode: Mode): Int {
        val sample = Bitmap.createScaledBitmap(src, 16, 16, true)
        var r = 0; var g = 0; var b = 0; var count = 0
        val endY = (sample.height * 0.3f).roundToInt().coerceAtLeast(1)
        for (y in 0 until endY) {
            for (x in 0 until sample.width) {
                val c = sample.getPixel(x, y)
                if (Color.alpha(c) < 16) continue
                r += Color.red(c); g += Color.green(c); b += Color.blue(c); count++
            }
        }
        sample.recycle()
        if (count == 0) {
            return if (mode == Mode.DARK) Color.rgb(30, 32, 48) else Color.rgb(220, 235, 245)
        }
        r /= count; g /= count; b /= count
        return if (mode == Mode.DARK) {
            Color.rgb(
                (r * 0.25f).roundToInt().coerceIn(0, 80),
                (g * 0.30f).roundToInt().coerceIn(0, 90),
                (b * 0.45f).roundToInt().coerceIn(0, 120)
            )
        } else {
            Color.rgb(
                ((r + 180) / 2).coerceIn(0, 255),
                ((g + 205) / 2).coerceIn(0, 255),
                ((b + 235) / 2).coerceIn(0, 255)
            )
        }
    }

    private fun scaleToWidth(src: Bitmap, targetW: Int): Bitmap {
        if (src.width == targetW) return src
        val targetH = (targetW.toFloat() / src.width * src.height).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, targetW, targetH, true)
    }

    /* ================= 安全的栈模糊（模运算防越界） ================= */

    private fun stackBlur(s: Bitmap, radius: Int): Bitmap {
        val r = radius.coerceIn(1, 255)
        val w = s.width
        val h = s.height
        if (w <= 0 || h <= 0) return s

        // 超大图先缩小再模糊，避免 OOM + 索引溢出
        val MAX_DIM = 1024
        val work = if (max(w, h) > MAX_DIM) {
            val scale = MAX_DIM.toFloat() / max(w, h)
            Bitmap.createScaledBitmap(
                s,
                (w * scale).roundToInt().coerceAtLeast(1),
                (h * scale).roundToInt().coerceAtLeast(1),
                true
            )
        } else {
            s
        }
        val ww = work.width
        val hh = work.height
        val size = ww * hh

        val pixels = IntArray(size)
        work.getPixels(pixels, 0, ww, 0, 0, ww, hh)

        // 半径按实际尺寸再钳制，保证 2*radius+1 <= min(w,h)
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

    /** 横向栈模糊：用模运算保证索引永不越界 */
    private fun stackBlurH(pixels: IntArray, w: Int, h: Int, radius: Int) {
        val div = (2 * radius + 1).coerceAtLeast(1)
        val divSum = ((div + 1) shr 1) * ((div + 1) shr 1)
        val dv = IntArray(256 * divSum)
        for (i in dv.indices) dv[i] = i / divSum
        val maxVal = 255 * divSum

        val rSum = IntArray(w)
        val gSum = IntArray(w)
        val bSum = IntArray(w)
        val aSum = IntArray(w)

        for (y in 0 until h) {
            var sumR = 0; var sumG = 0; var sumB = 0; var sumA = 0
            for (i in -radius..radius) {
                val xi = (i + w) % w
                val p = pixels[y * w + xi]
                sumR += Color.red(p); sumG += Color.green(p); sumB += Color.blue(p); sumA += Color.alpha(p)
            }
            for (x in 0 until w) {
                val yi = y * w + x
                pixels[yi] = Color.argb(
                    dv[sumA.coerceIn(0, maxVal)],
                    dv[sumR.coerceIn(0, maxVal)],
                    dv[sumG.coerceIn(0, maxVal)],
                    dv[sumB.coerceIn(0, maxVal)]
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

    /** 纵向栈模糊：用模运算保证索引永不越界 */
    private fun stackBlurV(pixels: IntArray, w: Int, h: Int, radius: Int) {
        val div = (2 * radius + 1).coerceAtLeast(1)
        val divSum = ((div + 1) shr 1) * ((div + 1) shr 1)
        val dv = IntArray(256 * divSum)
        for (i in dv.indices) dv[i] = i / divSum
        val maxVal = 255 * divSum

        for (x in 0 until w) {
            var sumR = 0; var sumG = 0; var sumB = 0; var sumA = 0
            for (i in -radius..radius) {
                val yi = ((i + h) % h) * w + x
                sumR += Color.red(pixels[yi]); sumG += Color.green(pixels[yi])
                sumB += Color.blue(pixels[yi]); sumA += Color.alpha(pixels[yi])
            }
            for (y in 0 until h) {
                val yi = y * w + x
                pixels[yi] = Color.argb(
                    dv[sumA.coerceIn(0, maxVal)],
                    dv[sumR.coerceIn(0, maxVal)],
                    dv[sumG.coerceIn(0, maxVal)],
                    dv[sumB.coerceIn(0, maxVal)]
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
