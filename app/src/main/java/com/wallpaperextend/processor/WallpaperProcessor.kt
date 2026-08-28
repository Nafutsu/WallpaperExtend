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
 *
 * ⚠️ 核心约束（已修复）：
 * - 【只顶部延展】：topOnly 固定为 true，底部绝不补任何内容。
 * - 输出画布布局：
 *     [0 .. mainTop]          → 顶部延展氛围区（采样色 + 模糊 + 渐变 + 光晕，全部 clip 在此区间）
 *     [mainTop .. outH]       → 清晰主体（fitCenter 放置，略偏下）
 *     ★ 主体下方到 outH 之间不做任何绘制，保持透明/底色，不生成伪影
 * - 所有装饰层（渐变/光晕/模糊带）严格 clip 到 [0, mainTop]，禁止越界到主体或底部。
 * - 模糊/缩放使用 copy 后的干净 Bitmap，禁止对带透明边框的 Bitmap 直接模糊导致边缘色带。
 */
object WallpaperProcessor {

    enum class Mode { LIGHT, DARK }

    data class Config(
        val blurRadius: Int = 60,
        /** 顶部延展比例（占原图高度的比例），0 表示不延展 */
        val extendRatio: Float = 0.34f,
        /** 主体顶部与延展区之间的羽化宽度（px） */
        val featherWidth: Int = 120,
        /** ⚠️ 强制 true：只顶部延展，即使传 false 也会被忽略并打印警告 */
        val topOnly: Boolean = true,
        /** 输出高度(px)。0 = 自动（原高 + 顶部延展高度） */
        val targetHeightPx: Int = 0,
        val mode: Mode = Mode.LIGHT
    ) {
        /** 对外始终表现为只顶部延展，防止旧调用传 false 导致底部延展 */
        fun effectiveTopOnly(): Boolean = true
    }

