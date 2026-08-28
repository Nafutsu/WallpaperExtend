package com.wallpaperextend.processor.ai

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.wallpaperextend.processor.WallpaperConfig

/**
 * ★ NPU 神经网络延展引擎 —— 骨架版
 * 
 * 另一位开发者：在这里填 ONNX Runtime + QNN 的真实推理逻辑。
 * 当前 isAvailable() 返回 false，走 RenderEffect 降级。
 */
class NpuExtendEngine : ExtendStrategy {

    companion object {
        private const val TAG = "NpuExtendEngine"
        private val SUPPORTED_HARDWARE = setOf("qcom", "mt", "kirin")
    }

    override fun isAvailable(): Boolean {
        // TODO: 真实检测
        // 1. 检查模型文件是否已下载
        // 2. 尝试初始化 QNN / NNAPI 委托
        // 3. 检查设备硬件
        return false
    }

    override fun name(): String = "NPU-Neural"

    override suspend fun extend(
        context: Context,
        src: Bitmap,
        targetW: Int,
        targetH: Int,
        config: WallpaperConfig
    ): Bitmap {
        // ★ TODO: 实现端侧扩散推理
        // 1. 预处理 src -> Tensor [1,3,512,512]
        // 2. 构造 mask（顶部 extendRatio 区域 = 1）
        // 3. encodeText("sky, clouds, seamless continuation")
        // 4. 扩散去噪循环 (LCM 4步 / DDIM 20步)
        // 5. VAE decode -> Bitmap
        // 6. 拼接：generated_top + src
        throw UnsupportedOperationException("NPU engine: TODO")
    }

    fun loadModels() {
        // TODO: ONNX Runtime 加载 .onnx + 选 EP
        Log.d(TAG, "loadModels() - TODO")
    }

    fun release() {
        Log.d(TAG, "release()")
    }
}
