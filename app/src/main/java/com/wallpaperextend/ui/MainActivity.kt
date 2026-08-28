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
    private var blurRadius = 40
    private var extendRatio = 0.25f
    private var targetHeight = 0

    // 双指缩放：scale 越大，原图缩得越小，顶部延展区越高
    private var userScale = 1.0f
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

    // 双指缩放检测
    private lateinit var scaleDetector: ScaleGestureDetector
    private val onScaleListener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val newScale = (userScale * detector.scaleFactor).coerceIn(minScale, maxScale)
            if (kotlin.math.abs(newScale - userScale) > 0.01f) {
                userScale = newScale
                // scale 1.0 -> ratio ~0；scale 1.6 -> ratio ~0.37
                extendRatio = ((userScale - minScale) / (maxScale - minScale) * 0.37f)
                    .coerceIn(0f, 0.6f)
                binding.tvExtend.text = "延展比例: ${(extendRatio * 100).toInt()}%"
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

        // 目标高度输入：失去焦点时更新
        binding.etTargetHeight.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                updateTargetHeight()
                reprocess()
            }
        }

        // 模糊半径滑块
        binding.seekBlur.max = 50
        binding.seekBlur.progress = blurRadius
        binding.seekBlur.setOnSeekBarChangeListener(object : SimpleSeekBar() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                blurRadius = progress.coerceAtLeast(1)
                binding.tvBlur.text = "模糊半径: $blurRadius"
                if (fromUser) reprocess()
            }
        })

        // 延展比例滑块
        binding.seekExtend.max = 60
        binding.seekExtend.progress = (extendRatio * 100).toInt()
        binding.seekExtend.setOnSeekBarChangeListener(object : SimpleSeekBar() {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                extendRatio = (progress / 100f).coerceIn(0f, 0.6f)
                userScale = (extendRatio / 0.37f).coerceIn(0f, 1f) * (maxScale - minScale) + minScale
                binding.tvExtend.text = "延展比例: ${(extendRatio * 100).toInt()}%"
                if (fromUser) reprocess()
            }
        })

        // 羽化宽度滑块（保留UI但不再传给processor，可后续移除）
        binding.seekFeather.progress = 0
        binding.seekFeather.isEnabled = false
        binding.tvFeather.text = "羽化宽度: 已内置渐变"
    }

    private fun updateTargetHeight() {
        val value = binding.etTargetHeight.text.toString().toIntOrNull()
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
        // 确保目标高度是最新的
        updateTargetHeight()
        binding.progress.visibility = View.VISIBLE

        val result = withContext(Dispatchers.Default) {
            val dm = resources.displayMetrics
            val screenW = dm.widthPixels
            val refH = targetHeight.takeIf { it > 0 } ?: dm.heightPixels

            WallpaperProcessor.process(
                original = src,
                targetW = screenW,
                targetH = refH,
                extendRatio = extendRatio,
                blurRadius = blurRadius
            )
        }

        if (result != null) {
            processedBitmap?.recycleSafe()
            processedBitmap = result
            binding.imgResult.setImageBitmap(result)
            binding.btnSave.isEnabled = true
        }

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
                        this@MainActivity,
                        bmp,
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
