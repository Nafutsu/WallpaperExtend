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
 * NPU 神经网络延展引擎（方案一 + 选择1：LaMa ONNX + 端侧推理）
 *
 * ★ 本次修复三处：
 *   1) 模型加载：assets → 内部存储文件 → createSession(路径)，不再 readBytes() 撑爆 Java 堆 (OOM)
 *   2) 推理受 NonCancellable 保护，拖动滑块取消上一协程时不会中断正在跑的推理
 *   3) 输出解析：LaMa 输出是完整修复图，直接缩放到目标尺寸作为延展区，不再错误截取
 *
 * 执行提供者：默认让 ORT 自动选择（CPU / NNAPI）。NNAPI 不可用时自动降级 CPU。
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

    /**
     * 加载模型。
     * ★ 修复 OOM：先把 assets 里的 .onnx 以流方式拷贝到内部存储，
     *   再用文件路径创建 Session —— 不会在 Java 堆上分配 198MB。
     */
    @Synchronized
    fun loadModels() {
        if (isInitialized) return
        try {
            env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                // NNAPI：走 NPU / GPU / DSP；不可用则降级 CPU
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

            // ★ 关键：从文件路径加载，不占 Java 堆
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
        // ★ NonCancellable：保证一次推理完整跑完，不被外部 cancel 打断
        kotlinx.coroutines.withContext(NonCancellable) {
            if (!isInitialized) loadModels()
            val ortSession = session
                ?: throw IllegalStateException("NPU model not loaded (isAvailable=${isAvailable()})")

            val extendH = (targetH * config.extendRatio.coerceIn(0.05f, 0.6f)).toInt().coerceAtLeast(8)

            // 原图适配目标宽度
            val scaledH = (targetW.toFloat() / src.width * src.height).toInt().coerceAtLeast(1)
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
                drawRect(0f, 0f, INPUT_SIZE.toFloat(), maskBottomH.toFloat(),
                    Paint().apply { color = Color.WHITE })
            }

            // ---- 推理 ----
            val imageTensor = bitmapToTensor(inputBitmap)
            val maskTensor = maskToTensor(maskBitmap)
            val inputs = mapOf("image" to imageTensor, "mask" to maskTensor)
            val outputs = ortSession.run(inputs)
            val resultTensor = outputs[0] as OnnxTensor
            val generated512 = floatBufferToBitmap(resultTensor.floatBuffer, INPUT_SIZE, INPUT_SIZE)

            inputBitmap.recycle()
            maskBitmap.recycle()
            scaledSrc.recycle()

            // ---- ★ 拼接：模型输出是完整修复图，直接缩放到 (targetW × targetH) ----
            // 这样延展区(顶部)与原图(底部)自然衔接，避免"截取一半拼接"导致的错位
            val finalBitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(finalBitmap)
            // 先画模型生成的完整延展背景
            canvas.drawBitmap(
                Bitmap.createScaledBitmap(generated512, targetW, targetH, true),
                0f, 0f, null
            )
            // 再叠加原图（从 extendH 开始，紧贴延展区下方）
            val bottomH = (targetH - extendH).coerceAtLeast(0)
            if (bottomH > 0) {
                val bottomSrc = Bitmap.createScaledBitmap(scaledSrc, targetW, bottomH, true)
                canvas.drawBitmap(bottomSrc, 0f, extendH.toFloat(), null)
                if (bottomSrc !== scaledSrc) bottomSrc.recycle()
            }
            generated512.recycle()

            finalBitmap
        }
    }

    // ===== Tensor 转换工具方法 =====

    /** Bitmap [0,255] → FloatBuffer [-1,1]，形状 [1,3,H,W] (NCHW) */
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

    /** Alpha8 mask → FloatBuffer [0,1]，形状 [1,1,H,W] */
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

    /**
     * ONNX floatBuffer → Bitmap
     * 布局：[1, 3, H, W] = R 平面 + G 平面 + B 平面，值 ∈ [-1, 1]
     */
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
