package com.wallpaperextend.processor.NPU

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import com.wallpaperextend.WallpaperConfig
import com.wallpaperextend.processor.ExtendStrategy
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.math.min

class NpuExtendEngine : ExtendStrategy {

    private val TAG = "NpuExtendEngine"
    private val MODEL_PATH = "models/image_extension_lama.onnx"
    private val INPUT_SIZE = 512

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var isInitialized = false

    override fun create(context: Context) {
        if (isInitialized) return
        try {
            env = OrtEnvironment.getEnvironment()
            val modelFile = copyModelToInternalStorage(context)
            val opts = OrtSession.SessionOptions()
            try {
                opts.addNnapi()
                Log.d(TAG, "NNAPI enabled")
            } catch (e: Exception) {
                Log.w(TAG, "NNAPI not available, falling back to CPU: ${e.message}")
            }
            session = env!!.createSession(modelFile.absolutePath, opts)
            isInitialized = true
            Log.d(TAG, "✅ Model loaded successfully from ${modelFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            throw RuntimeException("NPU engine init failed: ${e.message}", e)
        }
    }

    private fun copyModelToInternalStorage(context: Context): File {
        val modelFile = File(context.filesDir, "image_extension_lama.onnx")
        if (!modelFile.exists()) {
            Log.d(TAG, "Copying model from assets to ${modelFile.absolutePath}")
            context.assets.open(MODEL_PATH).use { input ->
                modelFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "Model copied, size: ${modelFile.length()} bytes")
        }
        return modelFile
    }

    override fun extend(
        src: Bitmap,
        targetW: Int,
        targetH: Int,
        config: WallpaperConfig
    ): Bitmap {
        if (!isInitialized || session == null) {
            throw IllegalStateException("Engine not initialized, call create() first")
        }

        val extendH = targetH - src.height
        if (extendH <= 0) return src

        // 1. 准备输入：原图放底部，顶部留空（待生成区域）
        val combined = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(combined)
        val scale = INPUT_SIZE.toFloat() / targetW.toFloat()
        val scaledSrcH = (src.height * scale).toInt()
        val scaledSrcW = INPUT_SIZE
        val scaledSrc = Bitmap.createScaledBitmap(src, scaledSrcW, scaledSrcH, true)
        val topPadding = INPUT_SIZE - scaledSrcH
        canvas.drawBitmap(scaledSrc, 0f, topPadding.toFloat(), null)
        if (combined != scaledSrc) scaledSrc.recycle()

        // 2. 转 float 输入 [1,3,512,512] NCHW
        val inputBuffer = bitmapToFloatBuffer(combined)
        if (combined != src) combined.recycle()

        // 3. 准备 mask：顶部待生成区域为 1，其余为 0
        val maskBuffer = FloatBuffer.allocate(1 * 1 * INPUT_SIZE * INPUT_SIZE)
        for (y in 0 until topPadding) {
            for (x in 0 until INPUT_SIZE) {
                maskBuffer.put(1f)
            }
        }
        for (y in topPadding until INPUT_SIZE) {
            for (x in 0 until INPUT_SIZE) {
                maskBuffer.put(0f)
            }
        }
        maskBuffer.rewind()

        // 4. 推理
        val inputShape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        val maskShape = longArrayOf(1, 1, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        val inputTensor = OnnxTensor.createTensor(env!!, inputBuffer, inputShape)
        val maskTensor = OnnxTensor.createTensor(env!!, maskBuffer, maskShape)

        val inputs = mapOf(
            "image" to inputTensor,
            "mask" to maskTensor
        )

        val results = session!!.run(inputs)
        val outputTensor = results[0] as OnnxTensor
        val outputBuffer = outputTensor.floatBuffer

        inputTensor.close()
        maskTensor.close()
        outputTensor.close()
        results.close()

        // 5. 输出 → Bitmap（完整 512×512 修复图）
        val generated512 = floatBufferToBitmap(outputBuffer, INPUT_SIZE, INPUT_SIZE)

        // 6. 缩放到目标尺寸作为延展背景
        val scaledGenerated = Bitmap.createScaledBitmap(generated512, targetW, targetH, true)
        if (scaledGenerated != generated512) generated512.recycle()

        // 7. 拼接：延展背景 + 原图在底部
        val final = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val finalCanvas = Canvas(final)
        finalCanvas.drawBitmap(scaledGenerated, 0f, 0f, null)
        if (final != scaledGenerated) scaledGenerated.recycle()

        val srcScaled = Bitmap.createScaledBitmap(src, targetW, src.height, true)
        finalCanvas.drawBitmap(srcScaled, 0f, extendH.toFloat(), null)
        if (final != srcScaled) srcScaled.recycle()

        return final
    }

    private fun bitmapToFloatBuffer(bitmap: Bitmap): FloatBuffer {
        val buffer = FloatBuffer.allocate(1 * 3 * INPUT_SIZE * INPUT_SIZE)
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (c in 0 until 3) {
            for (i in pixels.indices) {
                val pixel = pixels[i]
                val value = when (c) {
                    0 -> (pixel shr 16 and 0xFF) / 255.0f
                    1 -> (pixel shr 8 and 0xFF) / 255.0f
                    else -> (pixel and 0xFF) / 255.0f
                }
                buffer.put(value)
            }
        }
        buffer.rewind()
        return buffer
    }

    private fun floatBufferToBitmap(buffer: FloatBuffer, w: Int, h: Int): Bitmap {
        Log.d(TAG, "floatBufferToBitmap: capacity=${buffer.capacity()}, w=$w, h=$h, expected=${1*3*h*w}")
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        val localBuffer = buffer.asReadOnlyBuffer()
        localBuffer.rewind()
        for (i in pixels.indices) {
            val r = ((localBuffer.get() * 255f).toInt()).coerceIn(0, 255)
            val g = ((localBuffer.get() * 255f).toInt()).coerceIn(0, 255)
            val b = ((localBuffer.get() * 255f).toInt()).coerceIn(0, 255)
            pixels[i] = Color.rgb(r, g, b)
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }

    override fun release() {
        try {
            session?.close()
            env?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Release failed", e)
        }
        session = null
        env = null
        isInitialized = false
    }
}
