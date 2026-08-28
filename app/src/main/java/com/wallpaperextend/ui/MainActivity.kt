package com.wallpaperextend.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wallpaperextend.databinding.ActivityMainBinding
import com.wallpaperextend.processor.WallpaperExtend
import com.wallpaperextend.util.ImageLoader
import com.wallpaperextend.util.ImageSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var originalBitmap: Bitmap? = null
    private var processedBitmap: Bitmap? = null

    // 可调参数（默认值已调为推荐区间）
    private var blurRadius = 28
    private var extendRatio = 0.25f
    private var featherWidth = 40   // 默认 40，避免 120 产生厚雾带
    private var topOnly = true
    private var targetHeight = 0

    // 原图真实尺寸（加载后填充）
    private var srcWidth = 0
    private var srcHeight = 0

    // 选图回调
    private val pickImage = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val uri = result.data!!.data ?: return@registerForActivityResult
            loadAndProcess(uri)
        }
    }

    // 存储权限回调
    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            saveCurrent()
        } else {
            Toast.makeText(this, "需要存储权限才能保存图片", Toast.LENGTH_SHORT).show()
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
        // 选图按钮
        binding.btnPick.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK).apply {
                type = "image/*"
                action = Intent.ACTION_GET_CONTENT
            }
            pickImage.launch(intent)
        }

        // 下载/保存按钮
        binding.btnSave.setOnClickListener {
            if (processedBitmap == null) {
                Toast.makeText(this, "请先选择并生成壁纸", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            checkPermissionAndSave()
        }

        // 仅顶部延展开关
        binding.cbTopOnly.setOnCheckedChangeListener { _, checked ->
            topOnly = checked
            reprocess()
        }

        // 输出高度输入
        binding.etTargetHeight.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                updateTargetHeight()
                reprocess()
            }
        }

        // 参数调节：模糊半径（0~60，默认 28）
        binding.seekBlur.setOnSeekBarChangeListener(object : SimpleSeekBar() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                blurRadius = progress.coerceAtLeast(1)
                binding.tvBlur.text = "模糊半径: $blurRadius"
                if (fromUser) reprocess()
            }
        })
        binding.seekBlur.progress = blurRadius
        binding.seekBlur.max = 60

        // 延展比例（0~50%，默认 25%）
        binding.seekExtend.setOnSeekBarChangeListener(object : SimpleSeekBar() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                extendRatio = progress / 100f
                binding.tvExtend.text = "延展比例: ${(extendRatio * 100).toInt()}%"
                if (fromUser) reprocess()
            }
        })
        binding.seekExtend.progress = (extendRatio * 100).toInt()
        binding.seekExtend.max = 50

        // 羽化宽度（8~160，默认 40）
        binding.seekFeather.setOnSeekBarChangeListener(object : SimpleSeekBar() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                featherWidth = progress.coerceIn(8, 160)
                binding.tvFeather.text = "羽化宽度: $featherWidth"
                if (fromUser) reprocess()
            }
        })
        binding.seekFeather.progress = featherWidth
        binding.seekFeather.max = 160
    }

    private fun updateTargetHeight() {
        val value = binding.etTargetHeight.text.toString().toIntOrNull()
        targetHeight = if (value != null && value > 0) value else 0
    }

    /** 处理来自"分享到本 App"的图片 */
    private fun handleSharedIntent() {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            @Suppress("DEPRECATION")
            val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM) as? android.net.Uri
            uri?.let { loadAndProcess(it) }
        }
    }

    private fun loadAndProcess(uri: android.net.Uri) {
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                ImageLoader.loadFromUri(this@MainActivity, uri)
            }
            if (bmp.isRecycled || bmp.width <= 0 || bmp.height <= 0) {
                Toast.makeText(this@MainActivity, "无法加载图片", Toast.LENGTH_SHORT).show()
                binding.progress.visibility = View.GONE
                return@launch
            }
            // 更新原图尺寸显示
            srcWidth = bmp.width
            srcHeight = bmp.height
            binding.tvSize.text = "原图尺寸: ${srcWidth} × ${srcHeight}"
            if (binding.etTargetHeight.text.isNullOrBlank()) {
                binding.etTargetHeight.hint = "默认 ${srcHeight}（=原高+延展）"
            }
            // 回收旧原图
            originalBitmap?.recycleSafe()
            originalBitmap = bmp
            // 原图预览（bmp 后续会被 originalBitmap 持有，不在此 recycle）
            binding.imgOriginal.setImageBitmap(bmp)
            binding.btnSave.isEnabled = false
            processImage()
        }
    }

    /** 参数变化后重新处理（防抖） */
    private var reprocessJob: Job? = null
    private fun reprocess() {
        val src = originalBitmap ?: return
        if (src.isRecycled) return
        reprocessJob?.cancel()
        reprocessJob = lifecycleScope.launch {
            delay(150)
            ensureActive() // 取消后不再执行
            processImage()
        }
    }

    private suspend fun processImage() {
        val src = originalBitmap
        if (src == null || src.isRecycled) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "图片不可用", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (src.width <= 0 || src.height <= 0) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "图片尺寸异常", Toast.LENGTH_SHORT).show()
            }
            return
        }

        withContext(Dispatchers.Main) {
            binding.progress.visibility = View.VISIBLE
        }

        val result = try {
            withContext(Dispatchers.Default) {
                ensureActive()

                val screenW = resources.displayMetrics.widthPixels
                val screenH = resources.displayMetrics.heightPixels
                // refH 为最终输出高度：延展区 + 原图
                val refH = if (targetHeight > 0) targetHeight else screenH

                // 限制处理宽度，防止 OOM（保持原图宽高比）
                val maxProcessW = screenW * 2
                val working = if (src.width > maxProcessW) {
                    val scale = maxProcessW.toFloat() / src.width
                    val newW = maxProcessW
                    val newH = (src.height * scale).toInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(src, newW, newH, true)
                } else {
                    src
                }

                // 延展高度：按 refH 与 working 高度的比例计算
                val extendH = ((refH - working.height).coerceAtLeast(0)).coerceAtMost(refH)

                // 模糊半径不再强制压到 6，改用与图高相关的合理上限
                val effectiveBlur = blurRadius.coerceIn(1, 60)

                val output = if (topOnly) {
                    WallpaperExtend.extendTop(
                        src = working,
                        extendH = extendH,
                        featherH = featherWidth,
                        blurRadius = effectiveBlur
                    )
                } else {
                    WallpaperExtend.extendBottom(
                        src = working,
                        extendH = extendH,
                        featherH = featherWidth,
                        blurRadius = effectiveBlur
                    )
                }

                if (working != src) {
                    working.recycle()
                }
                output
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "处理失败: ${e.message}", Toast.LENGTH_LONG).show()
                binding.progress.visibility = View.GONE
            }
            return
        }

        withContext(Dispatchers.Main) {
            binding.imgResult.setImageBitmap(result)
            // 回收上一帧处理结果，再保存新结果
            processedBitmap?.recycleSafe()
            processedBitmap = result
            binding.btnSave.isEnabled = true
            binding.progress.visibility = View.GONE
        }
    }

    private fun checkPermissionAndSave() {
        // Android 10+ 用 MediaStore，不需要 WRITE_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveCurrent()
        } else {
            requestPermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
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
                if (ok) {
                    Toast.makeText(this@MainActivity, "保存成功，已加入相册", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "保存失败：$errorMsg",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        originalBitmap?.recycleSafe()
        originalBitmap = null
        processedBitmap?.recycleSafe()
        processedBitmap = null
    }

    private fun Bitmap?.recycleSafe() {
        if (this != null && !isRecycled) {
            try {
                recycle()
            } catch (_: Exception) {
            }
        }
    }

    // 简单 SeekBar 监听基类
    abstract class SimpleSeekBar : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }
}
