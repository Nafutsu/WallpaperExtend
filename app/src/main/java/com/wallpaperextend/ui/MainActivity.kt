package com.wallpaperextend.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wallpaperextend.databinding.ActivityMainBinding
import com.wallpaperextend.processor.WallpaperProcessor
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

    // ====== 参数 ======
    private var blurRadius = 32
    private var extendRatio = 0.37f      // 最大延展高度占比上限；实际延展量 = 屏幕高 - 原图高
    private var featherWidth = 100
    @Suppress("unused")
    private var topOnly = true           // iOS 风格恒为 true，保留字段
    private var targetHeight = 0        // 0 = 自动取屏幕高度（推荐，防白边 + 底部对齐准确）

    // 双指缩放：scale 越大，原图缩得越小，顶部延展区越高（模拟 iOS 捏合）
    private var userScale = 1.0f        // 1.0 = 铺满宽度；>1 表示用户缩小
    private val minScale = 1.0f
    private val maxScale = 1.6f

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val uri = result.data!!.data ?: return@registerForActivityResult
            loadAndProcess(uri)
        }
    }

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            saveCurrent()
        } else {
            Toast.makeText(this, "需要存储权限才能保存图片", Toast.LENGTH_SHORT).show()
        }
    }

    // 双指缩放检测（作用在结果预览图上）
    private lateinit var scaleDetector: ScaleGestureDetector
    private val onScaleListener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val newScale = (userScale * detector.scaleFactor).coerceIn(minScale, maxScale)
            if (kotlin.math.abs(newScale - userScale) > 0.01f) {
                userScale = newScale
                // 把缩放量映射到 extendRatio，让延展高度随缩放变化
                // scale 1.0 -> ratio ~0；scale 1.6 -> ratio ~0.37
                extendRatio = ((userScale - minScale) / (maxScale - minScale) * 0.37f)
                    .coerceIn(0f, 0.6f)
                binding.tvExtend.text = "延展比例: ${(extendRatio * 100).toInt()}%"
                // 滑块与手势双向同步
                binding.seekExtend.progress = (extendRatio * 100).toInt()
                reprocess()
            }
            return true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        scaleDetector = ScaleGestureDetector(this, onScaleListener)
        // 把缩放手势挂到预览 ImageView 上（需布局里 imgResult 允许缩放）
        binding.imgResult.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            true
        }

        setupUI()
        handleSharedIntent()
    }

    private fun setupUI() {
        binding.btnPick.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK).apply {
                type = "image/*"
                action = Intent.ACTION_GET_CONTENT
            }
            pickImage.launch(intent)
        }
        binding.btnSave.setOnClickListener {
            if (processedBitmap == null) {
                Toast.makeText(this, "请先选择并生成壁纸", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            checkPermissionAndSave()
        }

        // topOnly 开关：iOS 风格固定仅顶部，禁用即可
        binding.cbTopOnly.isEnabled = false
        binding.cbTopOnly.isChecked = true

        binding.etTargetHeight.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                updateTargetHeight()
                reprocess()
            }
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
                // 手动拖动滑块时，同步更新 userScale，保持双向一致
                extendRatio = (progress / 100f).coerceIn(0f, 0.6f)
                userScale = (extendRatio / 0.37f).coerceIn(0f, 1f) * (maxScale - minScale) + minScale
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
    }

    private fun updateTargetHeight() {
        val value = binding.etTargetHeight.text.toString().toIntOrNull()
        // 0 或留空 = 自动取屏幕高度（推荐：底部对齐 + 防白边逻辑都依赖此值准确）
        targetHeight = if (value != null && value > 0) value else 0
    }

    private fun handleSharedIntent() {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
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
            binding.tvSize.text = "原图尺寸: ${bmp.width} × ${bmp.height}"
            if (binding.etTargetHeight.text.isNullOrBlank()) {
                binding.etTargetHeight.hint = "默认 = 屏幕高度（iOS 风格底部对齐）"
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
        val result = withContext(Dispatchers.Default) {
            val dm = resources.displayMetrics
            val screenW = dm.widthPixels
            // targetHeight = 0 时自动取屏幕**精确**高度（含状态栏/导航栏外的真实像素），
            // 用 ceil 避免取整导致底部 1px 露底 → 白边 bug
            val refH = targetHeight.takeIf { it > 0 } ?: dm.heightPixels
            WallpaperProcessor.process(
                src = src,
                targetW = screenW,
                targetH = refH,
                config = WallpaperProcessor.Config(
                    blurRadius = blurRadius,
                    extendRatio = extendRatio,
                    featherWidth = featherWidth,
                    topOnly = true // 固定仅顶部延展，对齐 iOS
                )
            )
        }
        processedBitmap?.recycleSafe()
        processedBitmap = result
        binding.imgResult.setImageBitmap(result)
        binding.btnSave.isEnabled = true
        binding.progress.visibility = View.GONE
    }

    private fun checkPermissionAndSave() {
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        if (!needsPermission) {
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
                    Toast.makeText(this@MainActivity, "保存失败：$errorMsg", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun shareImage(path: String) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "$packageName.fileprovider", java.io.File(path)
        )
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "分享延展壁纸"))
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

    abstract class SimpleSeekBar : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }
}
