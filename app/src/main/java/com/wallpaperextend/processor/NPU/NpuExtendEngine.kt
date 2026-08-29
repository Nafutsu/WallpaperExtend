package com.wallpaperextend.processor.NPU

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import com.wallpaperextend.processor.WallpaperConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer

/**
 * NPU 神经网络延展引擎（方案一 + 选择1：LaMa ONNX + 端侧推理）
 *
 * 流程：原图 → 构造 image + mask 输入 → ONNX Runtime 推理 → 生成扩展区 → 拼接 → 输出
 *
 * 执行提供者：默认让 ORT 自动选择（CPU / NNAPI / QNN）。
 * 如需强制启用 NPU，可在 SessionOptions 里 addConfigEntry 或用 addNnapi。
 */
class NpuExtendEngine(
    private val context: Context
) : ExtendStrategy {

    companion object {
        private const val TAG = "NpuExtendEngine"
        private const val MODEL_PATH = "models/image_extension_lama.onnx"
        private const val MODEL_FILE_NAME = "image_extension_lama.onnx"
        private const val INPUT_SIZE = 512
    }

    private var session: OrtSession? = null
    private var env: OrtEnvironment? = null
    private var isInitialized = false

    override fun isAvailable(): Boolean {
        return try {
            context.assets.open(MODEL_PATH).close()
            true
        } catch (e: Exception) {
            Log.d(TAG, "Model not found: $MODEL_PATH")
            false
        }
    }

    override fun name(): String = "NPU-LaMa"

    fun loadModels() {
        if (isInitialized) return
        try {
            env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                // ★ 启用 NNAPI（自动选择 NPU / GPU / DSP），失败降级 CPU
                try {
                    addNnapi()
                } catch (e: Throwable) {
                    Log.d(TAG, "NNAPI not available, fallback to CPU: ${e.message}")
                }
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }

            // ★ 修复 OOM：把 assets 模型复制到内部存储，再用文件路径加载
            //   （避免在 Java 堆上一次性分配 ~198MB 的 byte[]）
            val modelFile = File(context.filesDir, MODEL_FILE_NAME)
            if (!modelFile.exists()) {
                Log.d(TAG, "Copying model from assets to internal storage...")
                context.assets.open(MODEL_PATH).use { input ->
                    modelFile.outputStream().use { output ->
                        val buffer = ByteArray(8 * 1024) // 8KB 缓冲，不占大量内存
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                        }
                    }
                }
                Log.d(TAG, "Model copied: ${modelFile.absolutePath} (${modelFile.length()} bytes)")
            }

            // ★ 从文件路径加载，不在 Java 堆分配 198MB
            session = env!!.createSession(modelFile.absolutePath, opts)
            isInitialized = true
            Log.d(TAG, "✅ Model loaded successfully from: ${modelFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
        }
    }

    override suspend fun extend(
        context: Context,
        src: Bitmap,
        targetW: Int,
        targetH: Int,
        config: WallpaperConfig
    ): Bitmap = withContext(Dispatchers.Default) {

        if (!isInitialized) loadModels()
        val ortSession = session ?: throw IllegalStateException("NPU model not loaded")

        val extendH = (targetH * config.extendRatio.coerceIn(0.05f, 0.6f)).toInt()

        val scaledH = (targetW.toFloat() / src.width * src.height).toInt().coerceAtLeast(1)
        val scaledSrc = Bitmap.createScaledBitmap(src, targetW, scaledH, true)

        // 构造 512x512 模型输入（原图放底部，顶部留黑待生成）
        val inputBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(inputBitmap).apply {
            drawColor(Color.BLACK)
            val srcRect = Rect(0, 0, scaledSrc.width, scaledSrc.height)
            val dstTop = INPUT_SIZE - (scaledH * INPUT_SIZE / targetH).coerceAtLeast(1)
            val dstRect = Rect(0, dstTop, INPUT_SIZE, INPUT_SIZE)
            drawBitmap(scaledSrc, srcRect, dstRect, null)
        }

        // 构造 mask（顶部区域标记为待生成）
        val maskH = (extendH * INPUT_SIZE / targetH).coerceAtLeast(1)
        val maskBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ALPHA_8)
        Canvas(maskBitmap).apply {
            drawColor(Color.BLACK)
            val paint = android.graphics.Paint().apply { color = Color.WHITE }
            drawRect(0f, 0f, INPUT_SIZE.toFloat(), maskH.toFloat(), paint)
        }

        // Bitmap → Tensor
        val imageTensor = bitmapToTensor(inputBitmap)
        val maskTensor = maskToTensor(maskBitmap)

        // ★ NPU 推理
        val inputs = mapOf("image" to imageTensor, "mask" to maskTensor)
        val outputs = ortSession.run(inputs)
        val resultTensor = outputs[0] as OnnxTensor
        val resultBuffer = resultTensor.floatBuffer
        val generated512 = floatBufferToBitmap(resultBuffer, INPUT_SIZE, INPUT_SIZE)

        // 拼接：生成的顶部延展区 + 原图
        val finalBitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        Canvas(finalBitmap).apply {
            val topH = (extendH * INPUT_SIZE / targetH).coerceAtLeast(1)
            val topRegion = Bitmap.createBitmap(generated512, 0, 0, INPUT_SIZE, topH)
            val topScaled = Bitmap.createScaledBitmap(topRegion, targetW, extendH, true)
            drawBitmap(topScaled, 0f, 0f, null)

            val bottomH = targetH - extendH
            if (bottomH > 0) {
                val bottomScaled = Bitmap.createScaledBitmap(scaledSrc, targetW, bottomH, true)
                drawBitmap(bottomScaled, 0f, extendH.toFloat(), null)
                if (bottomScaled !== scaledSrc) bottomScaled.recycle()
            }
            topScaled.recycle()
            topRegion.recycle()
        }

        inputBitmap.recycle()
        maskBitmap.recycle()
        generated512.recycle()
        scaledSrc.recycle()

        finalBitmap
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

    private fun floatBufferToBitmap(
        buffer: FloatBuffer,
        w: Int,
        h: Int
    ): Bitmap {
        // buffer 布局：[1, 3, H, W]，R/G/B 三个平面依次排列
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        val planeSize = w * h
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
