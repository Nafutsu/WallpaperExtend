package com.wallpaperextend.util

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log

/**
 * 壁纸设置工具
 */
object WallpaperSetter {

    private const val TAG = "WallpaperSetter"

    const val TARGET_HOME = 1
    const val TARGET_LOCK = 2
    const val TARGET_BOTH = 3

    /**
     * 设置壁纸（兼容 Android 7.0+）
     */
    fun setWallpaper(context: Context, bitmap: Bitmap, target: Int = TARGET_BOTH): Boolean {
        return try {
            val manager = WallpaperManager.getInstance(context)

            when (target) {
                TARGET_HOME -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        // setBitmap(bitmap, which) — Android 7.0+
                        manager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM)
                    } else {
                        @Suppress("DEPRECATION")
                        manager.setBitmap(bitmap)
                    }
                }
                TARGET_LOCK -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        manager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)
                    } else {
                        @Suppress("DEPRECATION")
                        manager.setLockWallpaperBitmap(bitmap)
                    }
                }
                TARGET_BOTH -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        manager.setBitmap(bitmap, null, true,
                            WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
                    } else {
                        @Suppress("DEPRECATION")
                        manager.setBitmap(bitmap)
                    }
                }
            }
            Log.d(TAG, "Wallpaper set successfully, target=$target")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set wallpaper", e)
            false
        }
    }

    /**
     * 获取屏幕尺寸（用于计算延展目标尺寸）
     */
    fun getScreenDimensions(context: Context): Pair<Int, Int> {
        val displayMetrics = context.resources.displayMetrics
        return displayMetrics.widthPixels to displayMetrics.heightPixels
    }
}
