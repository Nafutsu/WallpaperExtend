package com.wallpaperextend.processor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import kotlin.math.max

/**
 * 图像处理公共工具（颜色采样 / 颜色匹配 / 羽化 / 拼接）。
 * 位于 processor 包，供 NPU / RenderEffect / WallpaperProcessor 共用。
 */
object NPUImageProcessingUtils {

    // ==================================================================
    // 采样原图顶部平均颜色
    // ==================================================================
    fun sampleTopAverageColor(src: Bitmap, ratio: Float = 0.12f): Int {
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
        return if (count == 0L) Color.BLACK else Color.rgb(
            (r / count).toInt(), (g / count).toInt(), (b / count).toInt()
        )
    }

    // ==================================================================
    // 颜色匹配：把 src 整体向 targetColor 偏移（在 RGB 空间做均值对齐）
    // ==================================================================
    fun matchColorToTarget(src: Bitmap, targetColor: Int): Bitmap {
        val w = src.width; val h = src.height
        if (w <= 0 || h <= 0) return src

        // 采样 src 平均色
        val sampleH = max(1, h / 4)
        var sr = 0L; var sg = 0L; var sb = 0L; var count = 0L
        for (y in 0 until sampleH step max(1, sampleH / 4)) {
            for (x in 0 until w step max(1, w / 32)) {
                val p = src.getPixel(x, y)
                sr += Color.red(p); sg += Color.green(p); sb += Color.blue(p)
                count++
            }
        }
        if (count == 0L) return src
        val srcR = (sr / count).toInt()
        val srcG = (sg / count).toInt()
        val srcB = (sb / count).toInt()
        val tR = Color.red(targetColor)
        val tG = Color.green(targetColor)
        val tB = Color.blue(targetColor)

        val dR = (tR - srcR).coerceIn(-128, 128)
        val dG = (tG - srcG).coerceIn(-128, 128)
        val dB = (tB - srcB).coerceIn(-128, 128)

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = Color.alpha(p)
            val nr = (Color.red(p) + dR).coerceIn(0, 255)
            val ng = (Color.green(p) + dG).coerceIn(0, 255)
            val nb = (Color.blue(p) + dB).coerceIn(0, 255)
            pixels[i] = Color.argb(a, nr, ng, nb)
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    // ==================================================================
    // 羽化：顶部保留，底部渐变透明（用于延展区与原图衔接）
    // ==================================================================
    fun applyFeather(src: Bitmap, featherWidth: Int): Bitmap {
        val w = src.width; val h = src.height
        if (w <= 0 || h <= 0) return src
        val fw = featherWidth.coerceIn(0, h)
        if (fw <= 0) return src

        // 从底部往上：0（底）-> 255（底 - fw）
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        for (y in (h - fw) until h) {
            val t = (y - (h - fw)).toFloat() / fw      // 0..1
            val alpha = (t * 255).toInt().coerceIn(0, 255)
            for (x in 0 until w) {
                val i = y * w + x
                val p = pixels[i]
                pixels[i] = Color.argb(
                    (Color.alpha(p) * alpha / 255).coerceIn(0, 255),
                    Color.red(p), Color.green(p), Color.blue(p)
                )
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    // ==================================================================
    // 公共拼接：延展区（颜色匹配+羽化后）+ 原图 → 最终壁纸
    // ==================================================================
    fun composeExtendedWallpaper(
        extendedRegion: Bitmap,
        src: Bitmap,
        targetW: Int,
        targetH: Int,
        extendH: Int,
        featherWidth: Int
    ): Bitmap {
        // 1. 颜色匹配
        val topAvg = sampleTopAverageColor(src, 0.12f)
        val colorMatched = matchColorToTarget(extendedRegion, topAvg)
        extendedRegion.recycle()

        // 2. 羽化
        val feathered = applyFeather(colorMatched, featherWidth)
        colorMatched.recycle()

        // 3. 拼接
        val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(feathered, 0f, 0f, null)
        feathered.recycle()

        // 4. 绘制原图（下方）
        val srcScaledH = (targetW.toFloat() / src.width * src.height).toInt()
        val finalSrcH = (targetH - extendH).coerceAtLeast(srcScaledH)
        val bottomSrc = Bitmap.createScaledBitmap(src, targetW, finalSrcH, true)
        canvas.drawBitmap(bottomSrc, 0f, extendH.toFloat(), null)
        bottomSrc.recycle()

        return out
    }

    // ==================================================================
    // 采样顶部边缘颜色（CPU 模糊路径使用）
    // ==================================================================
    fun sampleTopEdgeColor(src: Bitmap, ratio: Float = 0.1f): Int {
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

    // ==================================================================
    // 颜色变亮（CPU 模糊路径使用）
    // ==================================================================
    fun lighten(c: Int, factor: Float = 0.5f): Int {
        val f = factor.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(c) + (255 - Color.red(c)) * f).toInt().coerceIn(0, 255),
            (Color.green(c) + (255 - Color.green(c)) * f).toInt().coerceIn(0, 255),
            (Color.blue(c) + (255 - Color.blue(c)) * f).toInt().coerceIn(0, 255)
        )
    }

    // ==================================================================
    // Stack Blur（CPU 兜底模糊算法）
    // ==================================================================
    fun stackBlur(b: Bitmap, radius: Int): Bitmap {
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
}
