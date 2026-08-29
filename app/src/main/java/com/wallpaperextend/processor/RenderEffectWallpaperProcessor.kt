package com.wallpaperextend.processor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.HardwareRenderer
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import androidx.annotation.RequiresApi
import com.wallpaperextend.processor.NPU.ExtendStrategy
import com.wallpaperextend.processor.utils.ImageProcessingUtils
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * GPU 降级方案：用 RenderEffect 做模糊+拉伸+羽化的延展（minSdk 31 必可用）。
 * 实现 ExtendStrategy 接口，供 WallpaperExtendEngine 统一调度。
 */
@RequiresApi(Build.VERSION_CODES.S)
object RenderEffectWallpaperProcessor : ExtendStrategy {

    override fun isAvailable(): Boolean = true

    override fun name(): String = "RenderEffect-GPU"

    override suspend fun extend(
        context: Context,
        src: Bitmap,
        targetW: Int,
        targetH: Int,
        config: WallpaperConfig
    ): Bitmap {
        return processInternal(context, src, targetW, targetH, config)
    }

    fun process(
        context: Context,
        src: Bitmap,
        targetW: Int,
        targetH: Int,
        config: WallpaperConfig
    ): Bitmap {
        return processInternal(context, src, targetW, targetH, config)
    }

    private fun processInternal(
        context: Context,
        src: Bitmap,
        targetW: Int,
        targetH: Int,
        config: WallpaperConfig
    ): Bitmap {
        val extendH = (targetH * config.extendRatio.coerceIn(0.05f, 0.6f)).toInt()

        // 1. 采样顶部颜色（用于匹配）
        val topAvg = ImageProcessingUtils.sampleTopAverageColor(src, 0.12f)

        // 2. 生成延展区（模糊拉伸）
        val edgeH = max(50, (src.height * 0.1).toInt())
        val edgeSrc = Bitmap.createBitmap(src, 0, 0, src.width, min(edgeH, src.height))
        val stretched = Bitmap.createScaledBitmap(edgeSrc, targetW, extendH, true)
        val blurred = blurWithRenderEffect(stretched, config.blurRadius)
        stretched.recycle()
        edgeSrc.recycle()

        // 3. ★ 颜色匹配（使延展区颜色接近原图顶部）
        val colorMatched = ImageProcessingUtils.matchColorToTarget(blurred, topAvg)
        blurred.recycle()

        // 4. ★ 羽化（底部渐变透明）
        val feathered = ImageProcessingUtils.applyFeather(colorMatched, config.featherWidth)
        colorMatched.recycle()

        // 5. 输出画布
        val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)

        // 绘制延展区
        canvas.drawBitmap(feathered, 0f, 0f, null)
        feathered.recycle()

        // 6. 绘制原图（下方）
        val srcScaledH = (targetW.toFloat() / src.width * src.height).toInt()
        canvas.drawBitmap(
            src, null,
            RectF(0f, extendH.toFloat(), targetW.toFloat(), (extendH + srcScaledH).toFloat()),
            null
        )

        return out
    }

    // 系统级 RenderEffect 离屏模糊
    private fun blurWithRenderEffect(src: Bitmap, radius: Float): Bitmap {
        val r = radius.coerceIn(1f, 25f)
        val width = src.width
        val height = src.height

        val imageReader = ImageReader.newInstance(
            width, height,
            PixelFormat.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )

        val renderNode = RenderNode("WallpaperBlur")
        val renderer = HardwareRenderer()
        renderer.setSurface(imageReader.surface)
        renderer.setContentRoot(renderNode)
        renderNode.setPosition(0, 0, width, height)

        renderNode.setRenderEffect(
            RenderEffect.createBlurEffect(r, r, Shader.TileMode.MIRROR)
        )

        val renderCanvas = renderNode.beginRecording()
        renderCanvas.drawBitmap(src, 0f, 0f, null)
        renderNode.endRecording()

        renderer.createRenderRequest()
            .setWaitForPresent(true)
            .syncAndDraw()

        val image = imageReader.acquireNextImage()
            ?: throw RuntimeException("RenderEffect: no image")
        val hwBuffer = image.hardwareBuffer
            ?: throw RuntimeException("RenderEffect: no hardware buffer")

        val hwBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, null)
            ?: throw RuntimeException("RenderEffect: bitmap creation failed")

        val result = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)

        hwBuffer.close()
        image.close()
        imageReader.close()
        renderer.destroy()
        renderNode.discardDisplayList()

        return result ?: hwBitmap
    }

    private fun calculateLuminance(color: Int): Float {
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    private fun sampleTopEdgeColor(src: Bitmap, ratio: Float = 0.1f): Int {
        val h = max(1, (src.height * ratio).toInt())
        var r = 0L; var g = 0L; var b = 0L; var count = 0L
        val stepX = max(1, src.width / 48)
        val stepY = max(1, h / 4)
        for (y in 0 until h step stepY) {
            for (x in 0 until src.width step stepX) {
                val p = src.getPixel(x, y)
                r += Color.red(p); g += Color.green(p); b += Color.blue(p)
                count++
            }
        }
        return if (count == 0L) Color.BLACK else Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }

    private fun lighten(c: Int, factor: Float = 0.5f): Int {
        val f = factor.coerceIn(-1f, 1f)
        return if (f >= 0) {
            Color.rgb(
                (Color.red(c) + (255 - Color.red(c)) * f).toInt().coerceIn(0, 255),
                (Color.green(c) + (255 - Color.green(c)) * f).toInt().coerceIn(0, 255),
                (Color.blue(c) + (255 - Color.blue(c)) * f).toInt().coerceIn(0, 255)
            )
        } else {
            val df = -f
            Color.rgb(
                (Color.red(c) * (1 - df)).toInt().coerceIn(0, 255),
                (Color.green(c) * (1 - df)).toInt().coerceIn(0, 255),
                (Color.blue(c) * (1 - df)).toInt().coerceIn(0, 255)
            )
        }
    }
}
