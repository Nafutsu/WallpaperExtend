package com.wallpaperextend.processor.ai

import android.content.Context
import android.graphics.Bitmap
import com.wallpaperextend.processor.WallpaperConfig

/**
 * 壁纸延展策略接口。
 * ★ 另一位开发者：NPU 模型实现这个接口。
 */
interface ExtendStrategy {
    fun isAvailable(): Boolean
    fun name(): String
    suspend fun extend(
        context: Context,
        src: Bitmap,
        targetW: Int,
        targetH: Int,
        config: WallpaperConfig
    ): Bitmap
}
