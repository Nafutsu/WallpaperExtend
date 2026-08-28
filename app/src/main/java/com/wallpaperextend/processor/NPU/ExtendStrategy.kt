package com.wallpaperextend.processor.NPU

import android.content.Context
import android.graphics.Bitmap
import com.wallpaperextend.processor.WallpaperConfig

/**
 * 壁纸延展策略接口。
 * NPU 神经网络延展 / RenderEffect GPU 降级都实现这个接口，
 * 让上层（MainActivity / WallpaperExtendEngine）无需关心底层实现。
 */
interface ExtendStrategy {

    /** 该策略是否可用（NPU 策略用来检测设备/模型是否支持） */
    fun isAvailable(): Boolean

    /** 策略名称（用于日志/调试） */
    fun name(): String

    /**
     * 执行延展。
     */
    suspend fun extend(
        context: Context,
        src: Bitmap,
        targetW: Int,
        targetH: Int,
        config: WallpaperConfig
    ): Bitmap
}
