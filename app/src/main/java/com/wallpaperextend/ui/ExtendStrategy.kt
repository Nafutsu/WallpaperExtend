package com.wallpaperextend.processor.NPU

import android.content.Context
import android.graphics.Bitmap
import com.wallpaperextend.processor.WallpaperConfig

/**
 * 壁纸延展策略接口。
 * NpuExtendEngine（NPU 神经网络）与 RenderEffectWallpaperProcessor（GPU 降级）
 * 都实现该接口，供上层统一调度。
 */
interface ExtendStrategy {

    /** 该策略是否可用 */
    fun isAvailable(): Boolean

    /** 策略名称（日志/调试） */
    fun name(): String

    /** 执行延展，返回完整拼接好的最终壁纸 */
    suspend fun extend(
        context: Context,
        src: Bitmap,
        targetW: Int,
        targetH: Int,
        config: WallpaperConfig
    ): Bitmap
}
