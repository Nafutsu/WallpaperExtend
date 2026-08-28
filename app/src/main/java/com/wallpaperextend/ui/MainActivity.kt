package com.wallpaperextend.ui

import android.content.Intent
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var engine: WallpaperExtendEngine
    private var originalBitmap: Bitmap? = null
    private var processedBitmap: Bitmap? = null

    // ★ 用 Activity Result API 替代 startActivityForResult
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { loadImage(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        engine = WallpaperExtendEngine.create(this)
        setupUI()
    }

    private fun setupUI() {
        binding.btnPickImage.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        binding.btnProcess.setOnClickListener {
            processImage()
        }

        binding.btnSave.setOnClickListener {
            saveImage()
        }

        // 示例：模糊强度 1-25
        binding.seekBlur.max = 25
        binding.seekBlur.progress = 20

        // 延展比例 0-60%
        binding.seekExtend.max = 60
        binding.seekExtend.progress = 35

        // 羽化宽度 50-300
        binding.seekFeather.max = 300
        binding.seekFeather.progress = 200
    }

    private fun loadImage(uri: Uri) {
        try {
            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            binding.imagePreview.setImageBitmap(originalBitmap)
            binding.btnProcess.isEnabled = true
            processedBitmap = null
            binding.btnSave.isEnabled = false
        } catch (e: Exception) {
            Toast.makeText(this, "加载图片失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processImage() {
        val src = originalBitmap ?: return
        binding.progress.visibility = View.VISIBLE
        binding.btnProcess.isEnabled = false

        lifecycleScope.launch {
            try {
                val dm = resources.displayMetrics
                val config = WallpaperConfig(
                    blurRadius = binding.seekBlur.progress.toFloat().coerceAtLeast(1f),
                    extendRatio = binding.seekExtend.progress / 100f,
                    featherWidth = binding.seekFeather.progress,
                    saturationBoost = 1.1f,
                    brightnessOffset = 0.05f,
                    overlayStrength = 0.08f
                )
                processedBitmap = withContext(Dispatchers.IO) {
                    engine.process(
                        context = this@MainActivity,
                        src = src,
                        targetW = dm.widthPixels,
                        targetH = dm.heightPixels,
                        config = config
                    )
                }
                binding.imagePreview.setImageBitmap(processedBitmap)
                binding.btnSave.isEnabled = true
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "处理失败: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.progress.visibility = View.GONE
                binding.btnProcess.isEnabled = true
            }
        }
    }

    private fun saveImage() {
        processedBitmap?.let { bmp ->
            // TODO: 实现保存逻辑（MediaStore / 文件）
            Toast.makeText(this, "保存功能待实现", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.release()
    }
}
