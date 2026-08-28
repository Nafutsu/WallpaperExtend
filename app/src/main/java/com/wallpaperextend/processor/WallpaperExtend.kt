package com.wallpaperextend.processor

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

object WallpaperExtend {

    /**
     * 只顶部延展：
     * 原图下方不动，上方追加一段由"原图顶部边缘延续 + 模糊 + 主色调和"构成的过渡区，
     * 通过接缝渐变让延展区底部与原图顶部自然融合（不再有白块/硬线）。
     */
    fun extendTop(src: Bitmap, extendH: Int, featherH: Int, blurRadius: Int): Bitmap {
        val safe = ensureOpaque(src)
        val srcW = safe.width
        val srcH = safe.height

        val topH = extendH.coerceAtLeast(0)
        val outH = topH + srcH
        val out = Bitmap.createBitmap(srcW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        // 延展区画在 [0, topH]
        if (topH > 0) {
            drawTopExtension(canvas, safe, srcW, topH, blurRadius, featherH)
        }

        // 原图画在延展区下方，完整保留
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(safe, 0f, topH.toFloat(), paint)

        if (safe !== src) safe.recycle()
        return out
    }

    /**
     * 只底部延展（对称实现）：原图上方不动，下方追加过渡区。
     */
    fun extendBottom(src: Bitmap, extendH: Int, featherH: Int, blurRadius: Int): Bitmap {
        val safe = ensureOpaque(src)
        val srcW = safe.width
        val srcH = safe.height

        val bottomH = extendH.coerceAtLeast(0)
        val outH = srcH + bottomH
        val out = Bitmap.createBitmap(srcW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        // 原图在顶部
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(safe, 0f, 0f, paint)

        if (bottomH > 0) {
            drawBottomExtension(canvas, safe, srcW, srcH, bottomH, blurRadius, featherH)
        }

        if (safe !== src) safe.recycle()
        return out
    }

    /* ================= 顶部延展绘制 ================= */

    private fun drawTopExtension(
        canvas: Canvas, src: Bitmap, w: Int, topH: Int, blurRadius: Int, feather: Int
    ) {
        // 1) 取原图顶部边缘窄条，纵向拉伸到延展区高度（延续背景纹理）
        val stripH = max(6, src.height / 40)
        val topStrip = Bitmap.createBitmap(src, 0, 0, src.width, min(stripH, src.height))
        val stretched = Bitmap.createScaledBitmap(topStrip, w, topH, true)
        topStrip.recycle()

        // 2) 高斯模糊（半径已做安全钳制）
        val blurred = stackBlur(stretched, blurRadius)
        stretched.recycle()

        // 3) 画模糊底色
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(blurred, 0f, 0f, paint)

        // 4) 用顶部主色轻覆盖，统一色调（半透明，避免脏灰边）
        val topAvg = sampleTopColor(src, 0.2f)
        val tonePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        tonePaint.color = Color.argb(26,
            Color.red(topAvg), Color.green(topAvg), Color.blue(topAvg))
        canvas.drawRect(0f, 0f, w.toFloat(), topH.toFloat(), tonePaint)

        // 顶部往下的轻微提亮
        tonePaint.color = Color.argb(14, 255, 255, 255)
        canvas.drawRect(0f, 0f, w.toFloat(), topH.toFloat(), tonePaint)

        // 5) 接缝融合（关键）：
        //    延展区底部（靠近原图的一侧）渐隐，让原图自然透上来。
        //    DST_OUT + 渐变：透明→黑 表示 保留→清除。
        //    startY 处（远离原图/顶部）=透明（保留），endY 处（接缝）=黑色（清除），
        //    即延展区从下往上逐渐保留，底部边缘最淡，实现与原图的柔和过渡。
        val f = feather.coerceIn(8, topH)
        val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        fadePaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        fadePaint.shader = LinearGradient(
            0f, (topH - f).toFloat(), 0f, topH.toFloat(),
            intArrayOf(Color.TRANSPARENT, Color.BLACK), // 远接缝→保留 ; 接缝→清除
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, (topH - f).toFloat(), w.toFloat(), topH.toFloat(), fadePaint)

        blurred.recycle()
    }

    /* ================= 底部延展绘制 ================= */

    private fun drawBottomExtension(
        canvas: Canvas, src: Bitmap, w: Int, srcH: Int, bottomH: Int, blurRadius: Int, feather: Int
    ) {
        val stripH = max(6, src.height / 40)
        val bottomStrip = Bitmap.createBitmap(src, 0, src.height - stripH, src.width, stripH)
        val stretched = Bitmap.createScaledBitmap(bottomStrip, w, bottomH, true)
        bottomStrip.recycle()

        val blurred = stackBlur(stretched, blurRadius)
        stretched.recycle()

        canvas.drawBitmap(blurred, 0f, srcH.toFloat(),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))

        val bottomAvg = sampleBottomColor(src, 0.2f)
        val tonePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        tonePaint.color = Color.argb(26,
            Color.red(bottomAvg), Color.green(bottomAvg), Color.blue(bottomAvg))
        canvas.drawRect(0f, srcH.toFloat(), w.toFloat(), (srcH + bottomH).toFloat(), tonePaint)

        tonePaint.color = Color.argb(14, 255, 255, 255)
        canvas.drawRect(0f, srcH.toFloat(), w.toFloat(), (srcH + bottomH).toFloat(), tonePaint)

        // 接缝融合：底部延展的顶部（紧贴原图处）渐隐
        val f = feather.coerceIn(8, bottomH)
        val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        fadePaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        fadePaint.shader = LinearGradient(
            0f, srcH.toFloat(), 0f, (srcH + f).toFloat(),
            intArrayOf(Color.TRANSPARENT, Color.BLACK), // 远接缝→保留 ; 接缝→清除
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, srcH.toFloat(), w.toFloat(), (srcH + f).toFloat(), fadePaint)

        blurred.recycle()
    }

    /* ================= 取色 ================= */

    private fun sampleTopColor(src: Bitmap, ratio: Float): Int {
        val sample = Bitmap.createScaledBitmap(src, 32, 32, true)
        var r = 0; var g = 0; var b = 0; var count = 0
        val endY = max(1, (sample.height * ratio).toInt())
        for (y in 0 until endY) {
            for (x in 0 until sample.width) {
                val c = sample.getPixel(x, y)
                if (Color.alpha(c) < 128) continue
                r += Color.red(c); g += Color.green(c); b += Color.blue(c); count++
            }
        }
        sample.recycle()
        if (count == 0) return Color.WHITE
        return Color.rgb(r / count, g / count, b / count)
    }

    private fun sampleBottomColor(src: Bitmap, ratio: Float): Int {
        val sample = Bitmap.createScaledBitmap(src, 32, 32, true)
        var r = 0; var g = 0; var b = 0; var count = 0
        val startY = max(0, sample.height - (sample.height * ratio).toInt())
        for (y in startY until sample.height) {
            for (x in 0 until sample.width) {
                val c = sample.getPixel(x, y)
                if (Color.alpha(c) < 128) continue
                r += Color.red(c); g += Color.green(c); b += Color.blue(c); count++
            }
        }
        sample.recycle()
        if (count == 0) return Color.WHITE
        return Color.rgb(r / count, g / count, b / count)
    }

    /* ================= 工具 ================= */

    private fun ensureOpaque(src: Bitmap): Bitmap {
        if (!src.hasAlpha()) return src
        val b = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(b).apply {
            drawColor(Color.WHITE)
            drawBitmap(src, 0f, 0f, null)
        }
        return b
    }

    /* ================= 栈模糊（模运算防越界，已验证） ================= */

    private fun stackBlur(s: Bitmap, radius: Int): Bitmap {
        // 放宽上限，让 UI 的 blurRadius(25~35) 真正生效
        val radIn = radius.coerceIn(1, 120)
        val w = s.width
        val h = s.height
        if (w <= 0 || h <= 0) return s

        // 模糊只是做背景过渡，超过此尺寸先缩小以省内存/提速
        val MAX_DIM = 1024
        val work = if (max(w, h) > MAX_DIM) {
            val scale = MAX_DIM.toFloat() / max(w, h)
            Bitmap.createScaledBitmap(s,
                max(1, (w * scale).toInt()), max(1, (h * scale).toInt()), true)
        } else {
            s
        }
        val ww = work.width
        val hh = work.height
        val size = ww * hh
        val pixels = IntArray(size)
        work.getPixels(pixels, 0, ww, 0, 0, ww, hh)

        // 半径按实际尺寸再钳制，保证模运算不会越界
        val maxRad = (min(ww, hh) - 1) / 2
        val rad = min(radIn, maxRad).coerceAtLeast(1)

        try {
            stackBlurH(pixels, ww, hh, rad)
            stackBlurV(pixels, ww, hh, rad)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val out = Bitmap.createBitmap(ww, hh, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, ww, 0, 0, ww, hh)
        if (work !== s) work.recycle()
        return out
    }

    /**
     * 横向栈模糊：索引全部用 (i + w) % w 模运算，保证永不越界。
     */
    private fun stackBlurH(pixels: IntArray, w: Int, h: Int, radius: Int) {
        val div = (2 * radius + 1).coerceAtLeast(1)
        val dv = IntArray(256 * div)
        for (i in 0 until 256 * div) dv[i] = i / div

        for (y in 0 until h) {
            var sumR = 0; var sumG = 0; var sumB = 0; var sumA = 0
            for (i in -radius..radius) {
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
                val xiOut = (x - radius + w) % w
                val xiIn = (x + radius + 1 + w) % w
                val pOut = pixels[y * w + xiOut]
                val pIn = pixels[y * w + xiIn]
                sumR += Color.red(pIn) - Color.red(pOut)
                sumG += Color.green(pIn) - Color.green(pOut)
                sumB += Color.blue(pIn) - Color.blue(pOut)
                sumA += Color.alpha(pIn) - Color.alpha(pOut)
            }
        }
    }

    /**
     * 纵向栈模糊：索引全部用 (i + h) % h 模运算，保证永不越界。
     */
    private fun stackBlurV(pixels: IntArray, w: Int, h: Int, radius: Int) {
        val div = (2 * radius + 1).coerceAtLeast(1)
        val dv = IntArray(256 * div)
        for (i in 0 until 256 * div) dv[i] = i / div

        for (x in 0 until w) {
            var sumR = 0; var sumG = 0; var sumB = 0; var sumA = 0
            for (i in -radius..radius) {
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
                val yiOut = ((y - radius + h) % h) * w + x
                val yiIn = ((y + radius + 1 + h) % h) * w + x
                sumR += Color.red(pixels[yiIn]) - Color.red(pixels[yiOut])
                sumG += Color.green(pixels[yiIn]) - Color.green(pixels[yiOut])
                sumB += Color.blue(pixels[yiIn]) - Color.blue(pixels[yiOut])
                sumA += Color.alpha(pixels[yiIn]) - Color.alpha(pixels[yiOut])
            }
        }
    }
}
