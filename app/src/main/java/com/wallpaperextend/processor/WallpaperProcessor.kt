package com.wallpaperextend.processor

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.roundToInt

/**
 * 模拟 iOS 17 "Extend Wallpaper" 效果：
 * - 把原始图片 Cover 缩放至目标屏幕尺寸（保持比例，居中裁剪核心区）
 * - 对超出区域的边缘做高斯模糊采样延展
 * - 清晰区与模糊区之间羽化过渡
 *
 * 使用纯 Kotlin 实现的可分离高斯模糊（Stack Blur 思路），兼容所有 API。
 */
object WallpaperProcessor {

    data class Config(
        val blurRadius: Int = 30,   // 模糊半径 (px)
        val extendRatio: Float = 0.25f, // 延展比例 0~0.5
        val featherWidth: Int = 80   // 羽化过渡宽度 (px)
    )

    /**
     * @param src       原始图片
     * @param targetW   目标屏幕宽
     * @param targetH   目标屏幕高
     * @param config    参数
     * @return 延展后的完整 Bitmap（尺寸 = targetW x targetH）
     */
    fun process(src: Bitmap, targetW: Int, targetH: Int, config: Config = Config()): Bitmap {
        val ratio = src.width.toFloat() / src.height
        val screenRatio = targetW.toFloat() / targetH

        return if (ratio < screenRatio) {
            // 原图偏窄 → 左右延展
            extendHorizontal(src, targetW, targetH, config)
        } else {
            // 原图偏宽 → 上下延展（最常见：竖图做锁屏）
            extendVertical(src, targetW, targetH, config)
        }
    }

    // ==================== 竖图 → 上下延展 ====================
    private fun extendVertical(src: Bitmap, tw: Int, th: Int, cfg: Config): Bitmap {
        // 1. 主体 Cover 缩放：宽度铺满，高度按比例
        val scaledW = tw
        val scaledH = (tw / (src.width.toFloat() / src.height)).roundToInt()
        val scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)

