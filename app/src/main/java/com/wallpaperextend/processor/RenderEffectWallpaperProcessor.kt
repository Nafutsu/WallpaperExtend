package com.wallpaperextend.processor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.S)
object RenderEffectWallpaperProcessor {
    private const val TAG = "RenderEffectWP"

    fun process(
        context: Context,
        src: Bitmap,
        targetW: Int,
        targetH: Int,
        config: WallpaperConfig
    ): Bitmap {
        val result = blurBitmapWithRenderEffect(src, config.blurRadius)
        
        val finalBmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(finalBmp)
        
        // 1. 画模糊底图
        canvas.drawBitmap(result, 0f, 0f, null)
        
        // 2. 采样顶部颜色做叠加
        val topColor = sampleTopEdgeColor(src)
        val lightColor = lighten(topColor, 0.5f)
        val overlay = Paint().apply {
            color = Color.argb((config.overlayStrength * 255).toInt(), Color.red(lightColor), Color.green(lightColor), Color.blue(lightColor))
        }
        canvas.drawRect(0f, 0f, targetW.toFloat(), targetH.toFloat(), overlay)
        
        // 3. Smoothstep 渐变融合原图
        val scaledH = (src.height * (targetW.toFloat() / src.width)).toInt().coerceAtLeast(1)
        val scaledSrc = Bitmap.createScaledBitmap(src, targetW, scaledH, true)
        val srcDrawY = (targetH - scaledH).toFloat()
        
        val feather = config.featherWidth.coerceAtMost(targetH / 2)
        if (feather > 0) {
            val fadePaint = Paint().apply {
                shader = android.graphics.LinearGradient(
                    0f, (srcDrawY + scaledH - feather).toFloat(),
                    0f, (srcDrawY + scaledH).toFloat(),
                    Color.argb(0, 255, 255, 255),
                    Color.argb(255, 255, 255, 255),
                    Shader.TileMode.CLAMP
                )
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            }
            canvas.drawRect(
                0f, (srcDrawY + scaledH - feather).toFloat(),
                targetW.toFloat(), (srcDrawY + scaledH).toFloat(),
                fadePaint
            )
        }
        canvas.drawBitmap(scaledSrc, 0f, srcDrawY, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        scaledSrc.recycle()
        result.recycle()
        
        return finalBmp
    }

    private fun blurBitmapWithRenderEffect(src: Bitmap, radius: Float): Bitmap {
        val w = src.width
        val h = src.height
        val hwBuffer = HardwareBuffer.create(w, h, HardwareBuffer.RGBA_8888, 1)
        val imageReader = ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 1)
        
        val renderer = android.graphics.HardwareRenderer()
        renderer.setSurface(imageReader.surface)
        renderer.setLightSourceGeometry(w / 2f, 0f, 0f, 0f)
        
        val renderNode = android.graphics.RenderNode("blurNode")
        renderNode.setPosition(0, 0, w, h)
        val canvas = renderNode.beginRecording()
        canvas.drawBitmap(src, 0f, 0f, null)
        renderNode.endRecording()
        
        renderNode.setRenderEffect(
            RenderEffect.createBlurEffect(radius.coerceIn(1f, 25f), radius.coerceIn(1f, 25f), Shader.TileMode.MIRROR)
        )
        
        renderer.setContentRoot(renderNode)
        renderer.render()
        
        val image = imageReader.acquireLatestImage()
        val planes = image.planes
        val buffer = planes[0].buffer
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buffer)
        image.close()
        
        hwBuffer.close()
        renderer.destroy()
        imageReader.close()
        
        return bmp
    }

    private fun sampleTopEdgeColor(src: Bitmap): Int {
        val h = maxOf(1, (src.height * 0.1).toInt())
        var r = 0L; var g = 0L; var b = 0L; var count = 0L
        val stepX = maxOf(1, src.width / 48)
        val stepY = maxOf(1, h / 4)
        for (y in 0 until h step stepY) {
            for (x in 0 until src.width step stepX) {
                val p = src.getPixel(x, y)
                r += Color.red(p); g += Color.green(p); b += Color.blue(p)
                count++
            }
        }
        return if (count == 0L) Color.BLACK else Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }

    private fun lighten(c: Int, factor: Float): Int {
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
