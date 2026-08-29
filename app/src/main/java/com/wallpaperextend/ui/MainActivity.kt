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
import com.wallpaperextend.processor.WallpaperConfig
import com.wallpaperextend.processor.WallpaperExtendEngine
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
    private var engine: WallpaperExtendEngine? = null
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
        // ★ engine 不在 onCreate 创建（避免启动时加载 198MB 模型 → OOM/ANR）
        setupUI()
    }

    /** 懒加载引擎（用到才加载模型） */
    private fun getEngine(): WallpaperExtendEngine {
        if (engine == null) {
            engine = WallpaperExtendEngine.create(this)
        }
        return engine!!
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
        // 设置 iOS 风格默认参数（与 XML 初始值不同）
        binding.seekExtend.progress = 40          // 40%
        binding.seekBlur.progress = 15            // 半径 15
        binding.seekFeather.progress = 180        // 羽化 180px
        binding.seekSaturation.progress = 60      // 1.1x
        binding.seekBrightness.progress = 52      // +0.08
        binding.seekOverlay.progress = 10         // 10%
        binding.cbTopOnly.isChecked = true

        // 更新对应的 TextView（否则显示还是旧值）
        binding.tvExtend.text = "延展比例: 40%"
        binding.tvBlur.text = "模糊半径: 15"
        binding.tvFeather.text = "羽化宽度: 180"
        binding.tvSaturation.text = "饱和度: 1.1x"
        binding.tvBrightness.text = "亮度: 0.08"
        binding.tvOverlay.text = "蒙版强度: 10%"

        // 同步成员变量
        extendRatio = 0.40f
        blurRadius = 15f
        featherWidth = 180
        saturationBoost = 1.1f
        brightnessOffset = 0.08f
        overlayStrength = 0.10f
        // ==================================================================

        // ==================== 5.1 绑定 cbTopOnly 复选框 ====================
        binding.cbTopOnly.setOnCheckedChangeListener { _, _ ->
            scheduleReprocess()
        }
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

    // ==================== 5.3 修改 currentConfig() 加入 topOnly ====================
    private fun currentConfig(): WallpaperConfig = WallpaperConfig(
        blurRadius = blurRadius,
        extendRatio = extendRatio,
        featherWidth = featherWidth,
        saturationBoost = saturationBoost,
        brightnessOffset = brightnessOffset,
        overlayStrength = overlayStrength,
        topOnly = binding.cbTopOnly.isChecked  // ← 新增
    )
    // =============================================================================

    // ★ 修复：防抖 + 排队，拖动滑块不再疯狂 cancel → 不再抛 "was cancelled"
    private fun scheduleReprocess() {
        val src = originalBitmap ?: return
        shouldReprocess = true
        if (reprocessJob?.isActive == true) {
            // 正在处理中，本次只标记，等当前推理结束后再触发一次
            return
        }
        reprocessJob = lifecycleScope.launch {
            delay(300) // 防抖
            while (shouldReprocess) {
                shouldReprocess = false
                try {
                    processImage(src)
                } catch (e: Exception) {
                    if (e is CancellationException) {
                        // ★ 取消异常：静默，继续检查是否需要重处理（不弹 Toast）
                        continue
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "处理失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private suspend fun processImage(src: Bitmap) {
        withContext(Dispatchers.Main) { binding.progress.visibility = View.VISIBLE }
        try {
            val dm = resources.displayMetrics
            // ★ NonCancellable：保证一次推理完整跑完，不被外部 cancel 打断
            val result = withContext(Dispatchers.IO) {
                kotlinx.coroutines.withContext(NonCancellable) {
                    getEngine().process(
                        context = this@MainActivity,
                        src = src,
                        targetW = dm.widthPixels,
                        targetH = targetHeight.takeIf { it > 0 } ?: dm.heightPixels,
                        config = currentConfig()
                    )
                }
            }
            processedBitmap?.recycle()
            processedBitmap = result
            binding.imgResult.setImageBitmap(result)
            binding.btnSave.isEnabled = true
        } catch (e: Exception) {
            // 外部 cancel 不弹提示
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
        engine?.release()
        originalBitmap?.recycle()
        processedBitmap?.recycle()
    }

    /** 简易 SeekBar 监听器，子类按需重写 onProgressChanged */
    abstract class SimpleSeekBar : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }
}
