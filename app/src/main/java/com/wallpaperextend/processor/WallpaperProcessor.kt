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

/**
 * iOS 17 / ColorOS 16 风格 "Extend Wallpaper" 效果
 */
object WallpaperProcessor {

    data class Config(
        val blurRadius: Int = 30,
        val extendRatio: Float = 0.25f,
        val featherWidth: Int = 120,
        val topOnly: Boolean = true,
        val targetHeightPx: Int = 0
    )

    fun process(src: Bitmap, targetW: Int, targetH: Int, config: Config = Config()): Bitmap {
        val scaled = scaleToWidth(src, targetW)
        val extendH = (scaled.height * config.extendRatio).roundToInt().coerceAtLeast(0)
        val bottomExtendH = if (config.topOnly) 0 else extendH
        val outH = if (config.targetHeightPx > 0) {
            config.targetHeightPx.coerceAtLeast(scaled.height)
        } else {
            scaled.height + extendH + bottomExtendH
        }

        val result = Bitmap.createBitmap(targetW, outH, Bitmap.Config.ARGB_8888)
        val mainTop = extendH
        val mainBottom = mainTop + scaled.height

        if (extendH > 0) {
            drawExtendedEdge(
                result = result, src = scaled, edge = EDGE_TOP,
                bandHeight = (scaled.height * 0.25f).roundToInt().coerceAtLeast(2),
                extendLength = extendH, feather = config.featherWidth.coerceAtLeast(1),
                blurRadius = config.blurRadius.coerceAtLeast(1), mainAnchorY = mainTop
            )
        }

        if (bottomExtendH > 0) {
            drawExtendedEdge(
                result = result, src = scaled, edge = EDGE_BOTTOM,
                bandHeight = (scaled.height * 0.25f).roundToInt().coerceAtLeast(2),
                extendLength = bottomExtendH, feather = config.featherWidth.coerceAtLeast(1),
                blurRadius = config.blurRadius.coerceAtLeast(1), mainAnchorY = mainBottom
            )
        }

        val mainCanvas = Canvas(result)
        mainCanvas.drawBitmap(scaled, 0f, mainTop.toFloat(), null)

        return result
    }

    private const val EDGE_TOP = 0
    private const val EDGE_BOTTOM = 1

    private fun drawExtendedEdge(
        result: Bitmap, src: Bitmap, edge: Int,
        bandHeight: Int, extendLength: Int, feather: Int, blurRadius: Int, mainAnchorY: Int
    ) {
        val w = result.width
        val h = result.height
        if (extendLength <= 0 || w <= 0) return

        val band = when (edge) {
            EDGE_TOP -> {
                val bh = bandHeight.coerceAtMost(src.height)
                Bitmap.createBitmap(src, 0, 0, src.width, bh)
            }
            EDGE_BOTTOM -> {
                val bh = bandHeight.coerceAtMost(src.height)
                Bitmap.createBitmap(src, 0, src.height - bh, src.width, bh)
            }
            else -> return
        }

        val blurred = blur(band, blurRadius)
        val scaledBlur = Bitmap.createScaledBitmap(blurred, w, extendLength, true)
        val canvas = Canvas(result)

        val rectTop = if (edge == EDGE_TOP) 0 else mainAnchorY
        val rectBottom = if (edge == EDGE_TOP) extendLength else h
        canvas.drawBitmap(scaledBlur, null, android.graphics.Rect(0, rectTop, w, rectBottom), null)

        val featherTop: Int
        val featherBottom: Int
        if (edge == EDGE_TOP) {
            featherTop = (mainAnchorY - feather).coerceAtLeast(0)
            featherBottom = mainAnchorY.coerceAtMost(h)
        } else {
            featherTop = mainAnchorY.coerceAtLeast(0)
            featherBottom = (mainAnchorY + feather).coerceAtMost(h)
        }

        if (featherBottom > featherTop) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            // 修复：用 Color.parseColor 或 -0x1 代替 0xFFFFFFFF
            val gradientColors = if (edge == EDGE_TOP) {
                intArrayOf(0x00FFFFFF, -0x1)
            } else {
                intArrayOf(-0x1, 0x00FFFFFF)
            }
            val linearShader = LinearGradient(
                0f, featherTop.toFloat(), 0f, featherBottom.toFloat(),
                gradientColors, null, Shader.TileMode.CLAMP
            )
            val bitmapShader = android.graphics.BitmapShader(
                scaledBlur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP
            )
            paint.shader = android.graphics.ComposeShader(
                bitmapShader, linearShader,
                PorterDuff.Mode.DST_IN
            )
            canvas.drawRect(0f, featherTop.toFloat(), w.toFloat(), featherBottom.toFloat(), paint)
        }