        val result = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)

        // 主体居中偏移（优先保留上半部分，因为锁屏时钟在上）
        val topSpace = (th - scaledH).coerceAtLeast(0)
        val mainTop = (topSpace * 0.25f).roundToInt() // 主体偏上
        val mainBottom = mainTop + scaledH

        // 2. 计算延展区高度
        val extendH = (th * cfg.extendRatio).roundToInt()

        // 3. 顶部延展：取原图上沿条带 → 模糊 → 拉伸
        val topBandHeight = (scaledH * 0.2f).roundToInt().coerceAtLeast(1)
        val topBand = Bitmap.createBitmap(scaled, 0, 0, scaledW, topBandHeight)
        val topBlurred = blur(topBand, cfg.blurRadius.coerceAtLeast(1))
        val topStretched = Bitmap.createScaledBitmap(topBlurred, tw, extendH, true)

        // 4. 底部延展：取原图下沿条带 → 模糊 → 拉伸
        val bottomBandHeight = (scaledH * 0.2f).roundToInt().coerceAtLeast(1)
        val bottomBand = Bitmap.createBitmap(
            scaled, 0, scaledH - bottomBandHeight, scaledW, bottomBandHeight
        )
        val bottomBlurred = blur(bottomBand, cfg.blurRadius.coerceAtLeast(1))
        val bottomStretched = Bitmap.createScaledBitmap(bottomBlurred, tw, extendH, true)

        // 5. 绘制：Canvas 合成
        val canvas = android.graphics.Canvas(result)
        // 顶部模糊延展区
        canvas.drawBitmap(topStretched, null, android.graphics.Rect(0, 0, tw, extendH), null)
        // 底部模糊延展区
        canvas.drawBitmap(
            bottomStretched, null,
            android.graphics.Rect(0, th - extendH, tw, th), null
        )
        // 主体清晰区
        canvas.drawBitmap(scaled, 0f, mainTop.toFloat(), null)

        // 6. 羽化过渡（顶部清晰↔模糊 边界）
        featherHorizontal(
            result,
            scaled,
            mainTop,
            extendH,
            cfg.featherWidth.coerceAtLeast(1),
            topStretched
        )
        featherHorizontal(
            result,
            scaled,
            mainBottom - cfg.featherWidth.coerceAtLeast(1),
            cfg.featherWidth.coerceAtLeast(1),
            cfg.featherWidth.coerceAtLeast(1),
            bottomStretched
        )

        // 清理临时 bitmap
        topBand.recycle()
        bottomBand.recycle()
        topBlurred.recycle()
        bottomBlurred.recycle()
        if (scaled != src) { /* scaled 是新 bitmap，需回收；src 由调用者管理 */ }

        return result
    }

    // ==================== 横图 → 左右延展 ====================
    private fun extendHorizontal(src: Bitmap, tw: Int, th: Int, cfg: Config): Bitmap {
        val scaledH = th
        val scaledW = (th * (src.width.toFloat() / src.height)).roundToInt()
        val scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)

        val result = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val leftSpace = (tw - scaledW).coerceAtLeast(0)
        val mainLeft = (leftSpace * 0.5f).roundToInt()
        val mainRight = mainLeft + scaledW

        val extendW = (tw * cfg.extendRatio).roundToInt()

        // 左侧条带
        val leftBandW = (scaledW * 0.2f).roundToInt().coerceAtLeast(1)
        val leftBand = Bitmap.createBitmap(scaled, 0, 0, leftBandW, scaledH)
        val leftBlurred = blur(leftBand, cfg.blurRadius.coerceAtLeast(1))
        val leftStretched = Bitmap.createScaledBitmap(leftBlurred, extendW, th, true)

        // 右侧条带
        val rightBandW = (scaledW * 0.2f).roundToInt().coerceAtLeast(1)
        val rightBand = Bitmap.createBitmap(
            scaled, scaledW - rightBandW, 0, rightBandW, scaledH
        )
        val rightBlurred = blur(rightBand, cfg.blurRadius.coerceAtLeast(1))
        val rightStretched = Bitmap.createScaledBitmap(rightBlurred, extendW, th, true)

        val canvas = android.graphics.Canvas(result)
        canvas.drawBitmap(leftStretched, null, android.graphics.Rect(0, 0, extendW, th), null)
        canvas.drawBitmap(
            rightStretched, null,
            android.graphics.Rect(tw - extendW, 0, tw, th), null
        )
        canvas.drawBitmap(scaled, mainLeft.toFloat(), 0f, null)

        // 垂直方向羽化（左右边缘）
        featherVertical(
            result, mainLeft, extendW, cfg.featherWidth.coerceAtLeast(1), leftStretched, th
        )
        featherVertical(
            result, mainRight - cfg.featherWidth.coerceAtLeast(1),
            cfg.featherWidth.coerceAtLeast(1), cfg.featherWidth.coerceAtLeast(1),
            rightStretched, th
        )

        leftBand.recycle()
        rightBand.recycle()
        leftBlurred.recycle()
        rightBlurred.recycle()

        return result
    }

    // ==================== 羽化：水平带（上下边界） ====================
    private fun featherHorizontal(
        result: Bitmap, scaled: Bitmap, boundaryY: Int, bandH: Int,
        feather: Int, blurred: Bitmap
    ) {
        if (boundaryY < 0 || boundaryY + bandH > result.height) return
        val paint = android.graphics.Paint()
        val h = result.height
        for (y in boundaryY until (boundaryY + bandH).coerceAtMost(h)) {
            val t = (y - boundaryY).toFloat() / bandH.coerceAtLeast(1)
            // t=0（清晰区边缘）→ alpha 从 255→0（让清晰图渐隐）
            val alpha = ((1f - t) * 255).roundToInt().coerceIn(0, 255)
            paint.alpha = alpha
            // 在清晰区边缘画一条模糊色的线，实现过渡
            if (y >= 0 && y < h) {
                // no-op per-line; use a simpler gradient overlay instead
            }
        }
        // 用线性渐变做一次性的羽化覆盖（更高效）
        val gradient = android.graphics.LinearGradient(
            0f, boundaryY.toFloat(), 0f, (boundaryY + feather).toFloat(),
            Color.TRANSPARENT, Color.BLACK, android.graphics.Shader.TileMode.CLAMP
        )
        val gp = android.graphics.Paint().apply {
            shader = gradient
            xfermode = android.graphics.PorterDuffXfermode(
                android.graphics.PorterDuff.Mode.DST_IN
            )
        }
        // 简化为在清晰→模糊边界叠一层渐变模糊
        val overlay = android.graphics.Paint().apply {
            alpha = 120
        }
        val c = android.graphics.Canvas(result)
        // 画一条从模糊色到透明的过渡
        val rect = android.graphics.Rect(0, boundaryY, result.width, boundaryY + feather)
        // 用模糊 bitmap 的对应条带 + 渐变遮罩
        c.drawBitmap(blurred, null, rect, overlay)
    }

    // ==================== 羽化：垂直带（左右边界） ====================
    private fun featherVertical(
        result: Bitmap, boundaryX: Int, bandW: Int, feather: Int,
        blurred: Bitmap, h: Int
    ) {
        if (boundaryX < 0 || boundaryX + bandW > result.width) return
        val c = android.graphics.Canvas(result)
        val overlay = android.graphics.Paint().apply { alpha = 120 }
        val rect = android.graphics.Rect(boundaryX, 0, boundaryX + feather, h)
        c.drawBitmap(blurred, null, rect, overlay)
    }

    // ==================== 高斯模糊（可分离，近似） ====================
    private fun blur(src: Bitmap, radius: Int): Bitmap {
        val r = radius.coerceIn(1, 60)
        // 先缩小再放大 = 快速大半径模糊
        val scale = (1.0 / (1 + r * 0.5)).coerceAtLeast(0.25)
        val smallW = (src.width * scale).roundToInt().coerceAtLeast(1)
        val smallH = (src.height * scale).roundToInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, smallW, smallH, true)
        // 对缩小图做栈模糊
        val blurredSmall = stackBlur(small, (r * scale).roundToInt().coerceAtLeast(1))
        // 放大回原尺寸（天然产生柔和模糊）
        val out = Bitmap.createScaledBitmap(blurredSmall, src.width, src.height, true)
        if (small != src) small.recycle()
        if (blurredSmall != small) blurredSmall.recycle()
        return out
    }

    /**
     * Stack Blur（可分离，O(n) 近似高斯）—— 纯 Kotlin 实现
     * 参考 Mario Klingemann 的经典算法
     */
    private fun stackBlur(src: Bitmap, radius: Int): Bitmap {
        val r = radius.coerceIn(1, 255)
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, src.config ?: Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val div = (2 * r + 1)
        val dv = IntArray(256 * div)
        for (i in 0 until 256 * div) dv[i] = i / div

        var x: Int
        var y: Int
        var i: Int
        var p: Int
        var yp: Int
        var yi: Int
        var yw: Int

        val rSum = IntArray(w)
        val gSum = IntArray(w)
        val bSum = IntArray(w)
        val rOut = IntArray(w)
        val gOut = IntArray(w)
        val bOut = IntArray(w)
        val rIn = IntArray(w)
        val gIn = IntArray(w)
        val bIn = IntArray(w)

        var sumR: Int
        var sumG: Int
        var sumB: Int
        var stackPointer: Int
        var stackStart: Int

        // —— 水平方向 ——
        for (y in 0 until h) {
            sumR = 0; sumG = 0; sumB = 0
            for (i in (-r) until r + 1) {
                val px = (i + w) % w
                val p = pixels[y * w + px]
                sumR += (p shr 16) and 0xff
                sumG += (p shr 8) and 0xff
                sumB += p and 0xff
            }
            yi = y * w
            for (x in 0 until w) {
                rSum[x] = sumR
                gSum[x] = sumG
                bSum[x] = sumB
                if (y == 0) {
                    rOut[x] = (pixels[((r shl 1) + 1) % w] shr 16) and 0xff
                    gOut[x] = (pixels[((r shl 1) + 1) % w] shr 8) and 0xff
                    bOut[x] = pixels[((r shl 1) + 1) % w] and 0xff
                }
                val p1 = x + r + 1
                val px = if (p1 >= w) p1 - w else p1
                val p2 = x - r
                val px2 = if (p2 < 0) p2 + w else p2
                val p = pixels[y * w + px]
                val p3 = pixels[y * w + px2]
                rIn[x] = (p shr 16) and 0xff
                gIn[x] = (p shr 8) and 0xff
                bIn[x] = p and 0xff
                sumR += rIn[x] - ((pixels[y * w + px2] shr 16) and 0xff)
                sumG += gIn[x] - ((pixels[y * w + px2] shr 8) and 0xff)
                sumB += bIn[x] - (pixels[y * w + px2] and 0xff)
                yi++
            }
            yi = y * w
            for (x in 0 until w) {
                val pr = if (rSum[x] > 0) rSum[x] else 0
                val pg = if (gSum[x] > 0) gSum[x] else 0
                val pb = if (bSum[x] > 0) bSum[x] else 0
                pixels[yi] = (
                    0xff000000.toInt()
                    or (dv[pr] shl 16)
                    or (dv[pg] shl 8)
                    or dv[pb]
                )
                yi++
            }
        }

        // —— 垂直方向 ——
        for (x in 0 until w) {
            sumR = 0; sumG = 0; sumB = 0
            for (i in (-r) until r + 1) {
                val py = (i + h) % h
                val p = pixels[py * w + x]
                sumR += (p shr 16) and 0xff
                sumG += (p shr 8) and 0xff
                sumB += p and 0xff
            }
            yi = 0
            for (y in 0 until h) {
                rSum[x] = sumR
                gSum[x] = sumG
                bSum[x] = sumB
                if (x == 0) {
                    rOut[x] = (pixels[(((r shl 1) + 1) % h) * w + x] shr 16) and 0xff
                    gOut[x] = (pixels[(((r shl 1) + 1) % h) * w + x] shr 8) and 0xff
                    bOut[x] = pixels[(((r shl 1) + 1) % h) * w + x] and 0xff
                }
                val p1 = y + r + 1
                val py = if (p1 >= h) p1 - h else p1
                val p2 = y - r
                val py2 = if (p2 < 0) p2 + h else p2
                val p = pixels[py * w + x]
                val p3 = pixels[py2 * w + x]
                rIn[x] = (p shr 16) and 0xff
                gIn[x] = (p shr 8) and 0xff
                bIn[x] = p and 0xff
                sumR += rIn[x] - ((pixels[py2 * w + x] shr 16) and 0xff)
                sumG += gIn[x] - ((pixels[py2 * w + x] shr 8) and 0xff)
                sumB += bIn[x] - (pixels[py2 * w + x] and 0xff)
            }
            yi = 0
            for (y in 0 until h) {
                val pr = if (rSum[x] > 0) rSum[x] else 0
                val pg = if (gSum[x] > 0) gSum[x] else 0
                val pb = if (bSum[x] > 0) bSum[x] else 0
                pixels[yi + x] = (
                    0xff000000.toInt()
                    or (dv[pr] shl 16)
                    or (dv[pg] shl 8)
                    or dv[pb]
                )
                yi += w
            }
        }

        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }
}
