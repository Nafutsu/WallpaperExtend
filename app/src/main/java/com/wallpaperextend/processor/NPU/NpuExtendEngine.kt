package com.wallpaperextend.processor.NPU

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.wallpaperextend.processor.ImageProcessingUtils
import com.wallpaperextend.processor.WallpaperConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer

/**
 * NPU LaMa 神经网络延展引擎
 * 实现 ExtendStrategy 接口（同包下无需 import）
 */
class NpuExtendEngine(
    private val context: Context
) : ExtendStrategy {

    companion object {
        private const val TAG = "NpuExtendEngine"
        private const val MODEL_ASSET = "models/image_extension_lama.onnx"
        private const val MODEL_FILE = "image_extension_lama.onnx"
        private const val INPUT_SIZE = 512
    }

    private var session: OrtSession? = null
    private var env: OrtEnvironment? = null
    private var isInitialized = false

    override fun isAvailable(): Boolean {
        return try {
            context.assets.open(MODEL_ASSET).close()
            true
        } catch (e: Exception) {
            Log.d(TAG, "Model not found: $MODEL_ASSET")
            false
        }
    }

    override fun name(): String = "NPU-LaMa"

    @Synchronized
    fun loadModels() {
        if (isInitialized) return
        try {
            env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                try {
                    addNnapi()
                } catch (e: Throwable) {
                    Log.d(TAG, "NNAPI not available, fallback to CPU: ${e.message}")
                }
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val modelFile = File(context.filesDir, MODEL_FILE)
            if (!modelFile.exists() || modelFile.length() == 0L) {
                Log.d(TAG, "Copying model from assets to internal storage...")
                context.assets.open(MODEL_ASSET).use { input ->
                    modelFile.outputStream().use { output ->
                        val buf = ByteArray(8 * 1024)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            output.write(buf, 0, n)
                        }
                    }
                }
                Log.d(TAG, "Model copied: ${modelFile.absolutePath} (${modelFile.length()} bytes)")
            }
            session = env!!.createSession(modelFile.absolutePath, opts)
            isInitialized = true
            Log.d(TAG, "✅ Model loaded successfully (NNAPI auto)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            isInitialized = false
        }
    }

    override suspend fun extend(
        context: Context,
        src: Bitmap,
        targetW: Int,
        targetH: Int,
        config: WallpaperConfig
    ): Bitmap = withContext(Dispatchers.Default) {
        kotlinx.coroutines.withContext(NonCancellable) {
            if (!isInitialized) loadModels()
            val ortSession = session
                ?: throw IllegalStateException("NPU model not loaded (isAvailable=${isAvailable()})")

            val extendH = (targetH * config.extendRatio.coerceIn(0.05f, 0.6f))
                .toInt()
                .coerceAtLeast(8)

            // 原图适配目标宽度
            val scaledH = (targetW.toFloat() / src.width * src.height)
                .toInt()
                .coerceAtLeast(1)
            val scaledSrc = Bitmap.createScaledBitmap(src, targetW, scaledH, true)

            // ---- 构造 512x512 输入：原图贴底部，顶部为待延展区 ----
            val inputBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
            val inputCanvas = Canvas(inputBitmap).apply { drawColor(Color.BLACK) }
            val srcBottomH = (scaledH * INPUT_SIZE / targetW).coerceAtLeast(1)
            val srcTop = INPUT_SIZE - srcBottomH
            inputCanvas.drawBitmap(
                scaledSrc,
                Rect(0, 0, scaledSrc.width, scaledSrc.height),
                Rect(0, srcTop, INPUT_SIZE, INPUT_SIZE),
                null
            )

            // ---- mask：顶部 extendH 区域 = 1（待生成），其余 = 0 ----
            val maskBottomH = (extendH * INPUT_SIZE / targetH).coerceAtLeast(1)
            val maskBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ALPHA_8)
            Canvas(maskBitmap).apply {
                drawColor(Color.BLACK)
                drawRect(
                    0f, 0f, INPUT_SIZE.toFloat(), maskBottomH.toFloat(),
                    Paint().apply { color = Color.WHITE }
                )
            }

            // ---- 推理 ----
            val imageTensor = bitmapToTensor(inputBitmap)
            val maskTensor = maskToTensor(maskBitmap)
            val inputs = mapOf("image" to imageTensor, "mask" to maskTensor)
            val outputs = ortSession.run(inputs)
            val resultTensor = outputs[0] as OnnxTensor
            val generated512 = floatBufferToBitmap(resultTensor.floatBuffer, INPUT_SIZE, INPUT_SIZE)

            // 关闭 tensor
            imageTensor.close()
            maskTensor.close()
            resultTensor.close()
            outputs.close()
            inputBitmap.recycle()
            maskBitmap.recycle()

            // ★ 裁剪并缩放延展区（修复：除法重载歧义）
            val extendAspect = targetW.toFloat() / extendH.toFloat()
            val aiCropH = (INPUT_SIZE.toFloat() / extendAspect).toInt().coerceAtLeast(1)
            val croppedAi = Bitmap.createBitmap(generated512, 0, 0, INPUT_SIZE, aiCropH)
            generated512.recycle()

            val scaledGenerated = Bitmap.createScaledBitmap(croppedAi, targetW, extendH, true)
            croppedAi.recycle()

            // ★ 颜色匹配（采样原图顶部颜色）
            val topAvg = ImageProcessingUtils.sampleTopAverageColor(src, 0.12f)
            val colorMatched = ImageProcessingUtils.matchColorToTarget(scaledGenerated, topAvg)
            scaledGenerated.recycle()

            // ★ 羽化
            val feathered = ImageProcessingUtils.applyFeather(colorMatched, config.featherWidth)
            colorMatched.recycle()

            // ★ 拼接（修复：减法重载歧义）
            val finalBitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(finalBitmap)
            canvas.drawBitmap(feathered, 0f, 0f, null)
            feathered.recycle()

            // 原图放在下方
            val bottomH = (targetH - extendH).coerceAtLeast(0)
            if (bottomH > 0) {
                val bottomSrc = Bitmap.createScaledBitmap(scaledSrc, targetW, bottomH, true)
                canvas.drawBitmap(bottomSrc, 0f, extendH.toFloat(), null)
                bottomSrc.recycle()
            }
            scaledSrc.recycle()

            return@withContext finalBitmap
        }
    }

    // ===== Tensor 转换工具方法 =====
    private fun bitmapToTensor(bitmap: Bitmap): OnnxTensor {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val buffer = FloatBuffer.allocate(1 * 3 * h * w)
        for (pixel in pixels) {
            buffer.put((Color.red(pixel) / 127.5f - 1.0f).coerceIn(-1f, 1f))
            buffer.put((Color.green(pixel) / 127.5f - 1.0f).coerceIn(-1f, 1f))
            buffer.put((Color.blue(pixel) / 127.5f - 1.0f).coerceIn(-1f, 1f))
        }
        buffer.rewind()
        return OnnxTensor.createTensor(env!!, buffer, longArrayOf(1, 3, h.toLong(), w.toLong()))
    }

    private fun maskToTensor(bitmap: Bitmap): OnnxTensor {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val buffer = FloatBuffer.allocate(1 * 1 * h * w)
        for (pixel in pixels) {
            buffer.put(if (Color.alpha(pixel) > 127) 1.0f else 0.0f)
        }
        buffer.rewind()
        return OnnxTensor.createTensor(env!!, buffer, longArrayOf(1, 1, h.toLong(), w.toLong()))
    }

    private fun floatBufferToBitmap(buffer: FloatBuffer, w: Int, h: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        val planeSize = w * h
        buffer.rewind()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val r = ((buffer.get(0 * planeSize + i) + 1f) * 127.5f).toInt().coerceIn(0, 255)
                val g = ((buffer.get(1 * planeSize + i) + 1f) * 127.5f).toInt().coerceIn(0, 255)
                val b = ((buffer.get(2 * planeSize + i) + 1f) * 127.5f).toInt().coerceIn(0, 255)
                pixels[i] = Color.rgb(r, g, b)
            }
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }

    fun release() {
        session?.close()
        env?.close()
        isInitialized = false
    }
}