        band.recycle()
        blurred.recycle()
        scaledBlur.recycle()
    }

    private fun scaleToWidth(src: Bitmap, targetW: Int): Bitmap {
        if (src.width == targetW) return src
        val targetH = (targetW.toFloat() / src.width * src.height).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, targetW, targetH, true)
    }

    private fun blur(src: Bitmap, radius: Int): Bitmap {
        val r = radius.coerceIn(1, 80)
        val scale = (1.0 / (1 + r * 0.4)).coerceAtLeast(0.2)
        val smallW = (src.width * scale).roundToInt().coerceAtLeast(2)
        val smallH = (src.height * scale).roundToInt().coerceAtLeast(2)
        val small = Bitmap.createScaledBitmap(src, smallW, smallH, true)
        val blurredSmall = stackBlur(small, (r * scale).roundToInt().coerceAtLeast(1))
        val out = Bitmap.createScaledBitmap(blurredSmall, src.width, src.height, true)
        if (small != src) small.recycle()
        if (blurredSmall != small) blurredSmall.recycle()
        return out
    }

    private fun stackBlur(src: Bitmap, radius: Int): Bitmap {
        val r = radius.coerceIn(1, 255)
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src
        val out = Bitmap.createBitmap(w, h, src.config ?: Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val div = (2 * r + 1)
        val dv = IntArray(256 * div)
        for (i in 0 until 256 * div) dv[i] = i / div

        var x: Int
        var y: Int
        var yi: Int
        val rSum = IntArray(w)
        val gSum = IntArray(w)
        val bSum = IntArray(w)
        val rOut = IntArray(w)
        val gOut = IntArray(w)
        val bOut = IntArray(w)
        val rIn = IntArray(w)
        val gIn = IntArray(w)
        val bIn = IntArray(w)

        for (y in 0 until h) {
            var sumR = 0; var sumG = 0; var sumB = 0
            for (i in -r..r) {
                val px = (i + w) % w
                val p = pixels[y * w + px]
                sumR += (p shr 16) and 0xff
                sumG += (p shr 8) and 0xff
                sumB += p and 0xff
            }
            yi = y * w
            for (x in 0 until w) {
                rSum[x] = sumR; gSum[x] = sumG; bSum[x] = sumB
                if (y == 0) {
                    val p1 = pixels[((r shl 1) + 1) % w]
                    rOut[x] = (p1 shr 16) and 0xff
                    gOut[x] = (p1 shr 8) and 0xff
                    bOut[x] = p1 and 0xff
                }
                val px1 = if (x + r + 1 >= w) x + r + 1 - w else x + r + 1
                val px2 = if (x - r < 0) x - r + w else x - r
                val pp = pixels[y * w + px1]
                rIn[x] = (pp shr 16) and 0xff
                gIn[x] = (pp shr 8) and 0xff
                bIn[x] = pp and 0xff
                sumR += rIn[x] - ((pixels[y * w + px2] shr 16) and 0xff)
                sumG += gIn[x] - ((pixels[y * w + px2] shr 8) and 0xff)
                sumB += bIn[x] - (pixels[y * w + px2] and 0xff)
                yi++
            }
            yi = y * w
            for (x in 0 until w) {
                val pr = dv[rSum[x].coerceIn(0, 255 * div)]
                val pg = dv[gSum[x].coerceIn(0, 255 * div)]
                val pb = dv[bSum[x].coerceIn(0, 255 * div)]
                pixels[yi] = (-16777216) or (pr shl 16) or (pg shl 8) or pb
                yi++
            }
        }

        for (x in 0 until w) {
            var sumR = 0; var sumG = 0; var sumB = 0
            for (i in -r..r) {
                val py = (i + h) % h
                val p = pixels[py * w + x]
                sumR += (p shr 16) and 0xff
                sumG += (p shr 8) and 0xff
                sumB += p and 0xff
            }
            yi = 0
            for (y in 0 until h) {
                rSum[x] = sumR; gSum[x] = sumG; bSum[x] = sumB
                if (x == 0) {
                    val p1 = pixels[(((r shl 1) + 1) % h) * w + x]
                    rOut[x] = (p1 shr 16) and 0xff
                    gOut[x] = (p1 shr 8) and 0xff
                    bOut[x] = p1 and 0xff
                }
                val py1 = if (y + r + 1 >= h) y + r + 1 - h else y + r + 1
                val py2 = if (y - r < 0) y - r + h else y - r
                val pp = pixels[py1 * w + x]
                rIn[x] = (pp shr 16) and 0xff
                gIn[x] = (pp shr 8) and 0xff
                bIn[x] = pp and 0xff
                sumR += rIn[x] - ((pixels[py2 * w + x] shr 16) and 0xff)
                sumG += gIn[x] - ((pixels[py2 * w + x] shr 8) and 0xff)
                sumB += bIn[x] - (pixels[py2 * w + x] and 0xff)
            }
            yi = 0
            for (y in 0 until h) {
                val pr = rSum[x].coerceIn(0, 255)
                val pg = gSum[x].coerceIn(0, 255)
                val pb = bSum[x].coerceIn(0, 255)
                pixels[yi + x] = (-16777216) or (pr shl 16) or (pg shl 8) or pb
                yi += w
            }
        }

        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }
}
