package com.wallpaperextend.processor

data class WallpaperConfig(
    val blurRadius: Float = 25f,
    val extendRatio: Float = 0.35f,
    val featherWidth: Int = 200,
    val saturationBoost: Float = 1.1f,
    val brightnessOffset: Float = 0.05f,
    val overlayStrength: Float = 0.08f,
    val topOnly: Boolean = true   // ← 新增，控制延展方向，true=顶部延展
)
