package com.wallpaperextend.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.wallpaperextend.databinding.ActivityMainBinding
import com.wallpaperextend.processor.WallpaperProcessor
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var sourceBitmap: Bitmap? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var blurRadius = 28
    private var extendPercent = 25
    private var featherWidth = 60

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        pickImage()
    }

    private fun setupUI() {
        binding.btnPick.setOnClickListener { pickImage() }
        binding.btnProcess.setOnClickListener { processImage() }

        binding.seekBlur.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                blurRadius = p.coerceIn(1, 60)
                binding.tvBlur.text = "模糊: $blurRadius"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        binding.seekBlur.progress = blurRadius

        binding.seekExtend.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                extendPercent = p.coerceIn(5, 80)
                binding.tvExtend.text = "延展: $extendPercent%"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        binding.seekExtend.progress = extendPercent

        binding.seekFeather.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                featherWidth = p.coerceIn(8, 200)
                binding.tvFeather.text = "羽化: $featherWidth"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        binding.seekFeather.progress = featherWidth
    }

    private val pickLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            try {
                contentResolver.openInputStream(it)?.use { input ->
                    sourceBitmap = BitmapFactory.decodeStream(input)
                }
                binding.imgPreview.setImageBitmap(sourceBitmap)
            } catch (e: Exception) {
                Toast.makeText(this, "图片加载失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun pickImage() {
        pickLauncher.launch("image/*")
    }

    private fun processImage() {
        val src = sourceBitmap
        if (src == null || src.isRecycled) {
            Toast.makeText(this, "请先选择图片", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnProcess.isEnabled = false
        binding.tvStatus.text = "处理中..."

        scope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    val targetW = src.width
                    val extendH = (src.height * extendPercent / 100).coerceAtLeast(0)
                    val targetH = extendH + src.height
                    val extendRatio = if (targetH > 0) extendH.toFloat() / targetH else 0.25f

                    val config = WallpaperProcessor.Config(
                        blurRadius = blurRadius,
                        extendRatio = extendRatio,
                        featherWidth = featherWidth,
                        topOnly = true
                    )

                    WallpaperProcessor.process(src, targetW, targetH, config)
                }
                binding.imgPreview.setImageBitmap(result)
                binding.tvStatus.text = "完成"
            } catch (e: Exception) {
                binding.tvStatus.text = "失败: ${e.message}"
                Toast.makeText(this@MainActivity, "处理失败: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.btnProcess.isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        sourceBitmap?.recycle()
    }
}
