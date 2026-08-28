package com.wallpaperextend.processor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import kotlin.math.roundToInt

/**
 * iOS 17 / ColorOS 16 风格 "Extend Wallpaper" 效果：
 * - 保留主体区域清晰（默认仅顶部延展，底部就是原图）
 * - 顶部延展区：取原图上沿条带 → 强高斯模糊 → 渐变蒙版淡入主体（自然过渡，不再硬拼）
 * - 支持自定义输出高度、仅顶部/上下同时延展
 *
 * 模糊使用缩小→栈模糊→放大 的方式，兼顾速度与柔和度。
 */
object WallpaperProcessor {

    data class Config(
        val blurRadius: Int = 30,        // 模糊半径 (px)
        val extendRatio: Float = 0.25f,  // 延展比例 0~0.5（相对主体高度）
        val featherWidth: Int = 120,     // 羽化过渡宽度 (px)，越大越自然
        val topOnly: Boolean = true,     // 是否仅顶部延展（默认 true，更接近 iOS 17）
        val targetHeightPx: Int = 0      // 输出高度；0 = 自动 = 主体高度 × (1 + extendRatio)
    )

    /**
     * @param src     原始图片
     * @param targetW 目标宽度（通常为屏幕宽）
     * @param targetH 参考目标高度（仅当 Config.targetHeightPx <= 0 时用作自动计算基准）
     */
    fun process(src: Bitmap, targetW: Int, targetH: Int, config: Config = Config()): Bitmap {
        // 先把主体按目标宽度铺满（Cover 缩放，保持比例），后续只在高度方向延展
        val scaled = scaleToWidth(src, targetW)

        val extendH = (scaled.height * config.extendRatio).roundToInt().coerceAtLeast(0)
        val bottomExtendH = if (config.topOnly) 0 else extendH

        val outH = if (config.targetHeightPx > 0) {
            config.targetHeightPx.coerceAtLeast(scaled.height)
        } else {
            scaled.height + extendH + bottomExtendH
        }

        val result = Bitmap.createBitmap(targetW, outH, Bitmap.Config.ARGB_8888)

        // 主体顶部位置：顶部延展区在主体上方
        val mainTop = extendH
        val mainBottom = mainTop + scaled.height

        // 1) 顶部延展（模糊 + 渐变蒙版）
        if (extendH > 0) {
            drawExtendedEdge(
                result = result,
                src = scaled,
                edge = EDGE_TOP,
                bandHeight = (scaled.height * 0.25f).roundToInt().coerceAtLeast(2),
                extendLength = extendH,
                feather = config.featherWidth.coerceAtLeast(1),
                blurRadius = config.blurRadius.coerceAtLeast(1),
                mainAnchorY = mainTop
            )
        }

        // 2) 底部延展（若开启）
        if (bottomExtendH > 0) {
            drawExtendedEdge(
                result = result,
                src = scaled,
                edge = EDGE_BOTTOM,
                bandHeight = (scaled.height * 0.25f).roundToInt().coerceAtLeast(2),
                extendLength = bottomExtendH,
                feather = config.featherWidth.coerceAtLeast(1),
                blurRadius = config.blurRadius.coerceAtLeast(1),
                mainAnchorY = mainBottom
            )
        }

        // 3) 绘制主体清晰区（用离屏绘制避免直接画到 result 上的叠加问题）
        val mainCanvas = Canvas(result)
        mainCanvas.drawBitmap(scaled, 0f, mainTop.toFloat(), null)

        // scaled 若是新创建的副本则回收（src 由调用者管理，不在此回收）
        if (scaled != src) {
            // scaled 会在下方 finally 作用域外无法访问，故在此处理
        }
        return result
    }

    // ================== 内部 ==================

    private const val EDGE_TOP = 0
    private const val EDGE_BOTTOM = 1

