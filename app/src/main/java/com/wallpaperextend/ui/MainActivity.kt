package com.wallpaperextend.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.wallpaperextend.R
import com.wallpaperextend.processor.WallpaperProcessor
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var imgResult: ImageView
    private lateinit var seekBlur: SeekBar
    private lateinit var seekExtend: SeekBar
    private lateinit var seekFeather: SeekBar
    private lateinit var btnProcess: Button

    private var originalBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imgResult = findViewById(R.id.imgResult)
        seekBlur = findViewById(R.id.seekBlur)
        seekExtend = findViewById(R.id.seekExtend)
        seekFeather = findViewById(R.id.seekFeather)
        btnProcess = findViewById(R.id.btnProcess)

        // 加载示例图片（或从相册选择）
        originalBitmap = BitmapFactory.decodeResource(resources, R.drawable.sample)

        btnProcess.setOnClickListener {
            processImage()
        }
    }

    private fun processImage() {
        val src = originalBitmap ?: return

        val blurRadius = seekBlur.progress.coerceAtLeast(1)
        val extendRatio = seekExtend.progress / 100f  // SeekBar 0-100 → 0.0-1.0
        val featherWidth = seekFeather.progress

        // 核心调用：一行搞定
        val result = WallpaperProcessor.extendWallpaper(
            src = src,
            extendRatio = extendRatio,
            blurRadius = blurRadius,
            featherWidth = featherWidth
        )

        imgResult.setImageBitmap(result)
    }
}
