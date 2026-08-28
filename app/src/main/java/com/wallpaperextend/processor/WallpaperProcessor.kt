package com.wallpaperextend.processor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import kotlin.math.roundToInt

object WallpaperProcessor {

    enum class Mode { LIGHT, DARK }

    data class Config(
        val blurRadius: Int = 56,
        val extendRatio: Float = 0.32f,
        val featherWidth: Int = 120,
        val topOnly: Boolean = true,
        val mode: Mode = Mode.LIGHT
    )

    fun process(
        src: Bitmap,
        targetW: Int,
        targetH: Int,
        config: Config = Config()
    ): Bitmap {
        val safeSrc = ensureOpaque(src)

        val scaled = scaleToWidth(safeSrc, targetW)
        val srcW = scaled.width
        val srcH = scaled.height

        val topOnly = effectiveTopOnly(config.topOnly)
        val topH = (targetH * config.extendRatio.coerceIn(0f, 0.6f)).toInt()
        val bottomH = 0 // 当前强制不向下延展
        val mainTop = topH
        val mainBottom = mainTop + srcH
        val outH = targetH

        val out = Bitmap.createBitmap(targetW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(backgroundBaseColor(config.mode))

        val drawX = ((targetW - srcW) / 2f)

        drawTopExtension(canvas, scaled, targetW, topH, config, srcW, srcH, drawX)

        // 主体只画在 mainTop 开始位置
        canvas.drawBitmap(scaled, drawX, mainTop.toFloat(), null)

        // 顶部融合羽化，严格限制在顶部延展区
        drawTopFeather(canvas, targetW, topH, config.featherWidth)

        if (safeSrc !== src) safeSrc.recycle()
        if (scaled !== src) scaled.recycle()

        return out
    }

    private fun effectiveTopOnly(requested: Boolean): Boolean = true

    private fun backgroundBaseColor(mode: Mode): Int =
        if (mode == Mode.DARK) Color.rgb(18, 20, 24)
        else Color.rgb(245, 248, 252)

    private fun ensureOpaque(src: Bitmap): Bitmap {
        if (!needsOpaqueFix(src)) return src

        val b = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.WHITE)
        c.drawBitmap(src, 0f, 0f, null)
        return b
    }

    private fun needsOpaqueFix(src: Bitmap): Boolean {
        if (src.config == Bitmap.Config.ALPHA_8) return true
        // 抽样检查透明边缘/透明像素，避免模糊把透明混出彩边
        val step = 8.coerceAtLeast(src.width / 32)
        val ys = listOf(0, src.height - 1)
        val xs = listOf(0, src.width - 1)
        for (y in ys) {
            for (x in xs) {
                if (Color.alpha(src.getPixel(x.coerceIn(0, src.width - 1),
                        y.coerceIn(0, src.height - 1))) < 255) return true
            }
        }
        for (y in 0 until src.height step step) {
            for (x in 0 until src.width step step) {
                if (Color.alpha(src.getPixel(x, y)) < 255) return true
            }
        }
        return false
    }