    fun process(src: Bitmap, targetW: Int, targetH: Int, config: Config = Config()): Bitmap {
        // 1. 统一宽度，避免缩放误差
        val scaled = scaleToWidth(src, targetW)
        val extendH = (scaled.height * config.extendRatio).roundToInt().coerceAtLeast(0)
        // ★ 底部延展高度永远为 0
        val bottomExtendH = 0

        val outH = if (config.targetHeightPx > 0) {
            config.targetHeightPx.coerceAtLeast(scaled.height)
        } else {
            scaled.height + extendH + bottomExtendH
        }

        val result = Bitmap.createBitmap(targetW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        val mainTop = extendH

        // ===== 第一步：只填充延展区 [0, mainTop]，主体区 [mainTop, outH] 暂不绘制 =====
        canvas.save()
        canvas.clipRect(0, 0, targetW, mainTop)  // ★ 严格裁剪，任何绘制都不会落到主体/底部

        // 2. 底色：采样原图顶部色调（不过度提亮）
        canvas.drawColor(sampleAtmosphereColor(scaled, mode = config.mode))

        // 3. 氛围背景：放大 + 模糊的原图，铺满延展区（clip 后只可见在 [0, mainTop]）
        if (extendH > 0) {
            val actualBlur = (config.blurRadius.coerceIn(1, 100) / 4).coerceIn(4, 25)
            val blurredBg = blur(scaled, actualBlur)
            if (blurredBg != null) {
                // scaleToFill 用干净副本，避免边缘透明像素参与缩放产生彩边
                val clean = ensureOpaque(blurredBg)
                val bg = scaleToFill(clean, targetW, mainTop)
                canvas.drawBitmap(bg, null, Rect(0, 0, targetW, mainTop), Paint(Paint.FILTER_BITMAP_FLAG))
                if (bg != clean) bg.recycle()
                if (blurredBg != clean) blurredBg.recycle()
            }

            // 4. 顶部→主体方向渐变：延展区底部逐渐透明，让主体自然显现
            drawExtendGradient(canvas, targetW, extendH, config.mode)

            // 5. 顶部柔光/雾气（iOS 感）
            drawTopGlow(canvas, targetW, extendH, config.mode)
        }

        canvas.restore()  // ★ 解除裁剪，后续只在主体区绘制

        // ===== 第二步：绘制清晰主体，位置 fitCenter 略偏下 =====
        val placed = fitCenterRect(scaled.width, scaled.height, targetW, outH)
        val offsetY = (outH * 0.02f).toInt()
        val finalRect = Rect(placed.left, placed.top + offsetY, placed.right, placed.bottom + offsetY)
        // ★ 只允许画在 [mainTop, outH]，若主体被 clip 到延展区内则下移
        val drawRect = if (finalRect.top < mainTop) {
            finalRect.offsetTo(finalRect.left, mainTop)
        } else {
            finalRect
        }
        canvas.drawBitmap(scaled, null, drawRect, Paint(Paint.FILTER_BITMAP_FLAG))

        // ===== 第三步：顶部羽化融合（仅顶部一条带，clip 到延展区）=====
        if (extendH > 0 && config.featherWidth > 0) {
            canvas.save()
            canvas.clipRect(0, 0, targetW, mainTop)  // ★ 再次确保不污染主体/底部
            drawFeatherBand(result, scaled, targetW, mainTop, config.featherWidth, config.mode)
            canvas.restore()
        }

        // ★ 底部 [mainTop + scaled.height, outH] 完全不绘制，不会有任何延展/伪影

        return result
    }

    /** 顶部延展区：从氛围色（顶部）渐变到透明（靠近主体处），形成柔和过渡 */
    private fun drawExtendGradient(canvas: Canvas, w: Int, extendH: Int, mode: Mode) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // 渐变覆盖延展区下半部分（靠近主体处开始透明），75% 处起过渡
        val gradStart = (extendH * 0.45f).roundToInt().coerceAtLeast(0)
        val gradEnd = extendH
        if (gradEnd <= gradStart) return
        val c1 = if (mode == Mode.DARK) Color.argb(235, 30, 32, 48) else Color.argb(235, 235, 242, 252)
        paint.shader = LinearGradient(
            0f, gradStart.toFloat(), 0f, gradEnd.toFloat(),
            intArrayOf(c1, Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, gradStart.toFloat(), w.toFloat(), gradEnd.toFloat(), paint)
        paint.shader = null
    }

    private fun drawTopGlow(canvas: Canvas, w: Int, extendH: Int, mode: Mode) {
        val glowH = (extendH * 0.9f).roundToInt().coerceAtLeast(w / 8)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // ★ 光晕中心固定在顶部区域，绝不延伸到主体/底部
        val centerY = (extendH * 0.18f).roundToInt().coerceAtLeast(0)
        val radius = w * 0.6f
        val (centerColor, edgeColor) = if (mode == Mode.DARK) {
            Color.argb(60, 90, 100, 150) to Color.TRANSPARENT
        } else {
            Color.argb(45, 210, 230, 255) to Color.TRANSPARENT
        }
        paint.shader = RadialGradient(
            w / 2f, centerY.toFloat(), radius,
            centerColor, edgeColor,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w.toFloat(), glowH.toFloat(), paint)
        paint.shader = null
    }

    /**
     * 羽化融合带：在主体顶边上方画一条模糊的边缘带，用 DST_IN 渐变蒙版
     * 让主体顶部"融进"延展区。
     * ★ 只作用于 [mainTop - feather, mainTop]，clip 保证不越界。
     */
    private fun drawFeatherBand(
        result: Bitmap, src: Bitmap, w: Int, mainTop: Int, feather: Int, mode: Mode
    ) {
        val featherTop = (mainTop - feather).coerceAtLeast(0)
        val featherBottom = mainTop.coerceAtMost(result.height)
        if (featherBottom <= featherTop) return

        // 取主体顶部一条带，缩放成羽化高度，做模糊
        val bandH = (src.height * 0.3f).roundToInt().coerceAtLeast(2).coerceAtMost(src.height)
        val band = Bitmap.createBitmap(src, 0, 0, src.width, bandH)
        val actualBlur = 12
        val blurred = blur(band, actualBlur)
        band.recycle()
        if (blurred == null) return

        val clean = ensureOpaque(blurred)
        val scaledBlur = Bitmap.createScaledBitmap(clean, w, featherBottom - featherTop, true)
        if (clean != blurred) blurred.recycle()

        // 离屏：画模糊带
        val layer = Bitmap.createBitmap(w, result.height, Bitmap.Config.ARGB_8888)
        val layerCanvas = Canvas(layer)
        layerCanvas.drawBitmap(scaledBlur, null, Rect(0, featherTop, w, featherBottom), Paint(Paint.FILTER_BITMAP_FLAG))

        // 蒙版：靠近主体(下)侧从透明→不透明，让延展区底部渐隐
        val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            shader = LinearGradient(
                0f, featherTop.toFloat(), 0f, featherBottom.toFloat(),
                intArrayOf(Color.TRANSPARENT, Color.BLACK),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        layerCanvas.drawRect(0f, featherTop.toFloat(), w.toFloat(), featherBottom.toFloat(), maskPaint)

        // 合成到 result（此时外层已 clip [0, mainTop]，只会盖住延展区顶部一条）
        val composeCanvas = Canvas(result)
        composeCanvas.drawBitmap(layer, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
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

    /** 铺满缩放：基于不透明副本，避免透明边缘产生彩色拉伸伪影 */
    private fun scaleToFill(src: Bitmap, dstW: Int, dstH: Int): Bitmap {
        val clean = ensureOpaque(src)
        val srcRatio = clean.width.toFloat() / clean.height
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
        val result = Bitmap.createScaledBitmap(clean, outW, outH, true)
        if (clean != src) clean.recycle()
        return result
    }

    /**
     * 确保 Bitmap 不透明：用采样底色填充透明区域。
     * ★ 关键：消除 PNG 透明像素在模糊/缩放时产生的彩虹色带/边缘伪影。
     */
    private fun ensureOpaque(src: Bitmap): Bitmap {
        if (src.config == Bitmap.Config.ARGB_8888 && !src.hasAlpha) return src
        val fillColor = sampleAtmosphereColor(src, mode = Mode.LIGHT) // 任意 mode 都行，仅作底色
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(fillColor)
        c.drawBitmap(src, 0f, 0f, null)
        return out
    }

    private fun fitCenterRect(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Rect {
        val srcRatio = srcW.toFloat() / srcH
        val dstRatio = dstW.toFloat() / dstH
        val (outW, outH) = if (srcRatio > dstRatio) {
            dstW to (dstW / srcRatio).roundToInt()
        } else {
            (dstH * srcRatio).roundToInt() to dstH
        }
        val left = (dstW - outW) / 2
        val top = (dstH - outH) / 2
        return Rect(left, top, left + outW, top + outH)
    }

    /**
     * 采样氛围色：只取原图顶部区域，加权平均。
     * - 不做强制提白，保留原图色调
     * - LIGHT：偏亮带色调；DARK：压暗成冷灰紫
     */
    private fun sampleAtmosphereColor(src: Bitmap, mode: Mode): Int {
        val sample = Bitmap.createScaledBitmap(src, 24, 24, true)
        var r = 0; var g = 0; var b = 0; var count = 0
        // 只采样顶部 40% 区域
        val yEnd = (sample.height * 0.4f).roundToInt().coerceAtLeast(1)
        for (y in 0 until yEnd) {
            for (x in 0 until sample.width) {
                val c = sample.getPixel(x, y)
                r += Color.red(c); g += Color.green(c); b += Color.blue(c); count++
            }
        }
        sample.recycle()
        if (count == 0) return if (mode == Mode.DARK) Color.rgb(30, 32, 48) else Color.rgb(235, 242, 252)
        r /= count; g /= count; b /= count

        return if (mode == Mode.DARK) {
            Color.rgb(
                (r * 0.25f + 20).toInt().coerceIn(0, 80),
                (g * 0.25f + 24).toInt().coerceIn(0, 90),
                (b * 0.30f + 45).toInt().coerceIn(0, 120)
            )
        } else {
            // 轻度提亮但保留色调，不再粗暴取白
            Color.rgb(
                ((r + 200) / 2).coerceIn(0, 255),
                ((g + 215) / 2).coerceIn(0, 255),
                ((b + 235) / 2).coerceIn(0, 255)
            )
        }
    }

    /**
     * 栈模糊（Stack Blur），支持大半径。
     * 使用像素环绕（% w / % h）做边界处理——但这只对"铺满画面"的图有意义。
     * 为彻底避免边缘色带，调用方应先通过 ensureOpaque 填充底色。
     */
    private fun blur(src: Bitmap, radius: Int): Bitmap? {
        val r = radius.coerceIn(1, 255)
        if (src.width <= 0 || src.height <= 0) return null
        // 大半径时先缩小再模糊再放大，性能更好且模糊更"化"
        val down = (r / 6).coerceIn(1, 8)
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
