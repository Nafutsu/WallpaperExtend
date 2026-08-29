package com.wallpaperextend.processor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import com.wallpaperextend.processor.NPUImageProcessingUtils
import com.wallpaperextend.processor.NPU.NpuExtendEngine
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 壁纸处理统一入口（混合架构）。
 *
 * 管线：
 *   1. GPU（stackBlur）生成主体延展区 —— 永远执行，保证不出纯色块（兜底）。
 *   2. 若 useNpu = true，截取原图顶部边缘条带，交给 NpuExtendEngine 生成局部过渡区，
 *      覆盖绘制在原图与延展区的接缝处，实现更自然的 AI 过渡。NPU 失败自动降级，不影响主体。
 *
 * - process 为 suspend 函数，在 Dispatchers.Default 中执行，不阻塞 UI。
 * - extendRatio 作为"最大延展比例"，实际延展高度由内部动态计算。
 */
object WallpaperProcessor {

    // 持有 NPU 引擎（懒加载，进程级单例）
    private var npuEngine: NpuExtendEngine? = null

    private fun getNpu(context: Context): NpuExtendEngine {
        if (npuEngine == null) npuEngine = NpuExtendEngine(context.applicationContext)
        return npuEngine!!
    }

    /**
     * 统一入口：suspend + context + useNpu
     */
    suspend fun process(
        context: Context,
        src: Bitmap,
        targetW: Int,
        targetH: Int,
        config: WallpaperConfig,
        useNpu: Boolean = false
    ): Bitmap {
        if (targetW <= 0 || targetH <= 0) return src

        val edgeColor = NPUImageProcessingUtils.sampleTopEdgeColor(src, ratio = 0.05f)
        val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(edgeColor)

        val scaledW = targetW
        val scaledH = ceil(src.height * targetW.toFloat() / src.width).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)

        // 实际延展高度：若 topOnly，延展区填满"原图上方剩余空间"，上限受 extendRatio 约束
        val maxExtend = (targetH * config.extendRatio.coerceIn(0.05f, 0.6f)).toInt()
        val extendH = if (config.topOnly) {
            (targetH - scaledH).coerceAtLeast(0).coerceAtMost(maxExtend)
        } else {
            maxExtend
        }

        val srcDrawY = (targetH - scaledH).toFloat()

        // ★ 延展区（GPU 主体 + 可选 NPU 接缝细化）
        if (extendH > 0) {
            drawTopExtension(
                context = context,
                canvas = canvas,
                scaled = scaled,
                src = src,
                targetW = targetW,
                extendH = extendH,
                blurRadius = config.blurRadius.toInt(),
                feather = config.featherWidth,
                useNpu = useNpu
            )
        }

        // ★ 再画原图（原图覆盖在延展区下方接缝之上，形成自然衔接）
        canvas.drawBitmap(
            scaled,
            0f,
            srcDrawY,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )

