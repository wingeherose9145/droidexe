package com.example.tokplayer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

// 顶级工具函数：安全寻找 Activity
fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

@UnstableApi 
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 沉浸式与常亮配置
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }

        setContent {
            Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                MainScreen()
            }
        }
    }
}

@UnstableApi
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    
    // 初始化加载视频
    var videoFiles by remember { mutableStateOf(emptyList<File>()) }
    LaunchedEffect(Unit) {
        videoFiles = loadInternalVideos(context)
    }

    var isImporting by remember { mutableStateOf(false) }
    var pausedByUser by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            isImporting = true
            scope.launch {
                importVideos(context, uris)
                videoFiles = loadInternalVideos(context)
                isImporting = false
            }
        }
    }

    // ✅ 解决“对不齐”的终极方案：使用 orientation 作为 Pager 的 key
    // 当旋转发生时，Pager 会重新构建并自动吸附到当前比例的正确位置
    key(configuration.orientation) {
        val pagerState = rememberPagerState(pageCount = { videoFiles.size })

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            if (videoFiles.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("请点击右下角导入视频", color = Color.Gray, textAlign = TextAlign.Center)
                }
            } else {
                VerticalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondBoundsPageCount = 0,
                    pageSpacing = 0.dp
                ) { page ->
                    // 仅当停止滑动且是当前页时，才激活播放和旋转逻辑
                    val isVisible = pagerState.currentPage == page && !pagerState.isScrollInProgress
                    if (page < videoFiles.size) {
                        VideoPage(
                            file = videoFiles[page], 
                            isActive = isVisible,
                            onPauseStateChange = { pausedByUser = it }
                        )
                    }
                }
            }

            // 导入按钮：大幅淡化
            if ((videoFiles.isEmpty() || pausedByUser) && !isImporting) {
                FloatingActionButton(
                    onClick = { launcher.launch("video/*") },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 100.dp, end = 32.dp),
                    containerColor = Color.White.copy(alpha = 0.15f),
                    contentColor = Color.White.copy(alpha = 0.5f)
                ) {
                    Icon(Icons.Default.Add, null)
                }
            }

            if (isImporting) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White.copy(alpha = 0.3f)
                )
            }
        }
    }
}

@UnstableApi
@Composable
fun VideoPage(file: File, isActive: Boolean, onPauseStateChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val activity = remember { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    
    var isPaused by remember { mutableStateOf(false) }
    var isResumed by remember { mutableStateOf(true) }
    var progress by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var vWidth by remember { mutableIntStateOf(0) }
    var vHeight by remember { mutableIntStateOf(0) }

    // ✅ 修正：将 ExoPlayer 的 remember 与生命周期强绑定
    val exoPlayer = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
            repeatMode = Player.REPEAT_MODE_ONE
            addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    vWidth = videoSize.width
                    vHeight = videoSize.height
                }
            })
        }
    }

    // 播放器物理释放：当离开页面或 file 变化时执行
    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    // 屏幕旋转控制
    LaunchedEffect(isActive, vWidth, vHeight) {
        if (isActive && vWidth > 0 && vHeight > 0) {
            val target = if (vWidth > vHeight) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE 
                         else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            if (activity?.requestedOrientation != target) {
                activity?.requestedOrientation = target
            }
        }
    }

    // 前后台监听
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            isResumed = (event == Lifecycle.Event.ON_RESUME)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 播放与进度更新逻辑
    LaunchedEffect(isActive, isPaused, isResumed) {
        if (isActive && !isPaused && isResumed) {
            exoPlayer.play()
            while (true) {
                if (!isDragging) {
                    val duration = exoPlayer.duration.coerceAtLeast(1L)
                    progress = exoPlayer.currentPosition.toFloat() / duration.toFloat()
                }
                delay(500)
            }
        } else {
            exoPlayer.pause()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { 
                PlayerView(it).apply { 
                    player = exoPlayer
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setBackgroundColor(0xFF000000.toInt())
                } 
            },
            modifier = Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { 
                isPaused = !isPaused
                onPauseStateChange(isPaused)
            }
        )

        if (isPaused) {
            Icon(Icons.Default.PlayArrow, null, Modifier.size(80.dp).align(Alignment.Center), tint = Color.White.copy(alpha = 0.1f))
        }

        // ✅ 极致淡化进度条：透明度 0.03f，厚度极小，高精度 seek
        Box(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(40.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
            contentAlignment = Alignment.Center
        ) {
            Slider(
                value = progress,
                onValueChange = { isDragging = true; progress = it },
                onValueChangeFinished = {
                    isDragging = false
                    exoPlayer.seekTo((progress * exoPlayer.duration).toLong())
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White.copy(alpha = 0.05f),
                    activeTrackColor = Color.White.copy(alpha = 0.03f),
                    inactiveTrackColor = Color.White.copy(alpha = 0.01f)
                )
            )
        }
    }
}

fun loadInternalVideos(context: Context): List<File> {
    val folder = File(context.filesDir, "videos")
    if (!folder.exists()) folder.mkdirs()
    return folder.listFiles()?.filter { 
        it.extension.lowercase() in listOf("mp4", "mkv", "mov", "webm") 
    }?.sortedByDescending { it.lastModified() } ?: emptyList()
}

suspend fun importVideos(context: Context, uris: List<Uri>) = withContext(Dispatchers.IO) {
    val folder = File(context.filesDir, "videos")
    if (!folder.exists()) folder.mkdirs()
    uris.forEach { uri ->
        val destFile = File(folder, "tok_${System.currentTimeMillis()}.mp4")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }
}
