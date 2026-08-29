package com.wallpaperextend.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wallpaperextend.databinding.ActivityMainBinding
import com.wallpaperextend.processor.WallpaperConfig
import com.wallpaperextend.processor.WallpaperExtendEngine
import com.wallpaperextend.util.MediaStoreSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    // ★ 懒加载：不在 onCreate 里初始化，避免启动时加载 198MB 模型导致 OOM
    private var engine: WallpaperExtendEngine? = null

    private fun getEngine(): WallpaperExtendEngine {
        if (engine == null) {
            engine = WallpaperExtendEngine.create(this)
        }
        return engine!!
    }

    private var originalBitmap: Bitmap? = null
    private var processedBitmap: Bitmap? = null

    // 参数（默认值与 XML 一致）
    private var blurRadius = 20f
    private var extendRatio = 0.37f
    private var featherWidth = 150
    private var saturationBoost = 1.1f
    private var brightnessOffset = 0f
    private var overlayStrength = 0.15f
    private var targetHeight = 0

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { loadImage(it) } }

    private var reprocessJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ★ engine 不再这里创建，等用户操作时通过 getEngine() 懒加载
        setupUI()
    }

    private fun setupUI() {
        binding.btnPick.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        binding.btnSave.setOnClickListener {
            if (processedBitmap == null) {
                Toast.makeText(this, "请先生成延展壁纸", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveImage()
        }

        // 延展比例
        binding.seekExtend.setOnSeekBarChangeListener(object : SimpleSeekBar() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                extendRatio = (progress / 100f).coerceIn(0f, 0.6f)
                binding.tvExtend.text = "延展比例: ${progress}%"
                if (fromUser) reprocess()
            }
        })

        // 模糊半径
        binding.seekBlur.setOnSeekBarChangeListener(object : SimpleSeekBar() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                blurRadius = progress.toFloat().coerceAtLeast(1f)
                binding.tvBlur.text = "模糊半径: $progress"
                if (fromUser) reprocess()
            }
        })

        // 羽化宽度
        binding.seekFeather.setOnSeekBarChangeListener(object : SimpleSeekBar() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                featherWidth = progress.coerceAtLeast(8)
                binding.tvFeather.text = "羽化宽度: $progress"
                if (fromUser) reprocess()
            }
        })

        // 饱和度
        binding.seekSaturation.setOnSeekBarChangeListener(object : SimpleSeekBar() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                saturationBoost = 0.5f + progress / 100f
                binding.tvSaturation.text = "饱和度: ${"%.1f".format(saturationBoost)}x"
                if (fromUser) reprocess()
            }
        })

        // 亮度
        binding.seekBrightness.setOnSeekBarChangeListener(object : SimpleSeekBar() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                brightnessOffset = (progress - 50) / 250f
                binding.tvBrightness.text = "亮度: ${"%.2f".format(brightnessOffset)}"
                if (fromUser) reprocess()
            }
        })

        // 蒙版强度
        binding.seekOverlay.setOnSeekBarChangeListener(object : SimpleSeekBar() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                overlayStrength = progress / 100f
                binding.tvOverlay.text = "蒙版强度: ${progress}%"
                if (fromUser) reprocess()
            }
        })

        binding.etTargetHeight.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                targetHeight = binding.etTargetHeight.text.toString().toIntOrNull() ?: 0
                reprocess()
            }
        }
    }

    private fun loadImage(uri: Uri) {
        try {
            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            binding.imgOriginal.setImageBitmap(originalBitmap)
            binding.tvSize.text = "原图尺寸: ${originalBitmap?.width} × ${originalBitmap?.height}"
            processedBitmap = null
            binding.btnSave.isEnabled = false
            reprocess()
        } catch (e: Exception) {
            Toast.makeText(this, "加载图片失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun currentConfig(): WallpaperConfig = WallpaperConfig(
        blurRadius = blurRadius,
        extendRatio = extendRatio,
        featherWidth = featherWidth,
        saturationBoost = saturationBoost,
        brightnessOffset = brightnessOffset,
        overlayStrength = overlayStrength
    )

    private fun reprocess() {
        val src = originalBitmap ?: return
        reprocessJob?.cancel()
        reprocessJob = lifecycleScope.launch {
            delay(150)
            processImage(src)
        }
    }

    private suspend fun processImage(src: Bitmap) {
        binding.progress.visibility = android.view.View.VISIBLE
        try {
            val dm = resources.displayMetrics
            val result = withContext(Dispatchers.IO) {
                getEngine().process(
                    context = this@MainActivity,
                    src = src,
                    targetW = dm.widthPixels,
                    targetH = targetHeight.takeIf { it > 0 } ?: dm.heightPixels,
                    config = currentConfig()
                )
            }
            processedBitmap?.recycle()
            processedBitmap = result
            binding.imgResult.setImageBitmap(result)
            binding.btnSave.isEnabled = true
        } catch (e: Exception) {
            Toast.makeText(this@MainActivity, "处理失败: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        } finally {
            binding.progress.visibility = android.view.View.GONE
        }
    }

    private fun saveImage() {
        val bmp = processedBitmap ?: return
        lifecycleScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    MediaStoreSaver.save(this@MainActivity, bmp, "WallpaperExtend_${System.currentTimeMillis()}.png")
                }
                if (uri != null) {
                    Toast.makeText(this@MainActivity, "已保存到相册", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "保存失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        engine?.release()
        originalBitmap?.recycle()
        processedBitmap?.recycle()
    }

    abstract class SimpleSeekBar : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }
}
