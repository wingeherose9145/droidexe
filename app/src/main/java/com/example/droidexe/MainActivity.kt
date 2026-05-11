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

// 安全获取 Activity 的助手函数，防止 Context 包装导致的闪退
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
        
        // 设置常亮和全屏
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
                pausedByUser = false
            }
        }
    }

    val pagerState = rememberPagerState(pageCount = { videoFiles.size })

    // ✅ 修复对齐闪退：增加安全检查
    LaunchedEffect(configuration.orientation) {
        if (videoFiles.isNotEmpty() && pagerState.pageCount > 0) {
            try {
                delay(200) // 给系统足够的布局转换时间
                pagerState.scrollToPage(pagerState.currentPage)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (videoFiles.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().clickable { pausedByUser = !pausedByUser },
                contentAlignment = Alignment.Center
            ) {
                Text("暂无视频，请点击右下角导入", color = Color.Gray, textAlign = TextAlign.Center)
            }
        } else {
            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondBoundsPageCount = 0,
                pageSpacing = 0.dp
            ) { page ->
                // 仅在当前页且停止滑动时激活视频逻辑
                val isCurrent = pagerState.currentPage == page && !pagerState.isScrollInProgress
                if (page < videoFiles.size) {
                    VideoPage(
                        file = videoFiles[page], 
                        play = isCurrent,
                        onPauseStateChange = { pausedByUser = it }
                    )
                }
            }
        }

        // 按钮淡化处理
        if ((videoFiles.isEmpty() || pausedByUser) && !isImporting) {
            FloatingActionButton(
                onClick = { launcher.launch("video/*") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 100.dp, end = 30.dp),
                containerColor = Color.White.copy(alpha = 0.2f),
                contentColor = Color.White
            ) {
                Icon(Icons.Default.Add, null)
            }
        }

        if (isImporting) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White.copy(alpha = 0.5f))
            }
        }
    }
}

@UnstableApi
@Composable
fun VideoPage(file: File, play: Boolean, onPauseStateChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val activity = remember { context.findActivity() } // 安全获取 Activity
    val lifecycleOwner = LocalLifecycleOwner.current 
    
    var paused by remember { mutableStateOf(false) }
    var isAppInForeground by remember { mutableStateOf(true) } 
    var progress by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    
    var videoWidth by remember { mutableIntStateOf(0) }
    var videoHeight by remember { mutableIntStateOf(0) }

    val exoPlayer = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
            repeatMode = Player.REPEAT_MODE_ONE
            addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    videoWidth = videoSize.width
                    videoHeight = videoSize.height
                }
            })
        }
    }

    // 方向切换
    LaunchedEffect(play, videoWidth, videoHeight) {
        if (play && videoWidth > 0 && videoHeight > 0) {
            val target = if (videoWidth > videoHeight) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE 
                         else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            if (activity?.requestedOrientation != target) {
                activity?.requestedOrientation = target
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            isAppInForeground = (event == Lifecycle.Event.ON_RESUME)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(play, paused, isAppInForeground) { 
        if (play && !paused && isAppInForeground) { 
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

    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    Box(modifier = Modifier.fillMaxSize()) {
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
                paused = !paused
                onPauseStateChange(paused)
            }
        )

        if (paused) {
            Icon(Icons.Default.PlayArrow, null, Modifier.size(80.dp).align(Alignment.Center), tint = Color.White.copy(alpha = 0.1f))
        }

        // ✅ 极致淡化进度条：高度更小，颜色更浅，减少视觉干扰
        Box(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(50.dp)
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
                    activeTrackColor = Color.White.copy(alpha = 0.05f),
                    inactiveTrackColor = Color.White.copy(alpha = 0.02f)
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
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }
}
