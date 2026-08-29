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
import com.wallpaperextend.processor.WallpaperConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer

/**
 * NPU LaMa 神经网络延展引擎。
 * 实现 ExtendStrategy 接口；同时对外提供 generateExtensionBlock()，
 * 供 WallpaperProcessor 在 useNpu=true 时获取"纯延展区块"。
 */
class NpuExtendEngine(
    private val context: Context
) : ExtendStrategy {

    companion object {
        private const val TAG = "NpuExtendEngine"
        private const val MODEL_ASSET = "models/image_extension_lama.onnx"
        private const val MODEL_FILE = "image_extension_lama.onnx"
        const val INPUT_SIZE = 512   // ← 公开，供外部计算裁剪
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

    // ==================================================================
    // ExtendStrategy 接口：返回完整拼接好的最终壁纸
    // ==================================================================
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
                .toInt().coerceAtLeast(8)

            // 原图适配目标宽度（用于拼接）
            val scaledH = (targetW.toFloat() / src.width * src.height).toInt().coerceAtLeast(1)
            val scaledSrc = Bitmap.createScaledBitmap(src, targetW, scaledH, true)

            // 推理：得到 512x512 的生成结果
            val generated512 = runInference(ortSession, src, targetW, targetH, extendH)

            // 裁剪并缩放为延展区
            val extendAspect = targetW.toFloat() / extendH.toFloat()
            val aiCropH = (INPUT_SIZE.toFloat() / extendAspect).toInt().coerceAtLeast(1)
            val croppedAi = Bitmap.createBitmap(generated512, 0, 0, INPUT_SIZE, aiCropH)
            generated512.recycle()
            val scaledGenerated = Bitmap.createScaledBitmap(croppedAi, targetW, extendH, true)
            croppedAi.recycle()

            // ★ 公共方法：颜色匹配 + 羽化 + 拼接
            val finalBitmap = NPUImageProcessingUtils.composeExtendedWallpaper(
                extendedRegion = scaledGenerated,
                src = src,
                targetW = targetW,
                targetH = targetH,
                extendH = extendH,
                featherWidth = config.featherWidth
            )
            scaledSrc.recycle()
            return@withContext finalBitmap
        }
    }

    // ==================================================================
    // ★ 新增：生成"纯延展区域"（无原图拼接），供 WallpaperProcessor 使用
    // srcTopStrip = 原图顶部条带（作为推理条件）
    // 返回 Bitmap 尺寸 = targetW x extendH，已做颜色匹配 + 羽化
    // ==================================================================
    suspend fun generateExtensionBlock(
        context: Context,
        srcTopStrip: Bitmap,
        targetW: Int,
        extendH: Int,
        featherWidth: Int = 150
    ): Bitmap = withContext(Dispatchers.Default) {
        kotlinx.coroutines.withContext(NonCancellable) {
            if (!isInitialized) loadModels()
            val ortSession = session
                ?: throw IllegalStateException("NPU model not loaded")

            val stripH = srcTopStrip.height.coerceAtLeast(1)
            // 构造 512x512 输入：把条带贴底部，顶部为待生成区
            val inputBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
            Canvas(inputBitmap).apply { drawColor(Color.BLACK) }
            val stripBottomH = (stripH * INPUT_SIZE / INPUT_SIZE).coerceAtLeast(1)
            val srcTop = INPUT_SIZE - stripBottomH
            Canvas(inputBitmap).apply {
                drawBitmap(
                    srcTopStrip,
                    Rect(0, 0, srcTopStrip.width, srcTopStrip.height),
                    Rect(0, srcTop, INPUT_SIZE, INPUT_SIZE),
                    null
                )
            }

            // mask：顶部 = 1
            val maskBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ALPHA_8)
            Canvas(maskBitmap).apply {
                drawColor(Color.BLACK)
                drawRect(
                    0f, 0f, INPUT_SIZE.toFloat(), (INPUT_SIZE - stripBottomH).toFloat(),
                    Paint().apply { color = Color.WHITE }
                )
            }

            val imageTensor = bitmapToTensor(inputBitmap)
            val maskTensor = maskToTensor(maskBitmap)
            val outputs = ortSession.run(mapOf("image" to imageTensor, "mask" to maskTensor))
            val resultTensor = outputs[0] as OnnxTensor
            val generated512 = floatBufferToBitmap(resultTensor.floatBuffer, INPUT_SIZE, INPUT_SIZE)
            imageTensor.close(); maskTensor.close(); resultTensor.close(); outputs.close()
            inputBitmap.recycle(); maskBitmap.recycle()

            // 裁剪 + 缩放成 targetW x extendH
            val extendAspect = targetW.toFloat() / extendH.toFloat()
            val aiCropH = (INPUT_SIZE.toFloat() / extendAspect).toInt().coerceAtLeast(1)
            val cropped = Bitmap.createBitmap(generated512, 0, 0, INPUT_SIZE, aiCropH)
            generated512.recycle()
            val scaled = Bitmap.createScaledBitmap(cropped, targetW, extendH, true)
            cropped.recycle()

            // 颜色匹配 + 羽化（仅延展区，不拼接原图）
            val topAvg = NPUImageProcessingUtils.sampleTopAverageColor(srcTopStrip, 0.12f)
            val matched = NPUImageProcessingUtils.matchColorToTarget(scaled, topAvg)
            scaled.recycle()
            val feathered = NPUImageProcessingUtils.applyFeather(matched, featherWidth)
            matched.recycle()
            return@withContext feathered
        }
    }

    // ==================================================================
    // 推理核心（被 extend 与 generateExtensionBlock 共用）
    // ==================================================================
    private suspend fun runInference(
        ortSession: OrtSession,
        src: Bitmap,
        targetW: Int,
        targetH: Int,
        extendH: Int
    ): Bitmap = withContext(Dispatchers.Default) {
        val scaledH = (targetW.toFloat() / src.width * src.height).toInt().coerceAtLeast(1)
        val scaledSrc = Bitmap.createScaledBitmap(src, targetW, scaledH, true)

        val inputBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(inputBitmap).apply { drawColor(Color.BLACK) }
        val srcBottomH = (scaledH * INPUT_SIZE / targetW).coerceAtLeast(1)
        val srcTop = INPUT_SIZE - srcBottomH
        Canvas(inputBitmap).apply {
            drawBitmap(
                scaledSrc,
                Rect(0, 0, scaledSrc.width, scaledSrc.height),
                Rect(0, srcTop, INPUT_SIZE, INPUT_SIZE),
                null
            )
        }

        val maskBottomH = (extendH * INPUT_SIZE / targetH).coerceAtLeast(1)
        val maskBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ALPHA_8)
        Canvas(maskBitmap).apply {
            drawColor(Color.BLACK)
            drawRect(
                0f, 0f, INPUT_SIZE.toFloat(), maskBottomH.toFloat(),
                Paint().apply { color = Color.WHITE }
            )
        }

        val imageTensor = bitmapToTensor(inputBitmap)
        val maskTensor = maskToTensor(maskBitmap)
        val outputs = ortSession.run(mapOf("image" to imageTensor, "mask" to maskTensor))
        val resultTensor = outputs[0] as OnnxTensor
        val generated512 = floatBufferToBitmap(resultTensor.floatBuffer, INPUT_SIZE, INPUT_SIZE)
        imageTensor.close(); maskTensor.close(); resultTensor.close(); outputs.close()
        inputBitmap.recycle(); maskBitmap.recycle(); scaledSrc.recycle()
        return@withContext generated512
    }

    // ===== Tensor 转换工具方法 =====
    private fun bitmapToTensor(bitmap: Bitmap): OnnxTensor {
        val w = bitmap.width; val h = bitmap.height
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
        val w = bitmap.width; val h = bitmap.height
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
