package com.wallpaperextend.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.wallpaperextend.processor.WallpaperProcessor
import com.wallpaperextend.util.ImageLoader
import com.wallpaperextend.util.ImageSaver
import com.wallpaperextend.util.WallpaperSetter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.AppBar
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixThemeColorScheme
import top.yukonga.miuix.kmp.utils.getWindowSize
import java.time.LocalDate

sealed class Screen {
    object Home : Screen()
    object About : Screen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        setContent {
            MiuixTheme(
                colorScheme = MiuixThemeColorScheme(
                    primaryColor = Color(0xFF0A84FF),
                    backgroundColor = Color(0xFF1C1C1E),
                    surfaceColor = Color(0xFF2C2C2E),
                    secondaryTextColor = Color(0xFF8E8E93)
                )
            ) {
                WallpaperExtendApp()
            }
        }
    }
}

@Composable
fun WallpaperExtendApp() {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }

    AnimatedContent(
        targetState = currentScreen,
        transitionSpec = {
            if (targetState is Screen.About) {
                slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it } + fadeOut()
            } else {
                slideInHorizontally { -it } + fadeIn() togetherWith slideOutHorizontally { it } + fadeOut()
            }
        },
        label = "screenTransition"
    ) { screen ->
        when (screen) {
            is Screen.Home -> HomeScreen(onNavigateToAbout = { currentScreen = Screen.About })
            is Screen.About -> AboutScreen(onBack = { currentScreen = Screen.Home })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onNavigateToAbout: () -> Unit) {
    val context = LocalContext.current
    val viewModel: WallpaperViewModel = viewModel()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val uri = result.data!!.data
            uri?.let { viewModel.loadImage(context, it) }
        }
    }

    val shareLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.saveImage(context)
        } else {
            Toast.makeText(context, "需要存储权限才能保存图片", Toast.LENGTH_SHORT).show()
        }
    }

    val sharedUri = (context as? Activity)?.intent?.let { intent ->
        if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        } else null
    }

    LaunchedEffect(sharedUri) {
        sharedUri?.let { viewModel.loadImage(context, it) }
    }

    Scaffold(
        topBar = {
            AppBar(
                title = "壁纸延展",
                canNavigateBack = false,
                navigateUp = {},
                actions = {
                    IconButton(onClick = onNavigateToAbout) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "关于",
                            tint = Color.White
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Surface(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState()),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (viewModel.originalBitmap == null) {
                    item {
                        EmptyState {
                            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                                type = "image/*"
                            }
                            pickImageLauncher.launch(intent)
                        }
                    }
                }

                if (viewModel.originalBitmap != null) {
                    item {
                        ImageInfoCard(
                            originalWidth = viewModel.srcWidth,
                            originalHeight = viewModel.srcHeight
                        )
                    }
                }

                item {
                    ParametersCard(
                        blurRadius = viewModel.blurRadius,
                        extendRatio = viewModel.extendRatio,
                        featherWidth = viewModel.featherWidth,
                        topOnly = viewModel.topOnly,
                        onBlurRadiusChange = { viewModel.blurRadius = it },
                        onExtendRatioChange = { viewModel.extendRatio = it },
                        onFeatherWidthChange = { viewModel.featherWidth = it },
                        onTopOnlyChange = { viewModel.topOnly = it },
                        onReprocess = { viewModel.reprocess(context) }
                    )
                }

                if (viewModel.originalBitmap != null) {
                    item {
                        OriginalPreviewCard(bitmap = viewModel.originalBitmap)
                    }

                    item {
                        ResultPreviewCard(
                            bitmap = viewModel.processedBitmap,
                            isProcessing = viewModel.isProcessing
                        )
                    }

                    item {
                        ActionButtons(
                            canSave = viewModel.processedBitmap != null && !viewModel.isProcessing,
                            onPickImage = {
                                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                                    type = "image/*"
                                }
                                pickImageLauncher.launch(intent)
                            },
                            onSave = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    viewModel.saveImage(context)
                                } else {
                                    requestPermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                }
                            }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
fun EmptyState(onPickImage: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = Icons.Default.PhotoLibrary,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = Color(0xFF0A84FF)
        )
        Text(
            text = "选择一张图片开始",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = "支持从相册选择或从其他应用分享",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF8E8E93),
            textAlign = TextAlign.Center
        )
        androidx.compose.material3.Button(
            onClick = onPickImage,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text("选择图片", fontSize = 16.sp)
        }
    }
}

