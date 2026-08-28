package com.wallpaperextend.ui

import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.widget.SeekBar
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wallpaperextend.databinding.ActivityMainBinding
import com.wallpaperextend.processor.RenderEffectWallpaperProcessor
import com.wallpaperextend.processor.WallpaperConfig
import com.wallpaperextend.util.ImageLoader
import com.wallpaperextend.util.ImageSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@RequiresApi(Build.VERSION_CODES.S)
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var originalBitmap: Bitmap? = null
    private var processedBitmap: Bitmap? = null
    private var blurRadius = 25
    private var extendRatio = 0.35f
    private var featherWidth = 200
    private var saturationBoost = 1.1f
    private var brightnessOffset = 0.05f
    private var overlayStrength = 0.08f
    private var targetHeight: Int = 0
    private var reprocessJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnPick.setOnClickListener { pickImage() }
        binding.btnSave.setOnClickListener { saveCurrent() }
        
        binding.seekBlur.setOnSeekBarChangeListener(object : SimpleSeekBar() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                blurRadius = progress.coerceAtLeast(1)
                binding.tvBlur.text = "模糊: $blurRadius"
                if (fromUser) reprocess()
            }
        })
        binding.seekFeather.setOnSeekBarChangeListener(object : SimpleSeekBar() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                featherWidth = progress
                binding.tvFeather.text = "羽化: $featherWidth"
                if (fromUser) reprocess()
            }
        })
    }

    private fun pickImage() {
        lifecycleScope.launch {
            val uri = ImageLoader.pickImage(this@MainActivity)
            if (uri != null) {
                val bmp = ImageLoader.loadFromUri(this@MainActivity, uri)
                originalBitmap?.recycleSafe()
                originalBitmap = bmp
                binding.imgOriginal.setImageBitmap(bmp)
                binding.btnSave.isEnabled = false
                processImage()
            }
        }
    }

    private fun reprocess() {
        if (originalBitmap == null) return
        reprocessJob?.cancel()
        reprocessJob = lifecycleScope.launch {
            delay(150)
            processImage()
        }
    }

    private suspend fun processImage() {
        val src = originalBitmap ?: return
        binding.progress.visibility = android.view.View.VISIBLE
        val result = withContext(Dispatchers.IO) {
            val dm = resources.displayMetrics
            val screenW = dm.widthPixels
            val refH = targetHeight.takeIf { it > 0 } ?: dm.heightPixels
            RenderEffectWallpaperProcessor.process(
                context = this@MainActivity,
                src = src,
                targetW = screenW,
                targetH = refH,
                config = WallpaperConfig(
                    blurRadius = blurRadius.toFloat(),
                    extendRatio = extendRatio,
                    featherWidth = featherWidth,
                    saturationBoost = saturationBoost,
                    brightnessOffset = brightnessOffset,
                    overlayStrength = overlayStrength
                )
            )
        }
        processedBitmap?.recycleSafe()
        processedBitmap = result
        binding.imgResult.setImageBitmap(result)
        binding.btnSave.isEnabled = true
        binding.progress.visibility = android.view.View.GONE
    }

    private fun saveCurrent() {
        val bmp = processedBitmap ?: return
        lifecycleScope.launch {
            val ok = try {
                withContext(Dispatchers.IO) {
                    ImageSaver.saveToGallery(this@MainActivity, bmp, "Wallpaper_${System.currentTimeMillis()}.png")
                }
            } catch (e: Exception) { false }
            if (ok) android.widget.Toast.makeText(this@MainActivity, "保存成功", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        originalBitmap?.recycleSafe()
        processedBitmap?.recycleSafe()
    }

    private fun Bitmap?.recycleSafe() {
        if (this != null && !isRecycled) try { recycle() } catch (_: Exception) {}
    }

    abstract class SimpleSeekBar : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }
}
