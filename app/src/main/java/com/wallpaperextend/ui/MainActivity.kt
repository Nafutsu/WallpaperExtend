package com.wallpaperextend.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wallpaperextend.databinding.ActivityMainBinding
import com.wallpaperextend.processor.WallpaperConfig
import com.wallpaperextend.processor.WallpaperExtendEngine
import com.wallpaperextend.util.ImageLoader
import com.wallpaperextend.util.ImageSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var engine: WallpaperExtendEngine
    private var originalBitmap: Bitmap? = null
    private var processedBitmap: Bitmap? = null

    // ★ 参数（与 XML 默认值对齐）
    private var blurRadius = 20
    private var extendRatio = 0.37f
    private var featherWidth = 150
    private var saturationBoost = 1.1f
    private var brightnessOffset = 0f
    private var overlayStrength = 0.15f
    private var targetHeight = 0

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val uri = result.data!!.data ?: return@registerForActivityResult
            loadAndProcess(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        engine = WallpaperExtendEngine.create(this)
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

        // topOnly 开关：iOS 风格固定仅顶部
        binding.cbTopOnly.isEnabled = false
        binding.cbTopOnly.isChecked = true

        binding.etTargetHeight.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                updateTargetHeight()
                reprocess()
            }
        }

        // ---- 模糊半径 ----
        binding.seekBlur.setOnSeekBarChangeListener(object : SimpleSeekBar() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                blurRadius = progress.coerceAtLeast(1)
                binding.tvBlur.text = "模糊半径: $blurRadius"
                if (fromUser) reprocess()
            }
        })

        // ---- 延展比例 ----
        binding.seekExtend.setOnSeekBarChangeListener(object : SimpleSeekBar() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                extendRatio = (progress / 100f).coerceIn(0f, 0.6f)
                binding.tvExtend.text = "延展比例: ${(extendRatio * 100).toInt()}%"
                if (fromUser) reprocess()
            }
        })

        // ---- 羽化宽度 ----
        binding.seekFeather.setOnSeekBarChangeListener(object : SimpleSeekBar() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                featherWidth = progress.coerceAtLeast(8)
                binding.tvFeather.text = "羽化宽度: $featherWidth"
                if (fromUser) reprocess()
            }
        })

        // ---- 饱和度 ----
        binding.seekSaturation.setOnSeekBarChangeListener(object : SimpleSeekBar() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                saturationBoost = 0.5f + progress / 100f
                binding.tvSaturation.text = "饱和度: ${"%.1f".format(saturationBoost)}x"
                if (fromUser) reprocess()
            }
        })

        // ---- 亮度 ----
        binding.seekBrightness.setOnSeekBarChangeListener(object : SimpleSeekBar() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                brightnessOffset = (progress - 50) / 250f
                binding.tvBrightness.text = "亮度: ${"%.2f".format(brightnessOffset)}"
                if (fromUser) reprocess()
            }
        })

        // ---- 蒙版强度 ----
        binding.seekOverlay.setOnSeekBarChangeListener(object : SimpleSeekBar() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                overlayStrength = progress / 100f
                binding.tvOverlay.text = "蒙版强度: ${(overlayStrength * 100).toInt()}%"
                if (fromUser) reprocess()
            }
        })
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
            engine.process(
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
        engine.release()
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
