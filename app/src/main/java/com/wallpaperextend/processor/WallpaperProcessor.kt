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
 * 壁纸处理统一入口。
 * - process 为 suspend 函数，在 Dispatchers.Default 中执行，不阻塞 UI。
 * - useNpu = true：延展区由 NpuExtendEngine.generateExtensionBlock 生成（AI）；
 *   useNpu = false：延展区由 stackBlur 模糊拉伸生成（CPU 兜底）。
 * - extendRatio 作为"最大延展比例"，实际延展高度由内部动态计算。
 */
object WallpaperProcessor {

    enum class Mode { LIGHT, DARK }

    data class Config(
        val blurRadius: Int = 32,
        val extendRatio: Float = 0.37f,   // 最大延展比例
        val featherWidth: Int = 150,
        val topOnly: Boolean = true,
        val mode: Mode = Mode.LIGHT
    )

    // 持有 NPU 引擎（懒加载，进程级单例）
    private var npuEngine: NpuExtendEngine? = null
    private fun getNpu(context: Context): NpuExtendEngine {
        if (npuEngine == null) npuEngine = NpuExtendEngine(context.applicationContext)
        return npuEngine!!
    }

    /**
     * ★ 修改后的入口：suspend + context + useNpu
     */
    suspend fun process(
        context: Context,
        src: Bitmap,
        targetW: Int,
        targetH: Int,
        config: Config = Config(),
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

        // 实际延展高度：若 topOnly，则延展区填满"原图上方剩余空间"，上限受 extendRatio 约束
        val maxExtend = (targetH * config.extendRatio.coerceIn(0.05f, 0.6f)).toInt()
        val extendH = if (config.topOnly) {
            (targetH - scaledH).coerceAtLeast(0).coerceAtMost(maxExtend)
        } else {
            maxExtend
        }

        val srcDrawY = (targetH - scaledH).toFloat()

        // ★ 先画原图
        canvas.drawBitmap(
            scaled,
            0f,
            srcDrawY,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )

        // ★ 延展区（NPU 或 stackBlur）
        if (extendH > 0) {
            drawTopExtension(
                context = context,
                canvas = canvas,
                scaled = scaled,
                src = src,
                targetW = targetW,
                extendH = extendH,
                blurRadius = config.blurRadius,
                feather = config.featherWidth,
                useNpu = useNpu
            )
        }

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

    // ★ useNpu 分支：true → NpuExtendEngine.generateExtensionBlock；false → stackBlur
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

        if (useNpu && getNpu(context).isAvailable()) {
            // ===== NPU：生成纯延展块，直接绘制 =====
            val stripH = max(8, scaled.height / 6)
            val topStrip = Bitmap.createBitmap(scaled, 0, 0, scaled.width, stripH)
            try {
                val block = getNpu(context).generateExtensionBlock(
                    context = context,
                    srcTopStrip = topStrip,
                    targetW = targetW,
                    extendH = extendH,
                    featherWidth = feather
                )
                canvas.drawBitmap(block, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                block.recycle()
            } catch (e: Exception) {
                // NPU 失败降级到 stackBlur
                drawStackBlurExtension(canvas, scaled, src, targetW, extendH, blurRadius, feather)
            } finally {
                topStrip.recycle()
            }
        } else {
            // ===== CPU 兜底：stackBlur 模糊拉伸（原逻辑） =====
            drawStackBlurExtension(canvas, scaled, src, targetW, extendH, blurRadius, feather)
        }
    }

    // 原 WallpaperProcessor 的 drawTopExtension 逻辑，完整保留
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

        // 极淡色调底色
        val topAvg = NPUImageProcessingUtils.sampleTopEdgeColor(src, ratio = 0.12f)
        val tone = NPUImageProcessingUtils.lighten(topAvg, factor = 0.2f)
        val tonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(20, Color.red(tone), Color.green(tone), Color.blue(tone))
        }
        canvas.drawRect(0f, 0f, targetW.toFloat(), extendH.toFloat(), tonePaint)

        // 模糊层向下延伸覆盖原图顶部
        val effectiveFeather = feather.coerceAtLeast(50).coerceAtMost(200)
        val overlayBottom = extendH + effectiveFeather
        val overlayBmp = Bitmap.createScaledBitmap(blurred, targetW, overlayBottom, true)
        if (overlayBmp !== blurred) blurred.recycle()

        canvas.drawBitmap(
            overlayBmp,
            0f, 0f,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )

        // 渐变淡出
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
