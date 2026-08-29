package com.wallpaperextend.processor

/**
 * 壁纸处理配置（纯数据，跨策略共用）。
 * 注意字段命名：策略实现使用 [blurRadius] / [extendRatio] / [featherWidth] / [topOnly]。
 */
data class WallpaperConfig(
    val blurRadius: Float = 15f,
    val extendRatio: Float = 0.40f,
    val featherWidth: Int = 180,
    val saturationBoost: Float = 1.1f,
    val brightnessOffset: Float = 0.08f,
    val overlayStrength: Float = 0.10f,
    val topOnly: Boolean = true
)
