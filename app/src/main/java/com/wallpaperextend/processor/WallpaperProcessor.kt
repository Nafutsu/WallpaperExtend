package com.wallpaperextend.processor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.palette.graphics.Palette
import kotlin.math.roundToInt

object WallpaperProcessor {

    // 绝对兼容的求最大值方法
    private fun maxInt(a: Int, b: Int): Int = if (a > b) a else b
    // 绝对兼容的求最小值方法
    private fun minInt(a: Int, b: Int): Int = if (a < b) a else b

    fun extendWallpaper(
        src: Bitmap,
        extendRatio: Float,
        blurRadius: Int,
        featherWidth: Int
    ): Bitmap {
        val w = src.width
        val h = src.height
        val extendH = (h * extendRatio).roundToInt()
        
        // 使用自定义 maxInt 防止 Kotlin 版本兼容报错
        val safeBlurRadius = maxInt(1, blurRadius)
        val safeFeather = maxInt(2, featherRadius = featherWidth)

        // 1. 采样顶部条带
        val stripH = maxInt(6, h / 7)
        val topStrip = Bitmap.createBitmap(src, 0, 0, w, stripH)

        // 2. iOS 算法：顺时针旋转 180 度
        val matrix = Matrix().apply { setRotate(180f) }
        val rotatedStrip = Bitmap.createBitmap(topStrip, 0, 0, w, stripH, matrix, true)
        topStrip.recycle()

        // 3. 拉伸旋转后的条带
        val stretched = Bitmap.createScaledBitmap(rotatedStrip, w, extendH, true)
        rotatedStrip.recycle()

        // 4. 模糊处理
        val blurredExtend = fastBoxBlur(s stretched, safeBlurRadius)

        // 5. 采样主色作为底色（防白边）
        val edgeColor = sampleTopEdgeColor(src)

        // 6. 组装最终画布
        val targetH = extendH + h
        val result = Bitmap.createBitmap(w, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(edgeColor)

        // 绘制延展区
        val drawY = extendH
        canvas.drawBitmap(blurredExtend, 0f, drawY.toFloat(), null)

        // 7. iOS 渐变融合（接缝清晰，向上渐隐）
        val paint = Paint().apply { isAntiAlias = true }
        val step = maxInt(1, safeFeather / 10)
        for (i in 0 until safeFeather step step) {
            val alpha = (255f * (i.toFloat() / safeFeather)).roundToInt()
            paint.color = edgeColor
            paint.alpha = alpha
            canvas.drawRect(0f, (drawY - safeFeather + i).toFloat(), w.toFloat(), (drawY - safeFeather + i + step).toFloat(), paint)
        }

        // 8. 绘制原图（向下重叠 1px 防浮点缝隙）
        canvas.drawBitmap(src, 0f, drawY.toFloat() + 1f, null)

        // 9. 兜底补边（防极端白线）
        val actualScaledH = drawY + h
        if (actualScaledH < targetH) {
            val bottomColor = sampleBottomEdgeColor(src)
            paint.color = bottomColor
            paint.alpha = 255
            canvas.drawRect(0f, actualScaledH.toFloat(), w.toFloat(), targetH.toFloat(), paint)
        }

        blurredExtend.recycle()
        return result
    }

    // --- 辅助方法 ---

    private fun sampleTopEdgeColor(bitmap: Bitmap): Int {
        try {
            val palette = Palette.from(bitmap).setRegion(0, 0, bitmap.width, maxInt(1, bitmap.height / 10)).generate()
            return palette.dominantSwatch?.rgb ?: palette.vibrantSwatch?.rgb ?: 0xFF000000.toInt()
        } catch (e: Exception) {
            return 0xFF000000.toInt()
        }
    }

    private fun sampleBottomEdgeColor(bitmap: Bitmap): Int {
        try {
            val regionH = maxInt(1, bitmap.height / 10)
            val palette = Palette.from(bitmap).setRegion(0, bitmap.height - regionH, bitmap.width, bitmap.height).generate()
            return palette.dominantSwatch?.rgb ?: 0xFF000000.toInt()
        } catch (e: Exception) {
            return 0xFF000000.toInt()
        }
    }

    // 极速方框模糊算法 (兼容所有 Kotlin 版本)
    private fun fastBoxBlur(src: Bitmap, radius: Int): Bitmap {
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
            var p = pixels[yw]

            for (i in -r..r) {
                val temp = pixels[yw + minInt(w - 1, maxInt(0, i))]
                rSum += (temp shr 16) and 0xFF
                gSum += (temp shr 8) and 0xFF
                bSum += temp and 0xFF
            }

            for (x in 0 until w) {
                pixels[yw + x] = (dv[rSum] shl 16) or (dv[gSum] shl 8) or dv[bSum]

                val oldOut = pixels[yw + maxInt(0, x - r)]
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
                val temp = pixels[minInt(h - 1, maxInt(0, i)) * w + x]
                rSum += (temp shr 16) and 0xFF
                gSum += (temp shr 8) and 0xFF
                bSum += temp and 0xFF
            }

            for (y in 0 until h) {
                pixels[y * w + x] = (dv[rSum] shl 16) or (dv[gSum] shl 8) or dv[bSum]

                val oldOut = pixels[maxInt(0, y - r) * w + x]
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