@Composable
fun ImageInfoCard(originalWidth: Int, originalHeight: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF2C2C2E)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "图片信息",
                style = MaterialTheme.typography.titleSmall,
                color = Color(0xFF8E8E93),
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "原图尺寸: ${originalWidth} x ${originalHeight}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White
            )
        }
    }
}

@Composable
fun ParametersCard(
    blurRadius: Int,
    extendRatio: Float,
    featherWidth: Int,
    topOnly: Boolean,
    onBlurRadiusChange: (Int) -> Unit,
    onExtendRatioChange: (Float) -> Unit,
    onFeatherWidthChange: (Int) -> Unit,
    onTopOnlyChange: (Boolean) -> Unit,
    onReprocess: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF2C2C2E)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = "参数调节",
                style = MaterialTheme.typography.titleSmall,
                color = Color(0xFF8E8E93),
                fontWeight = FontWeight.Medium
            )

            ParameterSlider(
                label = "模糊半径",
                value = blurRadius.toFloat(),
                valueRange = 1f..60f,
                onValueChange = {
                    onBlurRadiusChange(it.toInt())
                    onReprocess()
                },
                valueText = "$blurRadius"
            )

            ParameterSlider(
                label = "延展比例",
                value = extendRatio * 100,
                valueRange = 0f..60f,
                onValueChange = {
                    onExtendRatioChange(it / 100f)
                    onReprocess()
                },
                valueText = "${(extendRatio * 100).toInt()}%"
            )

            ParameterSlider(
                label = "羽化宽度",
                value = featherWidth.toFloat(),
                valueRange = 0f..300f,
                onValueChange = {
                    onFeatherWidthChange(it.toInt())
                    onReprocess()
                },
                valueText = "$featherWidth"
            )

            SwitchPreference(
                title = "仅顶部延展",
                summary = "底部保留原图，更接近 iOS 17 效果",
                checked = topOnly,
                onCheckedChange = {
                    onTopOnlyChange(it)
                    onReprocess()
                }
            )
        }
    }
}

@Composable
fun ParameterSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    valueText: String
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF0A84FF)
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF0A84FF),
                activeTrackColor = Color(0xFF0A84FF),
                inactiveTrackColor = Color(0xFF3A3A3C)
            )
        )
    }
}

@Composable
fun OriginalPreviewCard(bitmap: Bitmap?) {
    if (bitmap == null) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF2C2C2E)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "原图预览",
                style = MaterialTheme.typography.titleSmall,
                color = Color(0xFF8E8E93),
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
            ) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "原图",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}

@Composable
fun ResultPreviewCard(bitmap: Bitmap?, isProcessing: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF2C2C2E)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "延展结果",
                style = MaterialTheme.typography.titleSmall,
                color = Color(0xFF8E8E93),
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(color = Color(0xFF0A84FF))
                } else if (bitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "延展结果",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(
                        text = "处理完成后在此显示",
                        color = Color(0xFF8E8E93),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
fun ActionButtons(canSave: Boolean, onPickImage: () -> Unit, onSave: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        androidx.compose.material3.Button(
            onClick = onPickImage,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PhotoLibrary,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("重新选择图片", fontSize = 16.sp)
        }

        androidx.compose.material3.Button(
            onClick = onSave,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            enabled = canSave
        ) {
            Icon(
                imageVector = Icons.Default.Save,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("保存到相册", fontSize = 16.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1C1C1E),
                    titleContentColor = Color.White
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                AppInfoCard()
            }

            item {
                DevelopersCard(context = context)
            }

            item {
                LicenseCard()
            }

            item {
                SpecialThanksCard(context = context)
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun AppInfoCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF2C2C2E)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF0A84FF)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoLibrary,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = Color.White
                )
            }
            Text(
                text = "壁纸延展",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "版本 2.0.0",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF8E8E93)
            )
            Text(
                text = "iOS 17 风格壁纸延展工具",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF6E6E73),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun DevelopersCard(context: Context) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF2C2C2E)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "开发者",
                style = MaterialTheme.typography.titleSmall,
                color = Color(0xFF8E8E93),
                fontWeight = FontWeight.Medium
            )

            DeveloperRow(
                context = context,
                name = "海葉なっふ",
                github = "Nafutsu",
                role = "核心开发"
            )

            DeveloperRow(
                context = context,
                name = "もうや",
                github = "mouya-q",
                role = "项目发起 / UI 重构"
            )
        }
    }
}

