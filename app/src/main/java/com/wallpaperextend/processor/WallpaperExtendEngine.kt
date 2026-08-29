package com.wallpaperextend.processor

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.Lifecycle
import com.wallpaperextend.processor.NPU.NpuExtendEngine

/**
 * ★ 兼容层（原 WallpaperExtendEngine）。
 * 不再重复定义 WallpaperConfig —— 统一以 [WallpaperConfig]（WallpaperConfig.kt）为准。
 * 内部委托给 [WallpaperProcessor]，移除旧依赖。
 */
class WallpaperExtendEngine private constructor(
    private val context: Context
) {

    private val processor = WallpaperProcessor
    private var npu: NpuExtendEngine? = null

    companion object {
        /** 兼容旧调用：WallpaperExtendEngine.create(context) */
        fun create(context: Context): WallpaperExtendEngine =
            WallpaperExtendEngine(context.applicationContext)
    }

    /**
     * 统一处理入口（兼容旧签名）。
     * @param useNpu 是否使用 NPU AI 生成（默认 true，不可用时自动降级 CPU）
     */
    suspend fun process(
        context: Context = this.context,
        src: Bitmap,
        targetW: Int,
        targetH: Int,
        config: WallpaperConfig = WallpaperConfig(),
        useNpu: Boolean = true
    ): Bitmap {
        val wpConfig = WallpaperProcessor.Config(
            blurRadius = config.blurRadius.toInt(),
            extendRatio = config.extendRatio,
            featherWidth = config.featherWidth,
            topOnly = config.topOnly,
            mode = WallpaperProcessor.Mode.LIGHT
        )
        return processor.process(
            context = context,
            src = src,
            targetW = targetW,
            targetH = targetH,
            config = wpConfig,
            useNpu = useNpu && isNpuAvailable()
        )
    }

    fun isNpuAvailable(): Boolean {
        if (npu == null) npu = NpuExtendEngine(context)
        return npu?.isAvailable() == true
    }

    fun release() {
        npu?.release()
        processor.release()
        npu = null
    }

    /** 生命周期兼容（旧代码可能在 onDestroy 调用） */
    fun bindToLifecycle(lifecycle: Lifecycle) {
        // 无需实际操作；NPU 引擎在 release() 时释放
    }
}
