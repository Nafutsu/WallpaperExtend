package com.wallpaperextend.processor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import androidx.annotation.RequiresApi
import com.wallpaperextend.processor.NPU.ExtendStrategy
import com.wallpaperextend.processor.NPU.NPUImageProcessingUtils
import kotlin.math.max
import kotlin.math.min

/**
 * GPU 降级方案：用 RenderEffect 做模糊+拉伸，再走公共的颜色匹配+羽化+拼接。
 * 实现 ExtendStrategy 接口，与 NpuExtendEngine 可互换。
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
        config: com.wallpaperextend.processor.WallpaperConfig
    ): Bitmap = processInternal(src, targetW, targetH, config)

    fun process(
        src: Bitmap,
        targetW: Int,
        targetH: Int,
        config: com.wallpaperextend.processor.WallpaperConfig
    ): Bitmap = processInternal(src, targetW, targetH, config)

    private fun processInternal(
        src: Bitmap,
        targetW: Int,
        targetH: Int,
        config: com.wallpaperextend.processor.WallpaperConfig
    ): Bitmap {
        val extendH = (targetH * config.extendRatio.coerceIn(0.05f, 0.6f)).toInt()

        // GPU 模糊生成延展区
        val edgeH = max(50, (src.height * 0.1).toInt())
        val edgeSrc = Bitmap.createBitmap(src, 0, 0, src.width, min(edgeH, src.height))
        val stretched = Bitmap.createScaledBitmap(edgeSrc, targetW, extendH, true)
        val blurred = blurWithRenderEffect(stretched, config.blurRadius)
        stretched.recycle()
        edgeSrc.recycle()

        // ★ 公共方法：颜色匹配 + 羽化 + 拼接
        return NPUImageProcessingUtils.composeExtendedWallpaper(
            extendedRegion = blurred,
            src = src,
            targetW = targetW,
            targetH = targetH,
            extendH = extendH,
            featherWidth = config.featherWidth
        )
    }

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
        renderNode.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.MIRROR))
        val renderCanvas = renderNode.beginRecording()
        renderCanvas.drawBitmap(src, 0f, 0f, null)
        renderNode.endRecording()
        renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
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
}