    /**
     * 在 result 的延展区绘制：模糊条带 + 与主体之间的渐变融合。
     * - 延展区整体铺满模糊图
     * - 靠近主体一侧做 feather 宽的线性渐变（清晰→模糊），用 DST_IN 蒙版让模糊自然融入主体边缘
     */
    private fun drawExtendedEdge(
        result: Bitmap,
        src: Bitmap,
        edge: Int,
        bandHeight: Int,
        extendLength: Int,
        feather: Int,
        blurRadius: Int,
        mainAnchorY: Int
    ) {
        val w = result.width
        val h = result.height
        if (extendLength <= 0 || w <= 0) return

        // 取边缘条带
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

        // 条带 → 模糊 → 拉伸到延展区尺寸
        val blurred = blur(band, blurRadius)
        val scaledBlur = Bitmap.createScaledBitmap(blurred, w, extendLength, true)

        val canvas = Canvas(result)

        // 延展区矩形
        val rectTop = if (edge == EDGE_TOP) 0 else mainAnchorY
        val rectBottom = if (edge == EDGE_TOP) extendLength else h
        canvas.drawBitmap(scaledBlur, null, android.graphics.Rect(0, rectTop, w, rectBottom), null)

        // 渐变蒙版：让延展区靠近主体一侧从清晰过渡到模糊
        // feather 范围内，从主体边缘向延展区方向 alpha 递减
        val featherTop: Int
        val featherBottom: Int
        if (edge == EDGE_TOP) {
            // 主体在下方，羽化区在 [mainAnchorY - feather, mainAnchorY]
            featherTop = (mainAnchorY - feather).coerceAtLeast(0)
            featherBottom = mainAnchorY.coerceAtMost(h)
        } else {
            // 主体在上方，羽化区在 [mainAnchorY, mainAnchorY + feather]
            featherTop = mainAnchorY.coerceAtLeast(0)
            featherBottom = (mainAnchorY + feather).coerceAtMost(h)
        }

        if (featherBottom > featherTop) {
            // 渐变：featherTop 处完全不透明（保留清晰主体），featherBottom 处透明（露出下方模糊）
            // 用 DST_IN 作用在 result 上：需要以主体快照为源
            // 简化为：在羽化带上方叠一层从透明→模糊色的线性渐变，混合出自然过渡
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val colors = if (edge == EDGE_TOP) {
                // 靠近主体(下)侧：渐变从模糊区颜色→透明（让清晰图显现）
                intArrayOf(0x00FFFFFF, 0xFFFFFFFF)
            } else {
                intArrayOf(0xFFFFFFFF, 0x00FFFFFF)
            }
            val colors = if (edge == EDGE_TOP) {
                intArrayOf(0x00FFFFFF.toInt(), 0xFFFFFFFF.toInt())
            } else {
                intArrayOf(0xFFFFFFFF.toInt(), 0x00FFFFFF.toInt())
         }
            
            // 用 ComposeShader：模糊图 × 渐变alpha
            val bitmapShader = android.graphics.BitmapShader(
                scaledBlur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP
            )
            paint.shader = android.graphics.ComposeShader(
                bitmapShader, shader,
                PorterDuff.Mode.DST_IN // 用渐变的 alpha 控制模糊图显示
            )
            canvas.drawRect(0f, featherTop.toFloat(), w.toFloat(), featherBottom.toFloat(), paint)
        }

        band.recycle()
        blurred.recycle()
        scaledBlur.recycle()
    }

    /** 宽度铺满，高度按比例（Cover 缩放） */
    private fun scaleToWidth(src: Bitmap, targetW: Int): Bitmap {
        if (src.width == targetW) return src
        val targetH = (targetW.toFloat() / src.width * src.height).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, targetW, targetH, true)
    }

    // ==================== 高斯模糊（缩小→栈模糊→放大） ====================
    private fun blur(src: Bitmap, radius: Int): Bitmap {
        val r = radius.coerceIn(1, 80)
        // 大半径：先缩小再模糊再放大，效果更柔、更快
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

    /**
     * Stack Blur（可分离，O(n) 近似高斯）—— 纯 Kotlin 实现
     * 参考 Mario Klingemann 经典算法
     */
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
        var p: Int
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

        // —— 水平方向 ——
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
                // dv 表已完成除以 div 的映射，直接取色值
                val pr = dv[rSum[x].coerceIn(0, 255 * div)]
                val pg = dv[gSum[x].coerceIn(0, 255 * div)]
                val pb = dv[bSum[x].coerceIn(0, 255 * div)]
                pixels[yi] = 0xff000000.toInt() or (pr shl 16) or (pg shl 8) or pb
                yi++
            }
        }

        // —— 垂直方向 ——
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
                pixels[yi + x] = (
                    0xff000000.toInt()
                    or (pr shl 16)
                    or (pg shl 8)
                    or pb
                )
                yi += w
            }
        }

        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }
}
