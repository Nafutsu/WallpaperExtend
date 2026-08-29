package com.wallpaperextend.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wallpaperextend.databinding.ActivityMainBinding
import com.wallpaperextend.processor.WallpaperProcessor
import com.wallpaperextend.util.MediaStoreSaver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
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

    // ★ 防抖排队：拖动滑块时标记"需要重处理"，正在处理的推理跑完后再触发一次
    private var shouldReprocess = false
    private var reprocessJob: Job? = null

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { loadImage(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // ★ 不再依赖 WallpaperExtendEngine，统一走 WallpaperProcessor
        setupUI()
    }

    private fun setupUI() {
        binding.btnPick.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }
        binding.btnSave.setOnClickListener {
            val bmp = processedBitmap
            if (bmp == null) {
                Toast.makeText(this, "请先生成延展壁纸", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveImage(bmp)
        }

        binding.seekExtend.setOnSeekBarChangeListener(object : SimpleSeekBar() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                extendRatio = (progress / 100f).coerceIn(0f, 0.6f)
                binding.tvExtend.text = "延展比例: ${progress}%"
                if (fromUser) scheduleReprocess()
            }
        })
        binding.seekBlur.setOnSeekBarChangeListener(object : SimpleSeekBar() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                blurRadius = progress.toFloat().coerceAtLeast(1f)
                binding.tvBlur.text = "模糊半径: $progress"
                if (fromUser) scheduleReprocess()
            }
        })
        binding.seekFeather.setOnSeekBarChangeListener(object : SimpleSeekBar() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                featherWidth = progress.coerceAtLeast(8)
                binding.tvFeather.text = "羽化宽度: $progress"
                if (fromUser) scheduleReprocess()
            }
        })
        binding.seekSaturation.setOnSeekBarChangeListener(object : SimpleSeekBar() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                saturationBoost = 0.5f + progress / 100f
                binding.tvSaturation.text = "饱和度: ${"%.1f".format(saturationBoost)}x"
                if (fromUser) scheduleReprocess()
            }
        })
        binding.seekBrightness.setOnSeekBarChangeListener(object : SimpleSeekBar() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                brightnessOffset = (progress - 50) / 250f
                binding.tvBrightness.text = "亮度: ${"%.2f".format(brightnessOffset)}"
                if (fromUser) scheduleReprocess()
            }
        })
        binding.seekOverlay.setOnSeekBarChangeListener(object : SimpleSeekBar() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                overlayStrength = progress / 100f
                binding.tvOverlay.text = "蒙版强度: ${progress}%"
                if (fromUser) scheduleReprocess()
            }
        })

        binding.etTargetHeight.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                targetHeight = binding.etTargetHeight.text.toString().toIntOrNull() ?: 0
                scheduleReprocess()
            }
        }

        // ==================== 5.2 设置 iOS 风格默认参数 ====================
        binding.seekExtend.progress = 40          // 40%
        binding.seekBlur.progress = 15            // 半径 15
        binding.seekFeather.progress = 180        // 羽化 180px
        binding.seekSaturation.progress = 60      // 1.1x
        binding.seekBrightness.progress = 52      // +0.08
        binding.seekOverlay.progress = 10         // 10%
        binding.cbTopOnly.isChecked = true
        // 新增 cbUseNpu：若 XML 中已添加则默认勾选（安全调用，缺 ID 不崩溃）
        binding.cbUseNpu?.isChecked = true

        binding.tvExtend.text = "延展比例: 40%"
        binding.tvBlur.text = "模糊半径: 15"
        binding.tvFeather.text = "羽化宽度: 180"
        binding.tvSaturation.text = "饱和度: 1.1x"
        binding.tvBrightness.text = "亮度: 0.08"
        binding.tvOverlay.text = "蒙版强度: 10%"

        extendRatio = 0.40f
        blurRadius = 15f
        featherWidth = 180
        saturationBoost = 1.1f
        brightnessOffset = 0.08f
        overlayStrength = 0.10f
        // ==================================================================

        // ==================== 5.1 绑定复选框 ====================
        binding.cbTopOnly.setOnCheckedChangeListener { _, _ -> scheduleReprocess() }
        // cbUseNpu 变化时也触发重处理（若 XML 中无此 ID，?. 安全跳过）
        binding.cbUseNpu?.setOnCheckedChangeListener { _, _ -> scheduleReprocess() }
        // ==================================================================
    }

    private fun loadImage(uri: Uri) {
        try {
            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            binding.imgOriginal.setImageBitmap(originalBitmap)
            binding.tvSize.text = "原图尺寸: ${originalBitmap?.width} × ${originalBitmap?.height}"
            processedBitmap?.recycle()
            processedBitmap = null
            binding.btnSave.isEnabled = false
            shouldReprocess = false
            scheduleReprocess()
        } catch (e: Exception) {
            Toast.makeText(this, "加载图片失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 当前配置（含 topOnly） */
    private fun currentConfig(): WallpaperProcessor.Config = WallpaperProcessor.Config(
        blurRadius = blurRadius.toInt(),
        extendRatio = extendRatio,
        featherWidth = featherWidth,
        topOnly = binding.cbTopOnly.isChecked
    )

    /** 是否使用 NPU（从新增的 cbUseNpu 读取） */
    private fun useNpu(): Boolean = binding.cbUseNpu?.isChecked == true

    // ★ 防抖 + 排队
    private fun scheduleReprocess() {
        val src = originalBitmap ?: return
        shouldReprocess = true
        if (reprocessJob?.isActive == true) return
        reprocessJob = lifecycleScope.launch {
            delay(300)
            while (shouldReprocess) {
                shouldReprocess = false
                try {
                    processImage(src)
                } catch (e: Exception) {
                    if (e is CancellationException) continue
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "处理失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // ★ 调用 WallpaperProcessor.process（suspend，已在 Dispatchers.Default 执行）
    private suspend fun processImage(src: Bitmap) {
        withContext(Dispatchers.Main) { binding.progress.visibility = View.VISIBLE }
        try {
            val dm = resources.displayMetrics
            val result = withContext(Dispatchers.IO) {
                kotlinx.coroutines.withContext(NonCancellable) {
                    WallpaperProcessor.process(
                        context = this@MainActivity,
                        src = src,
                        targetW = dm.widthPixels,
                        targetH = targetHeight.takeIf { it > 0 } ?: dm.heightPixels,
                        config = currentConfig(),
                        useNpu = useNpu()   // ← 从 cbUseNpu 读取
                    )
                }
            }
            processedBitmap?.recycle()
            processedBitmap = result
            binding.imgResult.setImageBitmap(result)
            binding.btnSave.isEnabled = true
        } catch (e: Exception) {
            if (e !is CancellationException) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "处理失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
                e.printStackTrace()
            }
        } finally {
            withContext(Dispatchers.Main) { binding.progress.visibility = View.GONE }
        }
    }

    private fun saveImage(bmp: Bitmap) {
        lifecycleScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    MediaStoreSaver.save(
                        this@MainActivity, bmp,
                        "WallpaperExtend_${System.currentTimeMillis()}.png"
                    )
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
        reprocessJob?.cancel()
        WallpaperProcessor.release()   // ★ 替代 engine?.release()
        originalBitmap?.recycle()
        processedBitmap?.recycle()
    }

    /** 简易 SeekBar 监听器 */
    abstract class SimpleSeekBar : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }
}
