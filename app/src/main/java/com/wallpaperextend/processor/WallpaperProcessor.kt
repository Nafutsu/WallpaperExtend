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

/**
 * iOS 17 风格壁纸延展处理器（v3）。
 *
 * 核心策略：
 *   1) 原图按 targetW 等比缩放，底部对齐屏幕（drawY = targetH - scaledH）。
 *   2) 顶部留白区 = extendH，由"原图顶部边缘采样条"填充：
 *        - 采样条先【顺时针旋转 180°】（绕自身中心）；
 *        - 再纵向拉伸到 extendH、做高斯模糊。
 *      ★ 旋转 180° 后，采样条【靠近原图的那条边】恰好是原图顶部边缘的镜像，
 *        因此这条边与原图顶部像素级连续 —— 接缝处【清晰对齐、不做模糊过渡】。
 *      ★ 渐变模糊只发生在延展区的【上半部分】（远离原图、靠近屏幕顶端的方向），
 *        即：越往上越模糊、越淡，顶部自然融入底色。这才是 iOS 的真实观感。
 *   3) 底色 = 原图顶部边缘主色（不用纯白），从根本上消灭"白条/白边"。
 *   4) 全链路用 ceil() 取整 + 防御性补边，杜绝 1px 浮点缝隙露底。
 *
 * 与 v2 的关键区别：v2 把渐变方向搞反了（接缝处用了 DST_OUT），导致
 * 用户圈出的"衔接处"反而模糊断裂。v3 改为【接缝清晰 + 上方渐隐】。
 */
object WallpaperProcessor {

    data class Config(
        val blurRadius: Int = 32,
        val extendRatio: Float = 0.37f,   // 上限系数；实际 extendH = targetH - scaledH
        val featherWidth: Int = 100,      // 延展区【上半部分】做渐隐模糊的过渡带宽度
        val topOnly: Boolean = true
    )

    fun process(src: Bitmap, targetW: Int, targetH: Int, config: Config): Bitmap {
        require(targetW > 0 && targetH > 0) { "target size must be positive" }

        val blur = config.blurRadius.coerceAtLeast(1)
        val feather = config.featherWidth.coerceAtLeast(8)

        // ---- 1. 原图按宽度等比缩放（高度用 ceil 避免截断露底白线）----
        val scaledW = targetW
        val scaledH = ceil(src.height * targetW.toFloat() / src.width).toInt().coerceAtLeast(1)

        // 若原图本身就比屏幕高（长图），直接裁剪居中返回，不做延展
        if (scaledH >= targetH) {
            return cropCenter(src, targetW, targetH)
        }

        val scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)

        // ---- 2. 计算布局：原图底部对齐，顶部留白即为延展区 ----
        val extendH = (targetH - scaledH).coerceAtLeast(0)
        val drawY = targetH - scaledH   // 原图顶部 y（= 接缝 y）

        // 底色 = 原图顶部边缘主色（防白边）
        val edgeColor = sampleTopEdgeColor(src, ratio = 0.05f)

        val result = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(edgeColor)

