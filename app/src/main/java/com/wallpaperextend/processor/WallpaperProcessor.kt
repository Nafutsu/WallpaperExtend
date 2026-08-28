package com.wallpaperextend.processor

import kotlin.math.roundToInt
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
        val extendRatio: Float = 0.25f,
        val featherWidth: Int = 40,
        val topOnly: Boolean = true,
        val mode: Mode = Mode.LIGHT
    )

    /**
     * 只顶部延展（下方/底部绝不延展）。
     *
     * 布局（topOnly = true）：
     *   [0 .. extendH]           → 顶部延展区（原图顶部条纵向拉伸 + 模糊 + 渐变融合）
     *   [extendH .. extendH+srcH] → 原图本体，完整保留，下方绝不补白/模糊
     *   outH = extendH + srcH    → 精确贴合，下方不留空（避免"看起来向下延展"）
     *
     * @param targetW 输出宽度（一般 = 屏幕宽，横向铺满）
     * @param targetH 建议输出高度。若 topOnly=true，实际输出高度 = extendH + srcH（>= targetH）。
     *                调用方可再自行裁剪/居中到屏幕，保证底部是原图或裁掉，而不是空白。
     */
    fun process(src: Bitmap, targetW: Int, targetH: Int, config: Config = Config()): Bitmap {
        if (targetW <= 0 || targetH <= 0) return src

        // 1. 原图按目标宽度等比缩放，横向铺满，避免右边白竖条
        val scaledW = targetW
        val scaledH = (src.height * targetW.toFloat() / src.width).toInt().coerceAtLeast(1)

        // 2. 顶部延展高度：基于"最终想让延展区占多少"计算
        //    用 scaledH 作为基准，让延展比例与图片本身尺寸解耦，结果更稳定
        val extendH = if (config.topOnly) {
            (scaledH * config.extendRatio.coerceIn(0.05f, 0.6f)).toInt().coerceAtLeast(0)
        } else {
            0
        }

        // 3. 关键：输出高度 = 延展区 + 原图，下方不留任何空隙
        //    （之前 bug：outH = targetH，当 extendH+scaledH < targetH 时下方露白底 = 看起来像向下延展）
        val outH = extendH + scaledH

        val out = Bitmap.createBitmap(targetW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        // 底色只在延展区被绘制覆盖；下方是原图，不需要底色，用透明避免误以为有内容
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)
        val feather = config.featherWidth.coerceIn(8, 160)

        // 4. 顶部延展（严格在 [0, extendH]，绝不画到原图以下）
        if (extendH > 0) {
            drawTopExtension(canvas, scaled, targetW, extendH, config.blurRadius, feather)
        }

        // 5. 原图本体：从 extendH 开始，完整绘制，下方不动
        canvas.drawBitmap(
            scaled,
            0f,
            extendH.toFloat(),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )

        if (scaled !== src) scaled.recycle()
        return out
    }

    /* ================= 顶部延展（iOS 风自然过渡） ================= */

    private fun drawTopExtension(
        canvas: Canvas,
        scaled: Bitmap,
        targetW: Int,
        extendH: Int,
        blurRadius: Int,
        feather: Int
    ) {
        // --- 1. 取原图顶部一条，纵向拉伸到延展区（延续原图顶部背景纹理）---
        val stripH = max(6, scaled.height / 40)
        val topStrip = Bitmap.createBitmap(scaled, 0, 0, scaled.width, stripH)
        val stretched = Bitmap.createScaledBitmap(topStrip, targetW, extendH, true)
        topStrip.recycle()

        // --- 2. 高斯模糊 ---
        val soft = stackBlur(stretched, blurRadius.coerceIn(0, 80))
        if (soft !== stretched) stretched.recycle()

        // --- 3. 用离屏层做"从原图顶边往上的渐变融合"，避免硬接缝 ---
        //     层级（从下到上）：模糊底色 → 顶部浅色调统一 → 顶部提亮
        val layer = Bitmap.createBitmap(targetW, extendH, Bitmap.Config.ARGB_8888)
        val lc = Canvas(layer)

        lc.drawBitmap(soft, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))

        // 轻色调统一（半透明覆盖，不用 SRC_ATOP，避免脏灰边）
        val topAvg = sampleTopEdgeColor(scaled, ratio = 0.18f)
        val tone = lighten(topAvg, factor = 0.5f)
        lc.drawRect(
            0f, 0f, targetW.toFloat(), extendH.toFloat(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(28, Color.red(tone), Color.green(tone), Color.blue(tone))
            }
        )

        // 轻微提亮（越靠近时钟区越亮，做出 iOS 那种"呼吸感"）
        lc.drawRect(
            0f, 0f, targetW.toFloat(), extendH.toFloat(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, 0f, extendH.toFloat(),
                    intArrayOf(Color.argb(30, 255, 255, 255), Color.argb(0, 255, 255, 255)),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
        )

        // --- 4. 接缝融合：延展区底部用 DST_OUT 渐隐，让原图边缘自然透上来 ---
        //     这是消除硬线的关键 —— 渐变从"完全保留延展"到"完全透明"
        val fadeH = feather.coerceIn(8, extendH)
        lc.drawRect(
            0f, (extendH - fadeH).toFloat(), targetW.toFloat(), extendH.toFloat(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                shader = LinearGradient(
                    0f, (extendH - fadeH).toFloat(), 0f, extendH.toFloat(),
                    intArrayOf(Color.TRANSPARENT, Color.BLACK),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
        )

        // --- 5. 把离屏层画到最终画布 ---
        canvas.drawBitmap(layer, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        layer.recycle()
        if (soft !== scaled) soft.recycle()
    }

    /* ================= 取色 ================= */

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

        // 超大图先缩小再模糊，避免 OOM + 索引溢出
        val MAX_DIM = 1024
        val work = if (max(w, h) > MAX_DIM) {
            val scale = MAX_DIM.toFloat() / max(w, h)
            Bitmap.createScaledBitmap(
                b,
                (w * scale).roundToInt().coerceAtLeast(1),
                (h * scale).roundToInt().coerceAtLeast(1),
                true
            )
        } else {
            b
        }
        val ww = work.width
        val hh = work.height
        val pixels = IntArray(ww * hh)
        work.getPixels(pixels, 0, ww, 0, 0, ww, hh)

        val rad = min(radius, (min(ww, hh) - 1) / 2).coerceAtLeast(1)
        stackBlurH(pixels, ww, hh, rad)
        stackBlurV(pixels, ww, hh, rad)

        val out = Bitmap.createBitmap(ww, hh, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, ww, 0, 0, ww, hh)

        if (work !== b) work.recycle()
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
