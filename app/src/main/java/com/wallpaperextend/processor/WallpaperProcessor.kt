package com.wallpaperextend.processor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import kotlin.math.roundToInt

/**
 * iOS 17 / ColorOS 16 风格 "Extend Wallpaper" 效果：
 * - 顶部（默认）或上下延展区域使用原图采样色 + 放大模糊背景，避免纯白硬裁切
 * - 主体边缘通过渐变蒙版（DST_IN）自然融合
 * - 顶部叠加柔和光晕，模拟 iOS 的"呼吸感"
 * - 支持亮/暗双模式
 */
object WallpaperProcessor {

    enum class Mode { LIGHT, DARK }

    data class Config(
        val blurRadius: Int = 60,
        val extendRatio: Float = 0.34f,
        val featherWidth: Int = 140,
        val topOnly: Boolean = true,
        val targetHeightPx: Int = 0,
        val mode: Mode = Mode.LIGHT
    )

    private const val EDGE_TOP = 0
    private const val EDGE_BOTTOM = 1

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
        val canvas = Canvas(result)

        // 1. 底色：采样原图上方主色调（不做过度提亮，保留氛围）
        canvas.drawColor(sampleAtmosphereColor(scaled, top = true, mode = config.mode))

        // 2. 氛围背景：放大 + 强模糊的原图，铺满整张画布（含延展区）
        //    blurRadius 滑块 1-100 映射到实际模糊强度
        val actualBlur = (config.blurRadius.coerceIn(1, 100) / 4).coerceIn(4, 25)
        val blurredBg = blur(scaled, actualBlur)
        if (blurredBg != null) {
            val bg = scaleToFill(blurredBg, targetW, outH)
            canvas.drawBitmap(bg, null, Rect(0, 0, targetW, outH), Paint(Paint.FILTER_BITMAP_FLAG))
            if (bg != blurredBg) bg.recycle()
            blurredBg.recycle()
        }

        // 3. 延展区域：在氛围背景之上做渐变淡化，让顶部"融进去"
        drawExtendGradient(canvas, targetW, outH, extendH, config.mode)

        val mainTop = extendH
        val mainBottom = mainTop + scaled.height

        // 4. 边缘羽化融合（顶部/底部延展带）
        if (extendH > 0) {
            drawExtendedEdge(
                result = result, src = scaled, edge = EDGE_TOP,
                bandHeight = (scaled.height * 0.3f).roundToInt().coerceAtLeast(2),
                extendLength = extendH,
                feather = config.featherWidth.coerceAtLeast(1),
                blurRadius = actualBlur,
                mainAnchorY = mainTop
            )
        }
        if (bottomExtendH > 0) {
            drawExtendedEdge(
                result = result, src = scaled, edge = EDGE_BOTTOM,
                bandHeight = (scaled.height * 0.3f).roundToInt().coerceAtLeast(2),
                extendLength = bottomExtendH,
                feather = config.featherWidth.coerceAtLeast(1),
                blurRadius = actualBlur,
                mainAnchorY = mainBottom
            )
        }

        // 5. 绘制清晰主体，略偏下
        val placed = fitCenterRect(scaled.width, scaled.height, targetW, outH)
        val offsetY = (outH * 0.02f).roundToInt()
        val finalRect = Rect(placed.left, placed.top + offsetY, placed.right, placed.bottom + offsetY)
        canvas.drawBitmap(scaled, null, finalRect, Paint(Paint.FILTER_BITMAP_FLAG))

        // 6. 顶部柔光/雾气（iOS 感关键）
        drawTopGlow(canvas, targetW, outH, extendH, config.mode)