        // 底部填充
        if (srcDrawY + scaledH < targetH) {
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = edgeColor }
            canvas.drawRect(
                0f,
                (srcDrawY + scaledH).coerceAtMost(targetH.toFloat()),
                targetW.toFloat(),
                targetH.toFloat(),
                fill
            )
        }

        if (scaled !== src) scaled.recycle()
        return out
    }

    // ★ 混合架构：GPU 主体延展 + NPU 接缝细化
    private suspend fun drawTopExtension(
        context: Context,
        canvas: Canvas,
        scaled: Bitmap,
        src: Bitmap,
        targetW: Int,
        extendH: Int,
        blurRadius: Int,
        feather: Int,
        useNpu: Boolean
    ) {
        if (extendH <= 0) return

        // ===== 第一步：GPU 主体延展（永远执行，保证不出纯色块）=====
        drawStackBlurExtension(canvas, scaled, src, targetW, extendH, blurRadius, feather)

        // ===== 第二步：NPU 接缝细化（可选，失败自动降级）=====
        if (useNpu && getNpu(context).isAvailable()) {
            try {
                // 过渡区高度：取 feather 宽度与 extendH 的较小值，至少 100px
                val transitionH = minOf(extendH, feather.coerceAtLeast(100))

                // 截取原图顶部边缘条带（高度 100px 或原图高度的 1/4）
                val edgeStripH = minOf(100, src.height / 4).coerceAtLeast(8)
                val edgeStrip = Bitmap.createBitmap(src, 0, 0, src.width, edgeStripH)

                // 送入 NPU 生成接缝过渡区
                val npuTransition = getNpu(context).generateExtensionBlock(
                    context = context,
                    srcTopStrip = edgeStrip,
                    targetW = targetW,
                    extendH = transitionH,
                    featherWidth = feather
                )

                // 绘制位置：覆盖在 GPU 延展区底部与原图顶部重叠处（接缝位置）
                // 这样一半覆盖延展区、一半覆盖原图顶部，实现自然融合
                val drawY = (extendH - transitionH / 2f).coerceAtLeast(0f)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
                }
                canvas.drawBitmap(npuTransition, 0f, drawY, paint)

                npuTransition.recycle()
                edgeStrip.recycle()
            } catch (e: Exception) {
                // NPU 细化失败不影响 GPU 主体效果，仅打印日志降级
                e.printStackTrace()
            }
        }
    }

    // 原 WallpaperProcessor 的 drawStackBlurExtension 逻辑，完整保留（不变）
    private fun drawStackBlurExtension(
        canvas: Canvas,
        scaled: Bitmap,
        src: Bitmap,
        targetW: Int,
        extendH: Int,
        blurRadius: Int,
        feather: Int
    ) {
        val stripH = max(8, scaled.height / 6)
        val topStrip = Bitmap.createBitmap(scaled, 0, 0, scaled.width, stripH)
        val rotated = Bitmap.createBitmap(
            topStrip, 0, 0, topStrip.width, topStrip.height,
            Matrix().apply { setRotate(180f) }, true
        )
        topStrip.recycle()
        val stretched = Bitmap.createScaledBitmap(rotated, targetW, extendH, true)
        rotated.recycle()
        val blurred = NPUImageProcessingUtils.stackBlur(stretched, blurRadius.coerceIn(0, 80))
        if (blurred !== stretched) stretched.recycle()

        val topAvg = NPUImageProcessingUtils.sampleTopEdgeColor(src, ratio = 0.12f)
        val tone = NPUImageProcessingUtils.lighten(topAvg, factor = 0.2f)
        val tonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(20, Color.red(tone), Color.green(tone), Color.blue(tone))
        }
        canvas.drawRect(0f, 0f, targetW.toFloat(), extendH.toFloat(), tonePaint)

        val effectiveFeather = feather.coerceAtLeast(50).coerceAtMost(200)
        val overlayBottom = extendH + effectiveFeather
        val overlayBmp = Bitmap.createScaledBitmap(blurred, targetW, overlayBottom, true)
        if (overlayBmp !== blurred) blurred.recycle()

        canvas.drawBitmap(
            overlayBmp, 0f, 0f,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )

        val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            shader = LinearGradient(
                0f, extendH.toFloat(),
                0f, overlayBottom.toFloat(),
                Color.TRANSPARENT, Color.BLACK,
                Shader.TileMode.CLAMP
            )
        }
        val layerId = canvas.saveLayer(
            0f, extendH.toFloat(),
            targetW.toFloat(), overlayBottom.toFloat(), null
        )
        canvas.drawRect(
            0f, extendH.toFloat(),
            targetW.toFloat(), overlayBottom.toFloat(),
            fadePaint
        )
        canvas.restoreToCount(layerId)
        if (overlayBmp !== scaled) overlayBmp.recycle()
    }

    fun release() {
        npuEngine?.release()
        npuEngine = null
    }
}
