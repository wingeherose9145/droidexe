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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
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

// 安全获取 Activity，防止闪退
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
        
        // 沉浸式与常亮
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
    
    // 1. 先声明数据，保证 Pager 创建时数据的一致性
    var videoFiles by remember { mutableStateOf(loadInternalVideos(context)) }
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

    // 2. 稳定的 PagerState，不再随旋转销毁
    val pagerState = rememberPagerState(pageCount = { videoFiles.size })

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (videoFiles.isEmpty()) {
            Text("请点击右下角导入视频", color = Color.Gray, modifier = Modifier.align(Alignment.Center))
        } else {
            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondBoundsPageCount = 0,
                pageSpacing = 0.dp,
                key = { index -> if (index < videoFiles.size) videoFiles[index].name else index }
            ) { page ->
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

        // 浮动按钮：仅在必要时出现，且极高透明度
        if ((videoFiles.isEmpty() || pausedByUser) && !isImporting) {
            FloatingActionButton(
                onClick = { launcher.launch("video/*") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 100.dp, end = 32.dp),
                containerColor = Color.White.copy(alpha = 0.1f),
                contentColor = Color.White.copy(alpha = 0.4f)
            ) {
                Icon(Icons.Default.Add, null)
            }
        }

        if (isImporting) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.White.copy(alpha = 0.3f))
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

    // 播放器创建：严格绑定 file，改变即销毁旧的
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

    // 必须：彻底释放资源
    DisposableEffect(file) {
        onDispose { exoPlayer.release() }
    }

    // 旋转逻辑：仅在页面激活且尺寸明确时触发
    LaunchedEffect(isActive, vWidth, vHeight) {
        if (isActive && vWidth > 0 && vHeight > 0) {
            val target = if (vWidth > vHeight) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE 
                         else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            if (activity?.requestedOrientation != target) {
                activity?.requestedOrientation = target
            }
        }
    }

    // 生命周期监听
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) isResumed = true
            if (event == Lifecycle.Event.ON_PAUSE) isResumed = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 播放状态逻辑
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

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { 
                PlayerView(it).apply { 
                    player = exoPlayer
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
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

        // ✅ 极致淡化进度条：透明度降至 0.03，宽度加大方便操作
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp),
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
    return folder.listFiles()?.filter { it.extension.lowercase() in listOf("mp4", "mkv", "mov", "webm") }?.sortedByDescending { it.lastModified() } ?: emptyList()
}

suspend fun importVideos(context: Context, uris: List<Uri>) = withContext(Dispatchers.IO) {
    val folder = File(context.filesDir, "videos")
    if (!folder.exists()) folder.mkdirs()
    uris.forEach { uri ->
        val destFile = File(folder, "tok_${System.currentTimeMillis()}.mp4")
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(destFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }
}
