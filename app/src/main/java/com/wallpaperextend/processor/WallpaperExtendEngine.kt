package com.wallpaperextend.processor

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.wallpaperextend.processor.ai.ExtendStrategy
import com.wallpaperextend.processor.ai.NpuExtendEngine

/**
 * 统一延展引擎 —— 自动选择最优策略。
 * 优先级：NPU > RenderEffect
 */
class WallpaperExtendEngine private constructor(
    private val strategies: List<ExtendStrategy>
) {

    companion object {
        private const val TAG = "WallpaperExtendEngine"

        fun create(context: Context): WallpaperExtendEngine {
            val list = mutableListOf<ExtendStrategy>()

            val npu = NpuExtendEngine()
            if (npu.isAvailable()) {
                npu.loadModels()
                list.add(npu)
                Log.d(TAG, "NPU strategy registered")
            }

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
