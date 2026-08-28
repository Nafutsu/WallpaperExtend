package com.wallpaperextend.processor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import kotlin.math.roundToInt

object WallpaperProcessor {

    enum class Mode { LIGHT, DARK }

    data class Config(
        val blurRadius: Int = 56,
        val extendRatio: Float = 0.32f,
        val featherWidth: Int = 120,
        val topOnly: Boolean = true,   // 现在固定 true，保留字段兼容旧调用
        val mode: Mode = Mode.LIGHT
    )

    /**
     * @param src      原始图片
     * @param targetW  输出宽度（一般 = 屏幕宽）
     * @param targetH  输出高度（一般 = 屏幕高）
     */
    fun process(src: Bitmap, targetW: Int, targetH: Int, config: Config = Config()): Bitmap {
        // 1. 保证不透明，避免模糊产生彩色拖影
        val safe = ensureOpaque(src)

        // 2. 等比缩放到目标宽度
        val scaled = scaleToWidth(safe, targetW)
        val srcW = scaled.width
        val srcH = scaled.height

        // 3. 只顶部延展
        val topH = (targetH * config.extendRatio.coerceIn(0f, 0.6f)).roundToInt().coerceAtLeast(0)
        val mainTop = topH
        val outH = targetH

        val out = Bitmap.createBitmap(targetW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(backgroundBaseColor(config.mode))

        val drawX = ((targetW - srcW) / 2f).coerceAtLeast(0f)

        // 4. 顶部氛围延展区（严格 clip 在 [0, topH]）
        drawTopExtension(canvas, scaled, targetW, topH, config)

        // 5. 画主体
        canvas.drawBitmap(scaled, drawX, mainTop.toFloat(), null)

        // 6. 顶部羽化融合（严格 clip 在 [mainTop-feather, mainTop]）
        drawTopFeather(canvas, targetW, mainTop, config.featherWidth.coerceAtLeast(1))

        // 回收临时
        if (safe !== src) safe.recycle()
        if (scaled !== src && scaled !== safe) scaled.recycle()

        return out
    }

    /* ===================== 内部实现 ===================== */

    private fun backgroundBaseColor(mode: Mode): Int =
        if (mode == Mode.DARK) Color.rgb(18, 20, 24) else Color.rgb(245, 248, 252)

    /** 透明像素会导致栈模糊把边缘"透明"混入产生彩边，先垫白底转不透明 */
    private fun ensureOpaque(src: Bitmap): Bitmap {
        if (src.hasAlpha().not()) return src
        // 抽样检查是否真的有透明像素
        val step = 8.coerceAtLeast(src.width / 32)
        for (y in 0 until src.height step step) {
            for (x in 0 until src.width step step) {
                if (Color.alpha(src.getPixel(x, y)) < 255) {
                    val b = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
                    Canvas(b).apply {
                        drawColor(Color.WHITE)
                        drawBitmap(src, 0f, 0f, null)
                    }
                    return b
                }
            }
        }
        return src
    }

    private fun drawTopExtension(
        canvas: Canvas, src: Bitmap, w: Int, topH: Int, config: Config
    ) {
        if (topH <= 0) return

        canvas.save()
        canvas.clipRect(0f, 0f, w.toFloat(), topH.toFloat())

        val baseColor = sampleAtmosphereColor(src, config.mode)
        canvas.drawColor(baseColor)

        // 取原图顶部 28% 做模糊氛围
        val sliceH = (src.height * 0.28f).roundToInt().coerceAtLeast(1).coerceAtMost(src.height)
        val slice = Bitmap.createBitmap(src, 0, 0, src.width, sliceH)
        val blur = stackBlur(scaleToWidth(slice, w), config.blurRadius.coerceIn(0, 120))
        slice.recycle()

        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            alpha = if (config.mode == Mode.DARK) 170 else 215
        }
        canvas.drawBitmap(blur, 0f, 0f, paint)

        // 顶部往下的轻微提亮渐变，让时钟区干净
        val grad = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, topH.toFloat(),
                intArrayOf(
                    if (config.mode == Mode.DARK) Color.argb(90, 0, 0, 0)
                    else Color.argb(70, 255, 255, 255),
                    Color.TRANSPARENT
                ),
                null, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), topH.toFloat(), grad)
        grad.shader = null

        blur.recycle()
        canvas.restore()
    }

    private fun drawTopFeather(canvas: Canvas, w: Int, mainTop: Int, featherWidth: Int) {
        if (mainTop <= 0) return
        val feather = featherWidth.coerceIn(0, mainTop)
        val startY = (mainTop - feather).toFloat()
        val endY = mainTop.toFloat()

        canvas.save()
        canvas.clipRect(0f, startY, w.toFloat(), endY)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            shader = LinearGradient(
                0f, startY, 0f, endY,
                intArrayOf(Color.TRANSPARENT, Color.BLACK),
                null, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, startY, w.toFloat(), endY, paint)
        paint.shader = null
        canvas.restore()
    }

    private fun sampleAtmosphereColor(src: Bitmap, mode: Mode): Int {
        val sample = Bitmap.createScaledBitmap(src, 16, 16, true)
        var r = 0; var g = 0; var b = 0; var count = 0
        val endY = (sample.height * 0.3f).roundToInt().coerceAtLeast(1)
        for (y in 0 until endY) {
            for (x in 0 until sample.width) {
                val c = sample.getPixel(x, y)
                if (Color.alpha(c) < 16) continue
                r += Color.red(c); g += Color.green(c); b += Color.blue(c); count++
            }
        }
        sample.recycle()
        if (count == 0) {
            return if (mode == Mode.DARK) Color.rgb(30, 32, 48) else Color.rgb(220, 235, 245)
        }
        r /= count; g /= count; b /= count
        return if (mode == Mode.DARK) {
            Color.rgb(
                (r * 0.25f).roundToInt().coerceIn(0, 80),
                (g * 0.30f).roundToInt().coerceIn(0, 90),
                (b * 0.45f).roundToInt().coerceIn(0, 120)
            )
        } else {
            Color.rgb(
                ((r + 180) / 2).coerceIn(0, 255),
                ((g + 205) / 2).coerceIn(0, 255),
                ((b + 235) / 2).coerceIn(0, 255)
            )
        }
    }

    private fun scaleToWidth(src: Bitmap, targetW: Int): Bitmap {
        if (src.width == targetW) return src
        val targetH = (targetW.toFloat() / src.width * src.height).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, targetW, targetH, true)
    }

    /* ===================== 栈模糊（不依赖 RenderScript） ===================== */

    private fun stackBlur(s: Bitmap, radius: Int): Bitmap {
        if (radius <= 0) return s
        val r = radius.coerceIn(1, 255)
        val w = s.width
        val h = s.height
        val pix = IntArray(w * h)
        s.getPixels(pix, 0, w, 0, 0, w, h)
        stackBlur(pix, w, h, r)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pix, 0, w, 0, 0, w, h)
        if (out !== s) s.recycle()
        return out
    }

    private fun stackBlur(pix: IntArray, w: Int, h: Int, radius: Int) {
        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        val a = IntArray(wh)

        for (i in 0 until wh) {
            val p = pix[i]
            r[i] = Color.red(p)
            g[i] = Color.green(p)
            b[i] = Color.blue(p)
            a[i] = Color.alpha(p)
        }

        var yw = 0
        val vMin = IntArray(Math.max(w, h))

        // ===== 横向 pass =====
        var divSum = (div + 1) shr 1
        divSum *= divSum
        val dv = IntArray(256 * divSum)
        for (i in dv.indices) dv[i] = i / divSum

        for (y in 0 until h) {
            var sumR = 0; var sumG = 0; var sumB = 0; var sumA = 0
            for (i in -radius..radius) {
                val p = pix[yw + (i + w) % w]
                sumR += Color.red(p); sumG += Color.green(p); sumB += Color.blue(p); sumA += Color.alpha(p)
            }
            for (x in 0 until w) {
                r[yw + x] = dv[sumR.coerceIn(0, 255 * div)]
                g[yw + x] = dv[sumG.coerceIn(0, 255 * div)]
                b[yw + x] = dv[sumB.coerceIn(0, 255 * div)]
                a[yw + x] = dv[sumA.coerceIn(0, 255 * div)]

                val xi1 = x + radius + 1
                val xi2 = x - radius
                val p1 = pix[yw + if (xi1 <= wm) xi1 else xi1 - w]
                val p2 = pix[yw + if (xi2 >= 0) xi2 else xi2 + w]
                sumR += Color.red(p1) - Color.red(p2)
                sumG += Color.green(p1) - Color.green(p2)
                sumB += Color.blue(p1) - Color.blue(p2)
                sumA += Color.alpha(p1) - Color.alpha(p2)
            }
            yw += w
        }

        // ===== 纵向 pass =====
        yw = 0
        for (x in 0 until w) {
            var sumR = 0; var sumG = 0; var sumB = 0; var sumA = 0
            for (i in -radius..radius) {
                val idx = (i + h) % h
                val yi = idx * w + x
                sumR += r[yi]; sumG += g[yi]; sumB += b[yi]; sumA += a[yi]
            }
            for (y in 0 until h) {
                val outIdx = yw + x
                pix[outIdx] = Color.argb(dv[sumA], dv[sumR], dv[sumG], dv[sumB])

                val yi1 = y + radius + 1
                val yi2 = y - radius
                val idx1 = if (yi1 <= hm) yi1 else yi1 - h
                val idx2 = if (yi2 >= 0) yi2 else yi2 + h
                val p1 = idx1 * w + x
                val p2 = idx2 * w + x
                sumR += r[p1] - r[p2]
                sumG += g[p1] - g[p2]
                sumB += b[p1] - b[p2]
                sumA += a[p1] - a[p2]
            }
            yw += w
        }
    }
}
