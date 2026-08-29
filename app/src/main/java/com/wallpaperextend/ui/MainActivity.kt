package com.wallpaperextend.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wallpaperextend.BuildConfig
import com.wallpaperextend.R
import com.wallpaperextend.WallpaperConfig
import com.wallpaperextend.processor.ExtendStrategy
import com.wallpaperextend.processor.NPU.NpuExtendEngine
import com.wallpaperextend.processor.RenderEffectWallpaperProcessor
import com.wallpaperextend.processor.WallpaperExtendEngine
import com.wallpaperextend.saver.MediaStoreSaver
import com.wallpaperextend.databinding.ActivityMainBinding
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var originalBitmap: Bitmap? = null
    private var processedBitmap: Bitmap? = null
    private var engine: ExtendStrategy? = null
    private var targetHeight = 0
    private var reprocessJob: Job? = null
    private var shouldReprocess = false

    companion object {
        private const val REQ_PICK = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnPick.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            startActivityForResult(intent, REQ_PICK)
        }

        binding.seekHeight.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                targetHeight = progress.coerceAtLeast(500)
                binding.tvHeight.text = "目标高度: $targetHeight"
                if (fromUser && originalBitmap != null) {
                    reprocess()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnProcess.setOnClickListener {
            originalBitmap?.let { processImage(it) }
        }

        binding.btnSave.setOnClickListener {
            processedBitmap?.let { bmp ->
                val uri = MediaStoreSaver.save(this, bmp, "WallpaperExtend_${System.currentTimeMillis()}")
                if (uri != null) {
                    Toast.makeText(this, "已保存到相册", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
                }
            }
        }

        targetHeight = resources.displayMetrics.heightPixels
        binding.seekHeight.progress = targetHeight
    }

    private fun getEngine(): ExtendStrategy {
        if (engine == null) {
            engine = if (BuildConfig.USE_NPU) {
                NpuExtendEngine().apply { create(this@MainActivity) }
            } else {
                WallpaperExtendEngine(RenderEffectWallpaperProcessor())
            }
        }
        return engine!!
    }

    private fun reprocess() {
        val src = originalBitmap ?: return
        shouldReprocess = true
        scheduleReprocess()
    }

    private fun scheduleReprocess() {
        if (reprocessJob?.isActive == true) {
            return
        }
        reprocessJob = lifecycleScope.launch {
            delay(300)
            while (shouldReprocess) {
                shouldReprocess = false
                try {
                    processImage(originalBitmap!!)
                } catch (e: Exception) {
                    if (e is CancellationException) {
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
        withContext(Dispatchers.Main) {
            binding.progress.visibility = android.view.View.VISIBLE
        }
        try {
            val dm = resources.displayMetrics
            val result = withContext(Dispatchers.IO + NonCancellable) {
                getEngine().process(
                    context = this@MainActivity,
                    src = src,
                    targetW = dm.widthPixels,
                    targetH = targetHeight,
                    config = currentConfig()
                )
            }
            if (processedBitmap != null && !processedBitmap!!.isRecycled()) {
                processedBitmap?.recycle()
            }
            processedBitmap = result
            withContext(Dispatchers.Main) {
                if (!result.isRecycled()) {
                    binding.imgResult.setImageBitmap(result)
                }
                binding.btnSave.isEnabled = true
            }
        } catch (e: Exception) {
            if (e !is CancellationException) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "处理失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
                e.printStackTrace()
            }
        } finally {
            withContext(Dispatchers.Main) {
                binding.progress.visibility = android.view.View.GONE
            }
        }
    }

    private fun currentConfig() = WallpaperConfig(
        extendTop = true,
        extendBottom = false,
        extendLeft = false,
        extendRight = false,
        blurRadius = binding.seekBlur.progress,
        scaleRatio = 1.0f
    )

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            data?.data?.let { uri: Uri ->
                originalBitmap = BitmapFactory.decodeStream(contentResolver.openInputStream(uri))
                binding.imgOriginal.setImageBitmap(originalBitmap)
                binding.btnProcess.isEnabled = true
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
}
