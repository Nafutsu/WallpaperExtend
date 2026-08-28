package com.wallpaperextend.ui

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var originalBitmap: Bitmap? = null
    private var processedBitmap: Bitmap? = null

    // 可调参数
    private var blurRadius = 30
    private var extendRatio = 0.25f
    private var featherWidth = 120
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

        // 参数调节：模糊半径
        binding.seekBlur.setOnSeekBarChangeListener(object : SimpleSeekBar() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                blurRadius = progress.coerceAtLeast(1)
                binding.tvBlur.text = "模糊半径: $blurRadius"
                if (fromUser) reprocess()
            }
        })
        binding.seekBlur.progress = blurRadius

        // 延展比例
        binding.seekExtend.setOnSeekBarChangeListener(object : SimpleSeekBar() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                extendRatio = progress / 100f
                binding.tvExtend.text = "延展比例: ${(extendRatio * 100).toInt()}%"
                if (fromUser) reprocess()
            }
        })
        binding.seekExtend.progress = (extendRatio * 100).toInt()

        // 羽化宽度
        binding.seekFeather.setOnSeekBarChangeListener(object : SimpleSeekBar() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                featherWidth = progress
                binding.tvFeather.text = "羽化宽度: $featherWidth"
                if (fromUser) reprocess()
            }
        })
        binding.seekFeather.progress = featherWidth
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
            // 更新原图尺寸显示
            srcWidth = bmp.width
            srcHeight = bmp.height
            binding.tvSize.text = "原图尺寸: ${srcWidth} × ${srcHeight}"
            if (binding.etTargetHeight.text.isNullOrBlank()) {
                binding.etTargetHeight.hint = "默认 ${srcHeight}（=原高+延展）"
            }
            // 回收旧原图（新图替换）
            originalBitmap?.recycleSafe()
            originalBitmap = bmp
            // 原图预览用缩放副本，避免 UI 持超大 Bitmap
            binding.imgOriginal.setImageBitmap(bmp)
            binding.btnSave.isEnabled = false
            processImage()
        }
    }

    /** 参数变化后重新处理（防抖） */
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
        val src = originalBitmap
        if (src == null) {
            Toast.makeText(this, "图片为空", Toast.LENGTH_SHORT).show()
            return
        }
        if (src.isRecycled || src.width <= 0 || src.height <= 0) {
            Toast.makeText(this, "图片不可用", Toast.LENGTH_SHORT).show()
            return
        }
        binding.progress.visibility = View.VISIBLE
        val result = try {
            withContext(Dispatchers.Default) {
                ensureActive()

                val screenW = resources.displayMetrics.widthPixels
                val screenH = resources.displayMetrics.heightPixels
                val refH = if (targetHeight > 0) targetHeight else screenH

                // 限制处理宽度，防止 OOM
                val maxProcessW = screenW * 2
                val working = if (src.width > maxProcessW) {
                    val scale = maxProcessW.toFloat() / src.width
                    val newW = maxProcessW
                    val newH = (src.height * scale).toInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(src, newW, newH, true)
                } else {
                    src
                }

                val extendH = (refH * extendRatio).toInt()

                val output = if (topOnly) {
                    WallpaperExtend.extendTop(
                        src = working,
                        extendH = extendH,
                        featherH = featherWidth,
                        blurRadius = blurRadius.coerceAtMost(6)
                    )
                } else {
                    WallpaperExtend.extendBottom(
                        src = working,
                        extendH = extendH,
                        featherH = featherWidth,
                        blurRadius = blurRadius.coerceAtMost(6)
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

        binding.imgResult.setImageBitmap(result)
        processedBitmap = result
        binding.btnSave.isEnabled = true
        binding.progress.visibility = View.GONE
    }

    private fun checkPermissionAndSave() {
        // Android 13+ 不需要 WRITE_EXTERNAL_STORAGE；Android 12- 也不需要（用 MediaStore）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveCurrent()
        } else {
            requestPermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun saveCurrent() {
        val bmp = processedBitmap ?: return
        // 禁用按钮防止重复点击
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
