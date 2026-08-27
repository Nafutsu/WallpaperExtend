package com.wallpaperextend.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
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
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var originalBitmap: Bitmap? = null
    private var processedBitmap: Bitmap? = null
    // 可调参数
    private var blurRadius = 30
    private var extendRatio = 0.25f
    private var featherWidth = 80

    // 选图回调（兼容 Android 13+）
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
                binding.tvExtend.text = "延展比例: ${extendRatio * 100.roundToInt()}%"
                if (fromUser) reprocess()
            }
        })
        binding.seekExtend.progress = (extendRatio * 100).roundToInt()
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

    /** 处理来自"分享到本 App"的图片 */
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
            originalBitmap = bmp
            binding.imgOriginal.setImageBitmap(bmp)
            binding.btnSave.isEnabled = false
            processImage()
        }
    }

        /** 参数变化后重新处理（防抖） */
    private var reprocessJob: kotlinx.coroutines.Job? = null
    private fun reprocess() {
        if (originalBitmap == null) return
        reprocessJob?.cancel()
        reprocessJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(150)
            processImage()
        }
    }

    private suspend fun processImage() {
        val src = originalBitmap ?: return
        binding.progress.visibility = View.VISIBLE
        val result = withContext(Dispatchers.Default) {
            val screenW = resources.displayMetrics.widthPixels
            val screenH = resources.displayMetrics.heightPixels
            WallpaperProcessor.process(
                src,
                screenW,
                screenH,
                WallpaperProcessor.Config(blurRadius, extendRatio, featherWidth)
            )
        }
        processedBitmap?.recycle()
        processedBitmap = result
        binding.imgResult.setImageBitmap(result)
        binding.btnSave.isEnabled = true
        binding.progress.visibility = View.GONE
    }

    private fun checkPermissionAndSave() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            saveCurrent()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveCurrent()
        } else {
            requestPermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun saveCurrent() {
        val bmp = processedBitmap ?: return
        lifecycleScope.launch {
            val path = withContext(Dispatchers.IO) {
                ImageSaver.saveToGallery(this@MainActivity, bmp)
            }
            if (path != null) {
                binding.btnShare.visibility = View.VISIBLE
                binding.btnShare.setOnClickListener { shareImage(path) }
            }
        }
    }

    private fun shareImage(path: String) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            java.io.File(path)
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
        originalBitmap?.recycle()
        processedBitmap?.recycle()
    }

    // 简单 SeekBar 监听基类
    abstract class SimpleSeekBar : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }
}
