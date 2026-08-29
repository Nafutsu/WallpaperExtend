package com.wallpaperextend.processor

import android.content.Context
import android.graphics.Bitmap
import com.wallpaperextend.processor.NPU.NpuExtendEngine

/**
 * 兼容委托层：保留旧 API，内部转发给 [WallpaperProcessor]。
 *
 * ★ 注意：本类只做薄封装，不持有任何配置数据类（无 Config / Mode）。
 *   所有参数通过 [WallpaperConfig] 传递。
 */
class WallpaperExtendEngine(context: Context) {

    private val appContext: Context = context.applicationContext

    // 若外部需要"默认参数"，直接用 WallpaperConfig() 即可（它有默认值）
    fun process(
        src: Bitmap,
        targetW: Int,
        targetH: Int,
        config: WallpaperConfig = WallpaperConfig(),
        useNpu: Boolean = true
    ): Bitmap {
        // 因为旧 API 不是 suspend，这里用 runBlocking 桥接（主线程调用会阻塞，慎用）
        return kotlinx.coroutines.runBlocking {
            WallpaperProcessor.process(
                context = appContext,
                src = src,
                targetW = targetW,
                targetH = targetH,
                config = config,
                useNpu = useNpu
            )
        }
    }

    /** 释放 NPU 资源（转发） */
    fun release() {
        WallpaperProcessor.release()
    }

    /** 预加载模型（可选，首次 process 会自动加载） */
    fun warmup() {
        // NpuExtendEngine 内部会在首次 extend 时 loadModels()
    }
}
