package com.wallpaperextend.processor

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.wallpaperextend.processor.NPU.ExtendStrategy
import com.wallpaperextend.processor.NPU.NpuExtendEngine

/**
 * 统一延展引擎：自动选择最优策略。
 * 优先级：NPU (LaMa) > RenderEffect (GPU 降级)
 */
class WallpaperExtendEngine private constructor(
    private val strategies: List<ExtendStrategy>
) {

    companion object {
        private const val TAG = "WallpaperExtendEngine"

        fun create(context: Context): WallpaperExtendEngine {
            val list = mutableListOf<ExtendStrategy>()

            // NPU 优先（LaMa 延展模型）
            val npu = NpuExtendEngine(context)
            if (npu.isAvailable()) {
                npu.loadModels()
                list.add(npu)
            }

            // GPU 降级（RenderEffect，保证始终可用）
            list.add(RenderEffectWallpaperProcessor)

            Log.d(TAG, "Strategies: ${list.joinToString { it.name() }}")
            return WallpaperExtendEngine(list)
        }
    }

    private val active: ExtendStrategy get() = strategies.first()

    suspend fun process(
        context: Context,
        src: Bitmap,
        targetW: Int,
        targetH: Int,
        config: WallpaperConfig
    ): Bitmap {
        Log.d(TAG, "Processing with: ${active.name()}")
        return active.extend(context, src, targetW, targetH, config)
    }

    fun release() {
        strategies.filterIsInstance<NpuExtendEngine>().forEach { it.release() }
    }
}