@Composable
fun DeveloperRow(context: Context, name: String, github: String, role: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://github.com/$github")
                }
                context.startActivity(intent)
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data("https://github.com/$github.png")
                .crossfade(true)
                .build(),
            contentDescription = name,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape),
            placeholder = androidx.compose.ui.res.painterResource(android.R.drawable.ic_menu_gallery),
            error = androidx.compose.ui.res.painterResource(android.R.drawable.ic_menu_gallery)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = role,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF6E6E73)
            )
        }
    }
}

@Composable
fun LicenseCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF2C2C2E)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "开源许可",
                style = MaterialTheme.typography.titleSmall,
                color = Color(0xFF8E8E93),
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "本项目基于 Apache 2.0 协议开源",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White
            )
            Text(
                text = "GitHub: github.com/mouya-q/WallpaperExtend",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF0A84FF)
            )
        }
    }
}

@Composable
fun SpecialThanksCard(context: Context) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF2C2C2E)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "特别感谢",
                style = MaterialTheme.typography.titleSmall,
                color = Color(0xFF8E8E93),
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "MIUIX - Yukonga",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                modifier = Modifier.clickable {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("https://github.com/Yukonga/miuix")
                    }
                    context.startActivity(intent)
                }
            )
            Text(
                text = "AndroidLiquidGlass - Kyant",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                modifier = Modifier.clickable {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("https://github.com/Kyant0/AndroidLiquidGlass")
                    }
                    context.startActivity(intent)
                }
            )
        }
    }
}

class WallpaperViewModel : androidx.lifecycle.ViewModel() {
    var originalBitmap by mutableStateOf<Bitmap?>(null)
    var processedBitmap by mutableStateOf<Bitmap?>(null)
    var isProcessing by mutableStateOf(false)
    var srcWidth by mutableStateOf(0)
    var srcHeight by mutableStateOf(0)

    var blurRadius by mutableStateOf(30)
    var extendRatio by mutableStateOf(0.25f)
    var featherWidth by mutableStateOf(120)
    var topOnly by mutableStateOf(true)

    private var reprocessJob: Job? = null
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()
    )

    fun loadImage(context: Context, uri: Uri) {
        scope.launch {
            isProcessing = true
            try {
                val bmp = withContext(Dispatchers.IO) {
                    ImageLoader.loadFromUri(context, uri)
                }
                originalBitmap?.recycleSafe()
                originalBitmap = bmp
                srcWidth = bmp.width
                srcHeight = bmp.height
                processImage(context)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isProcessing = false
            }
        }
    }

    fun reprocess(context: Context) {
        if (originalBitmap == null) return
        reprocessJob?.cancel()
        reprocessJob = scope.launch {
            delay(150)
            processImage(context)
        }
    }

    private suspend fun processImage(context: Context) {
        val src = originalBitmap ?: return
        if (src.isRecycled || src.width <= 0 || src.height <= 0) return

        isProcessing = true
        try {
            val result = withContext(Dispatchers.Default) {
                val screenW = context.resources.displayMetrics.widthPixels
                val screenH = context.resources.displayMetrics.heightPixels
                val refH = screenH

                val maxProcessW = screenW * 2
                val working = if (src.width > maxProcessW) {
                    val scale = maxProcessW.toFloat() / src.width
                    val newW = maxProcessW
                    val newH = (src.height * scale).toInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(src, newW, newH, true)
                } else {
                    src
                }

                try {
                    WallpaperProcessor.processAsync(
                        src = working,
                        targetW = screenW,
                        targetH = refH,
                        config = WallpaperProcessor.Config(
                            blurRadius = blurRadius,
                            extendRatio = extendRatio,
                            featherWidth = featherWidth,
                            topOnly = topOnly
                        )
                    )
                } finally {
                    if (working != src) working.recycle()
                }
            }
            processedBitmap?.recycleSafe()
            processedBitmap = result
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isProcessing = false
        }
    }

    fun saveImage(context: Context) {
        val bmp = processedBitmap ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                ImageSaver.saveToGallery(
                    context, bmp,
                    "WallpaperExtend_${System.currentTimeMillis()}.png"
                )
            }
            withContext(Dispatchers.Main) {
                if (ok) {
                    Toast.makeText(context, "保存成功", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        originalBitmap?.recycleSafe()
        processedBitmap?.recycleSafe()
    }

    private fun Bitmap?.recycleSafe() {
        if (this != null && !isRecycled) {
            try { recycle() } catch (_: Exception) {}
        }
    }
}