    private fun drawTopExtension(
        canvas: Canvas,
        src: Bitmap,
        w: Int,
        topH: Int,
        config: Config,
        srcW: Int,
        srcH: Int,
        drawX: Float
    ) {
        if (topH <= 0) return

        canvas.save()
        canvas.clipRect(0f, 0f, w.toFloat(), topH.toFloat())

        val baseColor = sampleAtmosphereColor(src, config.mode)
        canvas.drawColor(baseColor)

        // 用原图顶部区域做模糊氛围，只画到顶部区域
        val sliceH = (srcH * 0.28f).coerceAtLeast(1).coerceAtMost(srcH)
        val slice = Bitmap.createBitmap(src, 0, 0, srcW, sliceH)
        val blur = fastStackBlur(scaleToWidth(slice, w), config.blurRadius.coerceIn(0, 120))
        slice.recycle()

        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        // 让模糊氛围淡一点，避免压过原图融合
        paint.alpha = if (config.mode == Mode.DARK) 170 else 215
        canvas.drawBitmap(blur, 0f, 0f, paint)

        // 从上往下轻微渐变，让时钟区更干净
        val grad = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f,
                0f, topH.toFloat(),
                intArrayOf(
                    if (config.mode == Mode.DARK) Color.argb(90, 0, 0, 0)
                    else Color.argb(70, 255, 255, 255),
                    Color.TRANSPARENT
                ),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), topH.toFloat(), grad)

        blur.recycle()
        canvas.restore()
    }

    private fun drawTopFeather(canvas: Canvas, w: Int, topH: Int, featherWidth: Int) {
        if (topH <= 0) return
        val feather = featherWidth.coerceIn(0, topH)
        val startY = (topH - feather).toFloat()
        val endY = topH.toFloat()

        canvas.save()
        canvas.clipRect(0f, startY, w.toFloat(), endY)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            shader = LinearGradient(
                0f, startY,
                0f, endY,
                intArrayOf(Color.TRANSPARENT, Color.BLACK),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, startY, w.toFloat(), endY, paint)
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
                r += Color.red(c)
                g += Color.green(c)
                b += Color.blue(c)
                count++
            }
        }
        sample.recycle()
        if (count == 0) {
            return if (mode == Mode.DARK) Color.rgb(30, 32, 48)
            else Color.rgb(220, 235, 245)
        }

        r /= count
        g /= count
        b /= count

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
        val targetH = (targetW.toFloat() / src.width * src.height)
            .roundToInt()
            .coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, targetW, targetH, true)
    }

    // 简单栈模糊，避免引入额外依赖；半径会钳制，性能可接受
    private fun fastStackBlur(s: Bitmap, radius: Int): Bitmap {
        if (radius <= 0) return s
        val w = s.width
        val h = s.height
        val pix = IntArray(w * h)
        s.getPixels(pix, 0, w, 0, 0, w, h)
        val r = radius.coerceIn(1, 120)

        stackBlur(pix, w, h, r)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pix, 0, w, 0, 0, w, h)
        if (out !== s) s.recycle()
        return out
    }

    private fun stackBlur(pix: IntArray, w: Int, h: Int, radius: Int) {
        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1
        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        val a = IntArray(wh)
        var x: Int
        var y: Int
        var i: Int
        var p: Int
        var yp: Int
        var yi: Int
        var yw: Int
        val vMin = IntArray(Math.max(w, h))
        var divSum = div + 1 shr 1
        divSum *= divSum
        val dv = IntArray(256 * divSum)
        i = 0
        while (i < dv.size) {
            dv[i] = i / divSum
            i++
        }

        yi = 0
        yw = 0
        i = 0
        while (i < wh) {
            p = pix[i]
            r[i] = Color.red(p)
            g[i] = Color.green(p)
            b[i] = Color.blue(p)
            a[i] = Color.alpha(p)
            i++
        }

        var yp0 = 0
        i = 0
        while (i < h) {
            var sumR = 0
            var sumG = 0
            var sumB = 0
            var sumA = 0
            var sumRIn = 0
            var sumGIn = 0
            var sumBIn = 0
            var sumAIn = 0
            var sumROut = 0
            var sumGOut = 0
            var sumBOut = 0
            var sumAOut = 0
            var rIn: Int
            var gIn: Int
            var bIn: Int
            var aIn: Int
            var rOut: Int
            var gOut: Int
            var bOut: Int
            var aOut: Int
            var m = -radius
            while (m <= radius) {
                p = pix[yi + Math.min(wm, Math.max(m, 0))]
                sumR += Color.red(p)
                sumG += Color.green(p)
                sumB += Color.blue(p)
                sumA += Color.alpha(p)
                m++
            }
            x = 0
            while (x < w) {
                r[yi] = dv[sumR]
                g[yi] = dv[sumG]
                b[yi] = dv[sumB]
                a[yi] = dv[sumA]
                if (yp0 == 0) {
                    vMin[x] = Math.min(x + radius + 1, wm)
                }
                p = pix[yw + vMin[x]]
                rOut = Color.red(p)
                gOut = Color.green(p)
                bOut = Color.blue(p)
                aOut = Color.alpha(p)
                p = pix[yw + Math.max(x - radius, 0)]
                rIn = Color.red(p)
                gIn = Color.green(p)
                bIn = Color.blue(p)
                aIn = Color.alpha(p)
                sumR += rIn - rOut
                sumG += gIn - gOut
                sumB += bIn - bOut
                sumA += aIn - aOut
                sumROut += rOut
                sumGOut += gOut
                sumBOut += bOut
                sumAOut += aOut
                sumRIn += rIn
                sumGIn += gIn
                sumBIn += bIn
                sumAIn += aIn
                yi++
                x++
            }
            yw += w
            yp0++
        }

        yi = 0
        yw = 0
        i = 0
        while (i < w) {
            var sumR = 0
            var sumG = 0
            var sumB = 0
            var sumA = 0
            var yp1 = -radius * w
            y = 0
            while (y <= radius) {
                yi = Math.max(0, yp1) + i
                sumR += r[yi]
                sumG += g[yi]
                sumB += b[yi]
                sumA += a[yi]
                yp1 += w
                y++
            }
            y = 0
            while (y < h) {
                pix[yw + i] = Color.argb(dv[sumA], dv[sumR], dv[sumG], dv[sumB])
                if (x == 0) {
                    vMin[y] = Math.min(y + radius + 1, hm) * w + i
                }
                p = yw + vMin[y]
                sumR -= r[p]
                sumG -= g[p]
                sumB -= b[p]
                sumA -= a[p]
                p = yw + Math.max(0, y - radius) * w + i
                sumR += r[p]
                sumG += g[p]
                sumB += b[p]
                sumA += a[p]
                y++
            }
            i++
        }
    }
}
