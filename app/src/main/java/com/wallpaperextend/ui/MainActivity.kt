package com.wallpaperextend.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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

    private var blurRadius = 20
    private var extendRatio = 0.37f
    private var featherWidth = 200
    private var targetHeight = 0

    private var saturationBoost = 1.1f
    private var brightnessOffset = 0f
    private var overlayStrength = 0.08f

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val uri = result.data!!.data ?: return@registerForActivityResult
            loadAndProcess(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        handleSharedIntent()
    }

    private fun setupUI() {
        binding.btnPick.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK).apply {
                type = "image/*"
                action = Intent.ACTION_GET_CONTENT
            }
            pickImageLauncher.launch(intent)
        }

        binding.btnSave.setOnClickListener {
            if (processedBitmap == null) {
                Toast.makeText(this, "请先选择并生成壁纸", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveCurrent()
        }

        binding.cbTopOnly.isEnabled = false
        binding.cbTopOnly.isChecked = true

        binding.etTargetHeight.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) { updateTargetHeight(); reprocess() }
        }

        binding.seekBlur.setOnSeekBarChangeListener(object : SimpleSeekBar() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                blurRadius = progress.coerceAtLeast(1)
                binding.tvBlur.text = "模糊半径: $blurRadius"
                if (fromUser) reprocess()
            }
        })
        binding.seekBlur.progress = blurRadius

        binding.seekExtend.setOnSeekBarChangeListener(object : SimpleSeekBar() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                extendRatio = (progress / 100f).coerceIn(0f, 0.6f)
                binding.tvExtend.text = "延展比例: ${(extendRatio * 100).toInt()}%"
                if (fromUser) reprocess()
            }
        })
        binding.seekExtend.progress = (extendRatio * 100).toInt()

        binding.seekFeather.setOnSeekBarChangeListener(object : SimpleSeekBar() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                featherWidth = progress.coerceAtLeast(8)
                binding.tvFeather.text = "羽化宽度: $featherWidth"
                if (fromUser) reprocess()
            }
        })
        binding.seekFeather.progress = featherWidth

        binding.seekSaturation.setOnSeekBarChangeListener(object : SimpleSeekBar() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                saturationBoost = 0.5f + progress / 100f
                binding.tvSaturation.text = "饱和度: ${"%.1f".format(saturationBoost)}x"
                if (fromUser) reprocess()
            }
        })
        binding.seekSaturation.progress = 60

        binding.seekBrightness.setOnSeekBarChangeListener(object : SimpleSeekBar() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                brightnessOffset = (progress - 50) / 250f
                binding.tvBrightness.text = "亮度: ${"%.2f".format(brightnessOffset)}"
                if (fromUser) reprocess()
            }
        })
        binding.seekBrightness.progress = 50

        binding.seekOverlay.setOnSeekBarChangeListener(object : SimpleSeekBar() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                overlayStrength = progress / 100f
                binding.tvOverlay.text = "蒙版强度: ${(overlayStrength * 100).toInt()}%"
                if (fromUser) reprocess()
            }
        })
        binding.seekOverlay.progress = 8
    }

    private fun updateTargetHeight() {
        val value = binding.etTargetHeight.text.toString().toIntOrNull()
        targetHeight = if (value != null && value > 0) value else 0
    }

    private fun handleSharedIntent() {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            uri?.let { loadAndProcess(it) }
        }
    }

    private fun loadAndProcess(uri: Uri) {
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                ImageLoader.loadFromUri(this@MainActivity, uri)
            }
            binding.tvSize.text = "原图尺寸: ${bmp.width} × ${bmp.height}"
            if (binding.etTargetHeight.text.isNullOrBlank()) {
                binding.etTargetHeight.hint = "默认 = 屏幕高度"
            }
            originalBitmap?.recycleSafe()
            originalBitmap = bmp
            binding.imgOriginal.setImageBitmap(bmp)
            binding.btnSave.isEnabled = false
            processImage()
        }
    }

    private var reprocessJob: Job? = null
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
        binding.progress.visibility = View.VISIBLE
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
        binding.progress.visibility = View.GONE
    }

    private fun saveCurrent() {
        val bmp = processedBitmap ?: return
        binding.btnSave.isEnabled = false
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            var errorMsg: String? = null
            val ok = try {
                withContext(Dispatchers.IO) {
                    ImageSaver.saveToGallery(
                        this@MainActivity, bmp,
                        "WallpaperExtend_${System.currentTimeMillis()}.png"
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
                errorMsg = e.message
                false
            }
            withContext(Dispatchers.Main) {
                binding.progress.visibility = View.GONE
                binding.btnSave.isEnabled = true
                if (ok) Toast.makeText(this@MainActivity, "保存成功", Toast.LENGTH_LONG).show()
                else Toast.makeText(this@MainActivity, "保存失败：$errorMsg", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        originalBitmap?.recycleSafe()
        processedBitmap?.recycleSafe()
    }

    private fun Bitmap?.recycleSafe() {
        if (this != null && !isRecycled) {
            try { recycle() } catch (_: Exception) {}
        }
    }

    abstract class SimpleSeekBar : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }
}