        return result
    }

    /** 顶部延展区：从氛围色渐变到透明，避免"白块/硬边" */
    private fun drawExtendGradient(canvas: Canvas, w: Int, h: Int, extendH: Int, mode: Mode) {
        if (extendH <= 0) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // 顶部 60% 延展区保持较实，之后逐渐淡出让主体显现
        val gradH = (extendH * 0.75f).roundToInt().coerceAtLeast(1)
        val (c1, c2) = if (mode == Mode.DARK) {
            Color.argb(255, 30, 32, 48) to Color.TRANSPARENT
        } else {
            Color.argb(255, 235, 242, 252) to Color.TRANSPARENT
        }
        paint.shader = LinearGradient(
            0f, 0f, 0f, gradH.toFloat(),
            intArrayOf(c1, c2),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w.toFloat(), gradH.toFloat(), paint)
        paint.shader = null
    }

    private fun drawTopGlow(canvas: Canvas, w: Int, h: Int, extendH: Int, mode: Mode) {
        val glowH = (extendH * 1.2f).roundToInt().coerceAtLeast(h / 6)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val (centerColor, edgeColor) = if (mode == Mode.DARK) {
            Color.argb(70, 90, 100, 150) to Color.TRANSPARENT
        } else {
            Color.argb(55, 210, 230, 255) to Color.TRANSPARENT
        }
        paint.shader = RadialGradient(
            w / 2f, h * 0.12f,
            w * 0.65f,
            centerColor, edgeColor,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w.toFloat(), glowH.toFloat(), paint)
        paint.shader = null
    }

    private fun drawExtendedEdge(
        result: Bitmap, src: Bitmap, edge: Int,
        bandHeight: Int, extendLength: Int, feather: Int, blurRadius: Int, mainAnchorY: Int
    ) {
        val w = result.width
        val h = result.height
        if (extendLength <= 0 || w <= 0) return

        val bh = bandHeight.coerceAtMost(src.height)
        val band = when (edge) {
            EDGE_TOP -> Bitmap.createBitmap(src, 0, 0, src.width, bh)
            else -> Bitmap.createBitmap(src, 0, src.height - bh, src.width, bh)
        }
        val blurred = blur(band, blurRadius)
        band.recycle()
        if (blurred == null) return

        val scaledBlur = Bitmap.createScaledBitmap(blurred, w, extendLength, true)
        blurred.recycle()

        val featherTop: Int
        val featherBottom: Int
        if (edge == EDGE_TOP) {
            featherTop = (mainAnchorY - feather).coerceAtLeast(0)
            featherBottom = mainAnchorY.coerceAtMost(h)
        } else {
            featherTop = mainAnchorY.coerceAtLeast(0)
            featherBottom = (mainAnchorY + feather).coerceAtMost(h)
        }

        // 离屏层：先画模糊带，再用渐变蒙版擦出融合区
        val layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val layerCanvas = Canvas(layer)
        val rectTop = if (edge == EDGE_TOP) 0 else mainAnchorY
        val rectBottom = if (edge == EDGE_TOP) extendLength else h
        layerCanvas.drawBitmap(scaledBlur, null, Rect(0, rectTop, w, rectBottom), Paint(Paint.FILTER_BITMAP_FLAG))

        if (featherBottom > featherTop) {
            val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                val maskColors = if (edge == EDGE_TOP) {
                    intArrayOf(Color.TRANSPARENT, Color.BLACK)
                } else {
                    intArrayOf(Color.BLACK, Color.TRANSPARENT)
                }
                shader = LinearGradient(
                    0f, featherTop.toFloat(), 0f, featherBottom.toFloat(),
                    maskColors, floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
                )
            }
            layerCanvas.drawRect(
                0f, featherTop.toFloat(), w.toFloat(), featherBottom.toFloat(), maskPaint
            )
        }

        val composePaint = Paint(Paint.FILTER_BITMAP_FLAG)
        val composeCanvas = Canvas(result)
        composeCanvas.drawBitmap(layer, 0f, 0f, composePaint)
        layer.recycle()
        scaledBlur.recycle()
    }

    // ============ 工具函数 ============

    private fun scaleToWidth(src: Bitmap, targetW: Int): Bitmap {
        if (targetW <= 0) return src
        if (src.width == targetW) return src
        val targetH = (targetW.toFloat() / src.width * src.height).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, targetW, targetH, true)
    }

    /** 铺满缩放（可能裁切），用于背景氛围图 */
    private fun scaleToFill(src: Bitmap, dstW: Int, dstH: Int): Bitmap {
        val srcRatio = src.width.toFloat() / src.height
        val dstRatio = dstW.toFloat() / dstH
        val (outW, outH) = if (srcRatio > dstRatio) {
            val h = dstH
            val w = (h * srcRatio).roundToInt()
            w to h
        } else {
            val w = dstW
            val h = (w / srcRatio).roundToInt()
            w to h
        }
        return Bitmap.createScaledBitmap(src, outW, outH, true)
    }

    private fun fitCenterRect(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Rect {
        val srcRatio = srcW.toFloat() / srcH
        val dstRatio = dstW.toFloat() / dstH
        val outW: Int
        val outH: Int
        if (srcRatio > dstRatio) {
            outW = dstW
            outH = (dstW / srcRatio).roundToInt()
        } else {
            outH = dstH
            outW = (dstH * srcRatio).roundToInt()
        }
        val left = (dstW - outW) / 2
        val top = (dstH - outH) / 2
        return Rect(left, top, left + outW, top + outH)
    }

    /**
     * 采样氛围色：取图片对应区域的加权平均色调。
     * - 不做强制提白，保留原图氛围
     * - LIGHT：偏亮但带色调；DARK：压暗成冷灰紫
     */
    private fun sampleAtmosphereColor(src: Bitmap, top: Boolean, mode: Mode): Int {
        val sample = Bitmap.createScaledBitmap(src, 24, 24, true)
        var r = 0; var g = 0; var b = 0; var count = 0
        val yStart = if (top) 0 else (sample.height * 0.7f).roundToInt()
        val yEnd = if (top) (sample.height * 0.4f).roundToInt().coerceAtLeast(1) else sample.height
        for (y in yStart until yEnd) {
            for (x in 0 until sample.width) {
                val c = sample.getPixel(x, y)
                r += Color.red(c); g += Color.green(c); b += Color.blue(c); count++
            }
        }
        sample.recycle()
        if (count == 0) return if (mode == Mode.DARK) Color.rgb(30, 32, 48) else Color.rgb(235, 242, 252)
        r /= count; g /= count; b /= count

        return if (mode == Mode.DARK) {
            // 压暗 + 偏冷紫灰
            Color.rgb(
                (r * 0.25f + 20).toInt().coerceIn(0, 80),
                (g * 0.25f + 24).toInt().coerceIn(0, 90),
                (b * 0.30f + 45).toInt().coerceIn(0, 120)
            )
        } else {
            // 轻度提亮，保留色调（不再粗暴取白）
            Color.rgb(
                ((r + 200) / 2).coerceIn(0, 255),
                ((g + 215) / 2).coerceIn(0, 255),
                ((b + 235) / 2).coerceIn(0, 255)
            )
        }
    }

    /**
     * 栈模糊（Stack Blur），支持大半径。
     * 先缩小再模糊再放大，模拟高强度模糊。
     */
    private fun blur(src: Bitmap, radius: Int): Bitmap? {
        val r = radius.coerceIn(1, 255)
        if (src.width <= 0 || src.height <= 0) return null
        val down = ((r / 6).coerceIn(1, 8))
        val smallW = (src.width / down).coerceAtLeast(2)
        val smallH = (src.height / down).coerceAtLeast(2)
        val small = Bitmap.createScaledBitmap(src, smallW, smallH, true)
        val blurredSmall = stackBlur(small, (r / down).coerceIn(1, 255))
        if (small != src) small.recycle()
        if (blurredSmall == null) return null
        val out = Bitmap.createScaledBitmap(blurredSmall, src.width, src.height, true)
        if (blurredSmall != small) blurredSmall.recycle()
        return out
    }

    private fun stackBlur(src: Bitmap, radius: Int): Bitmap? {
        val r = radius.coerceIn(1, 255)
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return null
        val out = Bitmap.createBitmap(w, h, src.config ?: Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val div = (2 * r + 1)
        val dv = IntArray(256 * div)
        for (i in 0 until 256 * div) dv[i] = i / div

        val rSum = IntArray(w)
        val gSum = IntArray(w)
        val bSum = IntArray(w)

        // 水平方向
        for (y in 0 until h) {
            var sumR = 0; var sumG = 0; var sumB = 0
            for (i in -r..r) {
                val px = (i + w) % w
                val p = pixels[y * w + px]
                sumR += (p shr 16) and 0xff
                sumG += (p shr 8) and 0xff
                sumB += p and 0xff
            }
            var yi = y * w
            for (x in 0 until w) {
                rSum[x] = sumR; gSum[x] = sumG; bSum[x] = sumB
                val px1 = if (x + r + 1 >= w) x + r + 1 - w else x + r + 1
                val px2 = if (x - r < 0) x - r + w else x - r
                val pp = pixels[y * w + px1]
                val p2 = pixels[y * w + px2]
                sumR += ((pp shr 16) and 0xff) - ((p2 shr 16) and 0xff)
                sumG += ((pp shr 8) and 0xff) - ((p2 shr 8) and 0xff)
                sumB += (pp and 0xff) - (p2 and 0xff)
                val pr = dv[rSum[x].coerceIn(0, 255 * div)]
                val pg = dv[gSum[x].coerceIn(0, 255 * div)]
                val pb = dv[bSum[x].coerceIn(0, 255 * div)]
                pixels[yi] = (-16777216) or (pr shl 16) or (pg shl 8) or pb
                yi++
            }
        }

        // 垂直方向
        for (x in 0 until w) {
            var sumR = 0; var sumG = 0; var sumB = 0
            for (i in -r..r) {
                val py = (i + h) % h
                val p = pixels[py * w + x]
                sumR += (p shr 16) and 0xff
                sumG += (p shr 8) and 0xff
                sumB += p and 0xff
            }
            var yi = 0
            for (y in 0 until h) {
                rSum[x] = sumR; gSum[x] = sumG; bSum[x] = sumB
                val py1 = if (y + r + 1 >= h) y + r + 1 - h else y + r + 1
                val py2 = if (y - r < 0) y - r + h else y - r
                val pp = pixels[py1 * w + x]
                val p2 = pixels[py2 * w + x]
                sumR += ((pp shr 16) and 0xff) - ((p2 shr 16) and 0xff)
                sumG += ((pp shr 8) and 0xff) - ((p2 shr 8) and 0xff)
                sumB += (pp and 0xff) - (p2 and 0xff)
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