        if (extendH <= 0) {
            // 无延展空间：直接贴原图
            canvas.drawBitmap(scaled, 0f, drawY.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG))
            if (drawY + scaledH < targetH) {
                canvas.drawRect(0f, (drawY + scaledH).toFloat(), targetW.toFloat(), targetH.toFloat(),
                    Paint().apply { color = edgeColor })
            }
            scaled.recycle()
            return result
        }

        // ---- 3. 从原图顶部取采样条 ----
        val stripH = max(6, scaledH / 7).coerceAtMost(scaledH)
        val topStrip = Bitmap.createBitmap(scaled, 0, 0, scaledW, stripH)

        // ---- 4. 【顺时针旋转 180°】（绕采样条中心）----
        //     Android Matrix.setRotate(180) = 顺时针 180°。
        //     旋转后：原采样条"下边"（靠近原图）翻转到上方，"上边"（远离原图）翻转到下方。
        //     → 翻转后条带【底部边缘】= 原图顶部边缘的镜像 → 与接缝对齐。
        val rotate = Matrix().apply { setRotate(180f) }
        val rotated = Bitmap.createBitmap(topStrip, 0, 0, topStrip.width, topStrip.height, rotate, true)
        topStrip.recycle()

        // 拉伸到延展区全高（此时 rotated 底部 = 接缝侧）
        val stretched = Bitmap.createScaledBitmap(rotated, targetW, extendH, true)
        rotated.recycle()

        // ---- 5. 高斯模糊（在旋转后的条带上做）----
        val blurred = stackBlur(stretched, blur)
        if (blurred !== stretched) stretched.recycle()

        // ---- 6. 绘制延展层：先铺满顶部 extendH ----
        //     接缝(y=drawY)处是 rotated 的底部 = 原图顶部镜像 → 清晰连续，不做渐变。
        canvas.drawBitmap(blurred, 0f, drawY.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG))

        // ---- 7. 只在延展区【上半部分】做渐隐模糊（远离原图方向）----
        //     即从 drawY（接缝，完全不透明）到 drawY+feather（完全透明）做线性渐隐。
        //     这样：接缝 = 100% 清晰对齐；往上逐渐模糊淡出 → 顶部自然融入底色。
        if (feather > 0) {
            val fadeTop = drawY.toFloat()
            val fadeBottom = (drawY + feather).toFloat()
            val layer = canvas.saveLayer(0f, fadeTop, targetW.toFloat(), drawY + extendH.toFloat(), null)
            val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                shader = LinearGradient(
                    0f, fadeTop,
                    0f, fadeBottom,
                    intArrayOf(Color.TRANSPARENT, Color.BLACK),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            // 在延展区上半部分擦除（渐隐）：底部(接缝)全透(保留清晰)，顶部全擦(淡出)
            canvas.drawRect(0f, fadeTop, targetW.toFloat(), fadeBottom, mask)
            canvas.restoreToCount(layer)
        }

        // ---- 8. 绘制原图（接缝处与延展层像素级贴合）----
        canvas.drawBitmap(scaled, 0f, drawY.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG))

        // ---- 9. 防御性兜底：若仍有露底，用边缘色补齐 ----
        if (drawY + scaledH < targetH) {
            canvas.drawRect(0f, (drawY + scaledH).toFloat(), targetW.toFloat(), targetH.toFloat(),
                Paint().apply { color = edgeColor })
        }

        // result 画布已拷贝 scaled / blurred 的像素，可安全回收中间 Bitmap
        if (blurred !== scaled) blurred.recycle()
        scaled.recycle()
        return result
    }

    // ================= 工具函数 =================

    /** 长图（scaledH >= targetH）：等比缩放到目标尺寸，不做延展。不回收入参。 */
    private fun cropCenter(src: Bitmap, w: Int, h: Int): Bitmap {
        if (src.width == w && src.height == h) return src
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    /** 采样原图顶部边缘的主色，用作底色（避免纯白露底）。 */
    private fun sampleTopEdgeColor(src: Bitmap, ratio: Float): Int {
        val stripH = max(1, (src.height * ratio).toInt().coerceAtLeast(2))
        val sample = Bitmap.createBitmap(src, 0, 0, src.width, stripH)
        var r = 0L; var g = 0L; var b = 0L; var count = 0
        val pixels = IntArray(sample.width * sample.height)
        sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
        for (p in pixels) {
            r += Color.red(p); g += Color.green(p); b += Color.blue(p); count++
        }
        sample.recycle()
        if (count == 0) return Color.BLACK
        return Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }

    /**
     * Fast Stack Blur（横向 + 纵向各一遍，O(n) 近似高斯模糊）。
     * 与原项目 blur 算法等价，不引入新依赖。
     */
    private fun stackBlur(sentBitmap: Bitmap, radius: Int): Bitmap {
        if (radius < 1) return sentBitmap
        val w = sentBitmap.width
        val h = sentBitmap.height
        val pix = IntArray(w * h)
        sentBitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val div = (radius shl 1) + 1
        val dv = IntArray(256 * div)
        for (i in dv.indices) dv[i] = i / div

        var yi = 0
        val stack = Array(div) { IntArray(3) }

        // ---- 横向模糊 ----
        for (y in 0 until h) {
            var rsum = 0; var gsum = 0; var bsum = 0
            var rout = 0; var gout = 0; var bout = 0
            var rin = 0;  var gin = 0;  var bin = 0
            for (i in -radius..radius) {
                val p = pix[minOf(maxOf(i, 0), w - 1) + yi]
                val s = stack[i + radius]
                s[0] = Color.red(p); s[1] = Color.green(p); s[2] = Color.blue(p)
                val rbs = radius - kotlin.math.abs(i)
                rsum += s[0] * rbs; gsum += s[1] * rbs; bsum += s[2] * rbs
                if (i > 0) { rin += s[0]; gin += s[1]; bin += s[2] }
                else { rout += s[0]; gout += s[1]; bout += s[2] }
            }
            var sp = radius
            for (x in 0 until w) {
                pix[yi + x] = (pix[yi + x] and 0xFF000000.toInt()) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]
                val s0 = stack[sp]
                rout -= s0[0]; gout -= s0[1]; bout -= s0[2]
                val off = x + radius + 1
                val p = pix[minOf(maxOf(off, 0), w - 1) + yi]
                s0[0] = Color.red(p); s0[1] = Color.green(p); s0[2] = Color.blue(p)
                rin += s0[0]; gin += s0[1]; bin += s0[2]
                rsum += rin; gsum += gin; bsum += bin
                rin -= rout; gin -= gout; bin -= bout
                sp = (sp + 1) % div
            }
            yi += w
        }

        // ---- 纵向模糊 ----
        for (x in 0 until w) {
            var rsum = 0; var gsum = 0; var bsum = 0
            var rout = 0; var gout = 0; var bout = 0
            var rin = 0;  var gin = 0;  var bin = 0
            for (i in -radius..radius) {
                val p = pix[minOf(maxOf(i, 0), h - 1) * w + x]
                val s = stack[i + radius]
                s[0] = Color.red(p); s[1] = Color.green(p); s[2] = Color.blue(p)
                val rbs = radius - kotlin.math.abs(i)
                rsum += s[0] * rbs; gsum += s[1] * rbs; bsum += s[2] * rbs
                if (i > 0) { rin += s[0]; gin += s[1]; bin += s[2] }
                else { rout += s[0]; gout += s[1]; bout += s[2] }
            }
            var sp = radius
            var yp = x
            for (y in 0 until h) {
                pix[yp] = (pix[yp] and 0xFF000000.toInt()) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]
                val s0 = stack[sp]
                rout -= s0[0]; gout -= s0[1]; bout -= s0[2]
                val off = y + radius + 1
                val p = pix[minOf(maxOf(off, 0), h - 1) * w + x]
                s0[0] = Color.red(p); s0[1] = Color.green(p); s0[2] = Color.blue(p)
                rin += s0[0]; gin += s0[1]; bin += s0[2]
                rsum += rin; gsum += gin; bsum += bin
                rin -= rout; gin -= gout; bin -= bout
                sp = (sp + 1) % div
                yp += w
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pix, 0, w, 0, 0, w, h)
        return out
    }
}
