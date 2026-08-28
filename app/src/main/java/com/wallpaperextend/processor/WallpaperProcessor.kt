package com.wallpaperextend.processor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.roundToInt

object WallpaperProcessor {

    private const val MAX_BLUR_RADIUS = 50

    /**
     * 处理壁纸：顶部正向拉伸模糊延展 + 渐变平滑过渡
     */
    fun process(
        original: Bitmap,
        targetW: Int,
        targetH: Int,
        extendRatio: Float = 0.3f,
        blurRadius: Int = 30
    ): Bitmap? {
        if (original.isRecycled) return null

        val scaledBitmap = Bitmap.createScaledBitmap(original, targetW, targetH, true)
        val scaledH = scaledBitmap.height

        if (scaledH >= targetH) {
            return scaledBitmap
        }

        val extendH = (targetH * extendRatio).roundToInt().coerceAtMost(targetH - scaledH)
        if (extendH <= 0) return scaledBitmap

        val result = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // 1. 提取原图顶部条带
        val topStripH = 50.coerceAtMost(original.height / 10)
        val topStrip = Bitmap.createBitmap(original, 0, 0, original.width, topStripH)

        // 2. 拉伸条带到目标延展高度
        val stretched = Bitmap.createScaledBitmap(topStrip, targetW, extendH, true)

        // 3. 重度模糊
        val safeBlurRadius = blurRadius.coerceIn(1, MAX_BLUR_RADIUS)
        val blurred = stackBlur(stretched, safeBlurRadius)

        // 4. 绘制模糊延展区
        canvas.drawBitmap(blurred, 0f, 0f, null)

        // 5. 提取顶部主色调
        val baseColor = getAverageColor(topStrip)

        // 6. 渐变遮罩：顶部透明 → 底部主色调
        val gradient = LinearGradient(
            0f, 0f,
            0f, extendH.toFloat(),
            intArrayOf(Color.TRANSPARENT, baseColor),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )

        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = gradient
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }

        // 7. 应用渐变遮罩
        canvas.drawRect(0f, 0f, targetW.toFloat(), extendH.toFloat(), maskPaint)

        // 8. 绘制原图
        val srcDrawY = extendH
        canvas.drawBitmap(scaledBitmap, 0f, srcDrawY.toFloat(), null)

        // 回收中间变量
        topStrip.recycleSafe()
        stretched.recycleSafe()
        blurred.recycleSafe()
        scaledBitmap.recycleSafe()

        return result
    }

    private fun getAverageColor(bitmap: Bitmap): Int {
        if (bitmap.isRecycled) return Color.WHITE
        val smallBmp = Bitmap.createScaledBitmap(bitmap, 10, 10, true)
        var r = 0L
        var g = 0L
        var b = 0L
        for (x in 0 until smallBmp.width) {
            for (y in 0 until smallBmp.height) {
                val pixel = smallBmp.getPixel(x, y)
                r += Color.red(pixel)
                g += Color.green(pixel)
                b += Color.blue(pixel)
            }
        }
        val count = smallBmp.width * smallBmp.height
        smallBmp.recycleSafe()
        return Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }

    private fun stackBlur(sentBitmap: Bitmap, radius: Int): Bitmap {
        if (radius < 1) return sentBitmap
        val bitmap = Bitmap.createBitmap(sentBitmap)

        val w = bitmap.width
        val h = bitmap.height
        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        var rsum: Int
        var gsum: Int
        var bsum: Int
        var x: Int
        var y: Int
        var i: Int
        var p: Int
        var yp: Int
        var yi: Int
        var yw: Int
        val vmin = IntArray(w.coerceAtLeast(h))

        var divsum = div + 1 shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        i = 0
        while (i < 256 * divsum) {
            dv[i] = i / divsum
            i++
        }

        yi = 0
        yw = yi
        val stack = Array(div) { IntArray(3) }
        var stackpointer: Int
        var stackstart: Int
        var sir: IntArray
        var rbs: Int
        val r1 = radius + 1
        var routsum: Int
        var goutsum: Int
        var boutsum: Int
        var rinsum: Int
        var ginsum: Int
        var binsum: Int

        y = 0
        while (y < h) {
            bsum = 0
            gsum = 0
            rsum = 0
            routsum = 0
            goutsum = 0
            boutsum = 0
            rinsum = 0
            ginsum = 0
            binsum = 0

            for (i in -radius..radius) {
                p = pix[yi + (i.coerceIn(0, wm))]
                sir = stack[i + radius]
                sir[0] = Color.red(p)
                sir[1] = Color.green(p)
                sir[2] = Color.blue(p)

                rbs = r1 - abs(i)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs

                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
            }
            stackpointer = radius

            x = 0
            while (x < w) {
                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (y == 0) {
                    vmin[x] = (x + radius + 1).coerceAtMost(wm)
                }
                p = pix[yw + vmin[x]]
                sir[0] = Color.red(p)
                sir[1] = Color.green(p)
                sir[2] = Color.blue(p)

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi++
                x++
            }
            yw += w
            y++
        }

        x = 0
        while (x < w) {
            bsum = 0
            gsum = 0
            rsum = 0
            routsum = 0
            goutsum = 0
            boutsum = 0
            rinsum = 0
            ginsum = 0
            binsum = 0
            yp = -radius * w
            for (i in -radius..radius) {
                yi = (0).coerceAtLeast(yp) + x
                sir = stack[i + radius]
                sir[0] = r[yi]
                sir[1] = g[yi]
                sir[2] = b[yi]

                rbs = r1 - abs(i)
                rsum += r[yi] * rbs
                gsum += g[yi] * rbs
                bsum += b[yi] * rbs

                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
                if (i < hm) yp += w
            }
            yi = x
            stackpointer = radius
            y = 0
            while (y < h) {
                pix[yi] = Color.argb(255, dv[rsum], dv[gsum], dv[bsum])

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (x == 0) {
                    vmin[y] = (y + r1).coerceAtMost(hm) * w
                }
                p = x + vmin[y]
                sir[0] = r[p]
                sir[1] = g[p]
                sir[2] = b[p]

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi += w
                y++
            }
            x++
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
        return bitmap
    }

    private fun Bitmap.recycleSafe() {
        if (!this.isRecycled) {
            this.recycle()
        }
    }
}
