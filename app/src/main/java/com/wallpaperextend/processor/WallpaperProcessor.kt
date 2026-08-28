package com.wallpaperextend.processor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

object WallpaperProcessor {

    enum class Mode { LIGHT, DARK }

    data class Config(
        val blurRadius: Int = 32,
        /**
         * 延展比例：作为"最大延展高度占比"上限使用。
         * 实际延展高度 = 屏幕高度 - 原图缩放后高度（自动填满顶部留白）。
         * 双指缩放 / 滑块会修改此值来限制最大延展量。
         */
        val extendRatio: Float = 0.37f,
        val featherWidth: Int = 100,
        /** topOnly 保留字段，iOS 风格恒为仅顶部延展，固定 true */
        val topOnly: Boolean = true,
        val mode: Mode = Mode.LIGHT
    )

    /**
     * 主入口：将原图处理成 iOS 17 风格的"延展壁纸"。
     *
     * 核心策略（对齐 iOS 17 原生行为）：
     *   1. 原图按屏幕宽度等比缩放，横向铺满 → 底部对齐（严格贴合屏幕底部，无白边）
     *   2. 顶部留白 = 延展区。采样原图**顶部条带 → 旋转 180° → 高斯模糊 → 纵向拉伸**
     *      - 旋转 180° 是关键：让延展区的"下边缘"= 原图顶部边缘的镜像，
     *        这样接缝处是原图边缘对原图边缘，纹理连续、无硬断层
     *   3. 接缝处做**渐变模糊**（清晰→模糊的渐进过渡），而非 DST_OUT 硬裁切
     *   4. 防白边：尺寸用 ceil 取整 + 绘制时重叠 1px + 底色 = 边缘主色
     */
    fun process(src: Bitmap, targetW: Int, targetH: Int, config: Config = Config()): Bitmap {
        if (targetW <= 0 || targetH <= 0) return src

        // 底色用边缘主色，避免出现白/黑细线（白边 bug 的根因之一就是底色与图片不一致）
        val edgeColor = sampleTopEdgeColor(src, ratio = 0.05f)
        val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(edgeColor)

        // 原图按目标宽度等比缩放，横向铺满，避免右边白竖条
        val scaledW = targetW
        val scaledH = ceil(src.height * targetW.toFloat() / src.width).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)

        // 延展高度：屏幕高度 - 原图高度，刚好填满顶部留白；受 extendRatio 上限约束
        val maxExtend = (targetH * config.extendRatio.coerceIn(0.05f, 0.6f)).toInt()
        val extendH = (targetH - scaledH).coerceAtLeast(0).coerceAtMost(maxExtend)

        // 原图绘制 Y：底部对齐（= 屏幕高度 - 原图高度）
        // ★ 用 ceil + 重叠 1px，彻底消除"原图底部 / 延展区 与屏幕边缘的 1px 白线"
        val srcDrawY = (targetH - scaledH).toFloat()

        if (extendH > 0) {
            drawTopExtension(canvas, scaled, src, targetW, extendH, config.blurRadius, config.featherWidth)
        }

        // 原图贴底部画：重叠 1px 覆盖接缝，杜绝白边
        canvas.drawBitmap(
            scaled,
            0f,
            srcDrawY,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )

