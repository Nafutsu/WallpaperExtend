package com.wallpaperextend.processor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.palette.graphics.Palette

object WallpaperProcessor {

    /**
     * 对外唯一入口：生成 iOS 17 风格延展壁纸
     */
    fun extendWallpaper(
        src: Bitmap,
        extendRatio: Float = 0.35f,
        blurRadius: Int = 30,
        featherWidth: Int = 120
    ): Bitmap {
        val w = src.width
        val h = src.height
        val extendH = (h * extendRatio).toInt().coerceAtLeast(0)

        // 采样原图顶部条带
        val stripH = (h / 7).coerceAtLeast(6)
        val topStrip = Bitmap.createBitmap(src, 0, 0, w, stripH.coerceAtMost(h))

        // iOS 核心：顺时针旋转 180°
        val matrix = Matrix().apply { setRotate(180f) }
        val rotatedStrip = Bitmap.createBitmap(
            topStrip, 0, 0, topStrip.width, topStrip.height, matrix, true
        )
        topStrip.recycle()

        // 拉伸到延展区尺寸
        val stretched = Bitmap.createScaledBitmap(rotatedStrip, w, extendH, true)
        rotatedStrip.recycle()

        // 高斯模糊
        val safeRadius = blurRadius.coerceIn(1, 100)
        val blurredExtend = stackBlur(stretched, safeRadius)
        stretched.recycle()

        // 采样顶部主色（防白边底色）
        val edgeColor = sampleEdgeColor(src)

        // 组装最终画布
        val targetH = extendH + h
        val result = Bitmap.createBitmap(w, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(edgeColor)

        // 绘制延展区（从 y=0 开始，因为原图在下方）
        canvas.drawBitmap(blurredExtend, 0f, 0f, null)

        // iOS 渐变融合：从延展区底部向上做渐变，让接缝处自然过渡
        val safeFeather = featherWidth.coerceAtLeast(2).coerceAtMost(extendH)
        if (safeFeather > 0) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val steps = 20
            val stepH = safeFeather.toFloat() / steps
            for (i in 0 until steps) {
                val alpha = (255f * (i.toFloat() / steps)).toInt()
                paint.color = edgeColor
                paint.alpha = alpha
                val y = extendH - safeFeather + (i * stepH)
                canvas.drawRect(0f, y, w.toFloat(), y + stepH + 1f, paint)
            }
        }

        // 绘制原图（底部对齐，向下重叠 1px 防浮点缝隙）
        val drawY = extendH.toFloat()
        val srcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
        canvas.drawBitmap(src, 0f, drawY + 1f, srcPaint)

        // 兜底：如果还有露底，补齐
        val bottomY = drawY + h
        if (bottomY < targetH) {
            val fillPaint = Paint().apply { color = edgeColor }
            canvas.drawRect(0f, bottomY, w.toFloat(), targetH.toFloat(), fillPaint)
        }

        blurredExtend.recycle()
        return result
    }

    // ─── 采样边缘主色 ───
    private fun sampleEdgeColor(bitmap: Bitmap): Int {
        return try {
            val h = (bitmap.height / 10).coerceAtLeast(1)
            val slice = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, h)
            val color = Palette.from(slice).generate()
                .getDominantColor(0xFF222222.toInt())
            slice.recycle()
            color
        } catch (e: Exception) {
            0xFF222222.toInt()
        }
    }

    // ─── Stack Blur 算法 ───
    private fun stackBlur(src: Bitmap, radius: Int): Bitmap {
        var r = radius
        if (r < 1) r = 1
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val div = r + r + 1
        val dv = IntArray(256 * div)
        for (i in dv.indices) dv[i] = i / div

        var yw = 0
        for (y in 0 until h) {
            var rSum = 0
            var gSum = 0
            var bSum = 0
            var rOutSum = 0
            var gOutSum = 0
            var bOutSum = 0
            var rInSum = 0
            var gInSum = 0
            var bInSum = 0

            for (i in -r..r) {
                val temp = pixels[yw + (w - 1).coerceAtMost(0.coerceAtLeast(i))]
                rSum += (temp shr 16) and 0xFF
                gSum += (temp shr 8) and 0xFF
                bSum += temp and 0xFF
            }

            for (x in 0 until w) {
                pixels[yw + x] = (dv[rSum] shl 16) or (dv[gSum] shl 8) or dv[bSum]

                val oldOut = pixels[yw + (0.coerceAtLeast(x - r))]
                rOutSum -= (oldOut shr 16) and 0xFF
                gOutSum -= (oldOut shr 8) and 0xFF
                bOutSum -= oldOut and 0xFF

                if (x + r < w - 1) {
                    val oldIn = pixels[yw + x + r + 1]
                    rInSum += (oldIn shr 16) and 0xFF
                    gInSum += (oldIn shr 8) and 0xFF
                    bInSum += oldIn and 0xFF
                }

                rSum += rInSum - rOutSum
                gSum += gInSum - gOutSum
                bSum += bInSum - bOutSum
            }
            yw += w
        }

        yw = 0
        for (x in 0 until w) {
            var rSum = 0
            var gSum = 0
            var bSum = 0
            var rOutSum = 0
            var gOutSum = 0
            var bOutSum = 0
            var rInSum = 0
            var gInSum = 0
            var bInSum = 0

            for (i in -r..r) {
                val temp = pixels[(h - 1).coerceAtMost(0.coerceAtLeast(i)) * w + x]
                rSum += (temp shr 16) and 0xFF
                gSum += (temp shr 8) and 0xFF
                bSum += temp and 0xFF
            }

            for (y in 0 until h) {
                pixels[y * w + x] = (dv[rSum] shl 16) or (dv[gSum] shl 8) or dv[bSum]

                val oldOut = pixels[(0.coerceAtLeast(y - r)) * w + x]
                rOutSum -= (oldOut shr 16) and 0xFF
                gOutSum -= (oldOut shr 8) and 0xFF
                bOutSum -= oldOut and 0xFF

                if (y + r < h - 1) {
                    val oldIn = pixels[(y + r + 1) * w + x]
                    rInSum += (oldIn shr 16) and 0xFF
                    gInSum += (oldIn shr 8) and 0xFF
                    bInSum += oldIn and 0xFF
                }

                rSum += rInSum - rOutSum
                gSum += gInSum - gOutSum
                bSum += bInSum - bOutSum
            }
        }

        val result = Bitmap.createBitmap(w, h, src.config ?: Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }
}
