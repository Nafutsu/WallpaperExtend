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
    private var blurRadius = 30
    private var extendRatio = 0.25f
    private var featherWidth = 120
    private var topOnly = true
    private var targetHeight = 0
    private var srcWidth = 0
    private var srcHeight = 0

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
            pickImage.launch(intent)
        }
        binding.btnSave.setOnClickListener {
            if (processedBitmap == null) {
                Toast.makeText(this, "请先选择并生成壁纸", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            checkPermissionAndSave()
        }
        binding.cbTopOnly.setOnCheckedChangeListener { _, checked ->
            topOnly = checked
            reprocess()
        }
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
                extendRatio = progress / 100f
                binding.tvExtend.text = "延展比例: ${(extendRatio * 100).toInt()}%"
                if (fromUser) reprocess()
            }
        })
        binding.seekExtend.progress = (extendRatio * 100).toInt()
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
            srcWidth = bmp.width
            srcHeight = bmp.height
            binding.tvSize.text = "原图尺寸: ${srcWidth} × ${srcHeight}"
            if (binding.etTargetHeight.text.isNullOrBlank()) {
                binding.etTargetHeight.hint = "默认 ${srcHeight}（=原高+延展）"
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
            val screenW = resources.displayMetrics.widthPixels
            val refH = targetHeight.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
            WallpaperProcessor.process(
                src = src,
                targetW = screenW,
                targetH = refH,
                config = WallpaperProcessor.Config(
                    blurRadius = blurRadius,
                    extendRatio = extendRatio,
                    featherWidth = featherWidth,
                    topOnly = topOnly
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
            try { recycle() } catch (_: Exception) {}
        }
    }

    abstract class SimpleSeekBar : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }
}