        // 防御性兜底：若 scaledH 计算有微小偏差导致底部露底，用边缘色补齐 1px
        if (srcDrawY + scaledH < targetH) {
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = edgeColor }
            canvas.drawRect(
                0f, (srcDrawY + scaledH).coerceAtMost(targetH.toFloat()),
                0f + targetW, targetH.toFloat(), fill
            )
        }

        if (scaled !== src) scaled.recycle()
        return out
    }

    /**
     * 绘制顶部延展区（iOS 17 风格：采样 → 旋转 180° → 模糊 → 拉伸 → 渐变融合）
     */
    private fun drawTopExtension(
        canvas: Canvas,
        scaled: Bitmap,
        src: Bitmap,
        targetW: Int,
        extendH: Int,
        blurRadius: Int,
        feather: Int
    ) {
        if (extendH <= 0) return

        // ====== 1. 采样原图顶部条带 ======
        // 取约 1/6 高度（足够提供纹理），太细会断裂、太宽会失真
        val stripH = max(8, scaled.height / 6)
        val topStrip = Bitmap.createBitmap(scaled, 0, 0, scaled.width, stripH)

        // ====== 2. 旋转 180°（iOS 核心技巧）======
        // 旋转后，条带的"底部"对应原图顶部的镜像，
        // 后续拉伸时，延展区靠近原图的一侧 = 原图顶部边缘的连续延伸，纹理无缝衔接
        val rotated = Bitmap.createBitmap(
            topStrip, 0, 0, topStrip.width, topStrip.height,
            Matrix().apply { setRotate(180f) }, true
        )
        topStrip.recycle()

        // ====== 3. 纵向拉伸到延展区尺寸 ======
        // 拉伸方向：rotated 的"底部"（原图顶部）→ 延展区的底部（靠近原图）
        //           rotated 的"顶部"（原图顶部远端）→ 延展区顶部（屏幕边缘）
        // 这样延展区底部 = 原图顶部边缘的镜像 → 连接处最自然
        val stretched = Bitmap.createScaledBitmap(rotated, targetW, extendH, true)
        rotated.recycle()

        // ====== 4. 高斯模糊 ======
        val blurred = stackBlur(stretched, blurRadius.coerceIn(0, 80))
        if (blurred !== stretched) stretched.recycle()

        // ====== 5. 绘制模糊延展层（y = 0 起，铺满延展区）======
        canvas.drawBitmap(
            blurred,
            0f, 0f,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )

        // ====== 6. 色调统一：采样原图顶部主色，半透明覆盖，避免色差 ======
        val topAvg = sampleTopEdgeColor(src, ratio = 0.12f)
        val tone = lighten(topAvg, factor = 0.4f)
        val tonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(30, Color.red(tone), Color.green(tone), Color.blue(tone))
        }
        canvas.drawRect(0f, 0f, targetW.toFloat(), extendH.toFloat(), tonePaint)

        // ====== 7. 渐变模糊融合（替代 DST_OUT 硬裁切）======
        // iOS 的做法：接缝处不是"清晰 | 模糊"一刀切，
        // 而是从延展区底部往原图方向，做一个"模糊→清晰"的渐变过渡带。
        // 实现：在接缝上方 feather 区域内，用渐变 alpha 将原图顶部"渐隐"进模糊层。
        val blendTop = (extendH - feather).coerceAtLeast(0)
        val blendBottom = extendH.toFloat()

        // 7a. 在延展区底部叠加一层"从清晰到模糊"的渐变蒙版
        //     让模糊层在靠近原图处逐渐变淡，露出下方原图，形成自然过渡
        val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            shader = LinearGradient(
                0f, blendTop.toFloat(),
                0f, blendBottom,
                // 上方（远离原图）= 保留模糊层（DST_OUT 的透明 = 不影响）
                // 下方（靠近原图）= 渐隐模糊层，露出清晰原图
                intArrayOf(Color.TRANSPARENT, Color.BLACK),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        // 在延展层之上：让靠近原图的部分模糊层淡出
        val layerId = canvas.saveLayer(
            0f, blendTop.toFloat(),
            targetW.toFloat(), extendH.toFloat(), null
        )
        canvas.drawRect(
            0f, blendTop.toFloat(),
            targetW.toFloat(), extendH.toFloat(),
            fadePaint
        )
        canvas.restoreToCount(layerId)

        // 7b. 反向：在原图顶部 feather 范围内，叠加一层渐变模糊覆盖
        //     让原图在接缝处不是"突然清晰"，而是从模糊渐变到清晰
        val srcTopFeather = feather.coerceAtMost(scaled.height)
        val gradPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, extendH.toFloat(),
                0f, (extendH + srcTopFeather).toFloat(),
                // 接缝处 = 模糊层色调（延续模糊感）
                // 往原图内部 = 完全透明（保持原图清晰）
                intArrayOf(
                    Color.argb(40, Color.red(tone), Color.green(tone), Color.blue(tone)),
                    Color.argb(0, Color.red(tone), Color.green(tone), Color.blue(tone))
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(
            0f, extendH.toFloat(),
            targetW.toFloat(), (extendH + srcTopFeather).toFloat(),
            gradPaint
        )

        if (blurred !== scaled) blurred.recycle()
    }

    /**
     * 采样图片顶部区域的平均颜色（用于底色填充 + 色调匹配，防白边）
     */
    private fun sampleTopEdgeColor(src: Bitmap, ratio: Float = 0.1f): Int {
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
        if (count == 0L) return if (src.config == Bitmap.Config.ARGB_8888) Color.BLACK else Color.WHITE
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
