package com.wallpaperextend.processor.NPU

import android.content.Context
import android.graphics.Bitmap
import com.wallpaperextend.processor.WallpaperConfig

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
