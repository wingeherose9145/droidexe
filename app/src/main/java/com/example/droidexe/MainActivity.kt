package com.example.droidexe

import android.app.Activity
import android.content.Context
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

@UnstableApi 
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 沉浸式全屏与常亮设置
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }

        setContent { MainScreen() }
    }
}

@UnstableApi
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
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

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (videoFiles.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().clickable { pausedByUser = !pausedByUser },
                contentAlignment = Alignment.Center
            ) {
                Text("还没有视频\n请点击右下角 + 号导入", color = Color.Gray, textAlign = TextAlign.Center)
            }
        } else {
            // 始终使用垂直翻页，确保上下滑动切换逻辑的一致性
            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondBoundsPageCount = 1
            ) { page ->
                VideoPage(
                    file = videoFiles[page], 
                    play = pagerState.currentPage == page,
                    onPauseStateChange = { pausedByUser = it }
                )
            }
        }

        // 导入按钮：暂停时出现
        if ((videoFiles.isEmpty() || pausedByUser) && !isImporting) {
            FloatingActionButton(
                onClick = { launcher.launch("video/*") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 80.dp, end = 30.dp),
                containerColor = Color.White.copy(alpha = 0.8f),
                contentColor = Color.Black
            ) {
                Icon(Icons.Default.Add, contentDescription = "Import", modifier = Modifier.size(30.dp))
            }
        }

        // 导入状态指示
        if (isImporting) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        }
    }
}

@UnstableApi
@Composable
fun VideoPage(file: File, play: Boolean, onPauseStateChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current 
    
    var paused by remember { mutableStateOf(false) }
    var isAppInForeground by remember { mutableStateOf(true) } 
    var progress by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    val exoPlayer = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
            repeatMode = Player.REPEAT_MODE_ONE
            
            // ✅ 核心逻辑：监听视频尺寸并强制调整屏幕方向
            addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (play && videoSize.width > 0 && videoSize.height > 0) {
                        if (videoSize.width > videoSize.height) {
                            // 检测为横屏视频 -> 强制 Activity 进入横屏模式以获得最大占位
                            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        } else {
                            // 检测为竖屏视频 -> 强制 Activity 恢复竖屏
                            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        }
                    }
                }
            })
        }
    }

    // 处理回到桌面自动暂停逻辑
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) isAppInForeground = false 
            else if (event == Lifecycle.Event.ON_RESUME) isAppInForeground = true 
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(play, paused, isAppInForeground) { 
        if (play && !paused && isAppInForeground) { 
            exoPlayer.play()
            while (true) {
                if (!isDragging) {
                    progress = exoPlayer.currentPosition.toFloat() / exoPlayer.duration.coerceAtLeast(1L).toFloat()
                }
                delay(500)
            }
        } else {
            exoPlayer.pause()
        }
    }

    DisposableEffect(Unit) {
        onDispose { 
            exoPlayer.release() 
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { 
                PlayerView(it).apply { 
                    player = exoPlayer
                    useController = false
                    // ✅ 关键：FIT 模式配合 Activity 旋转，实现不拉伸的最大占位
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
            Icon(
                Icons.Default.PlayArrow, null, 
                Modifier.size(80.dp).align(Alignment.Center), 
                tint = Color.White.copy(alpha = 0.3f)
            )
        }

        // 灵敏进度条：加大热区，无论横竖屏都能精准操作
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(100.dp)
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
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.24f)
                )
            )
        }
    }
}

/**
 * 内部存储逻辑
 */
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
        val destFile = File(folder, "droid_${System.currentTimeMillis()}.mp4")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
