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
        // 极致沉浸式
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    MainScreen()
                }
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
    
    var videoFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var isImporting by remember { mutableStateOf(false) }
    var pausedByUser by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        videoFiles = loadInternalVideos(context)
    }

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

    Box(modifier = Modifier.fillMaxSize()) {
        if (videoFiles.isNotEmpty()) {
            val pagerState = rememberPagerState(pageCount = { videoFiles.size })
            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondBoundsPageCount = 0
            ) { page ->
                val isActive = pagerState.currentPage == page && !pagerState.isScrollInProgress
                VideoPage(
                    file = videoFiles[page],
                    isActive = isActive,
                    onPauseStateChange = { pausedByUser = it }
                )
            }
        } else {
            Text("点击右下角导入视频", color = Color.Gray, modifier = Modifier.align(Alignment.Center))
        }

        if ((videoFiles.isEmpty() || pausedByUser) && !isImporting) {
            SmallFloatingActionButton(
                onClick = { launcher.launch("video/*") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(32.dp),
                containerColor = Color.White.copy(alpha = 0.1f)
            ) {
                Icon(Icons.Default.Add, null, tint = Color.White.copy(alpha = 0.5f))
            }
        }
    }
}

@UnstableApi
@Composable
fun VideoPage(file: File, isActive: Boolean, onPauseStateChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    var isPaused by remember { mutableStateOf(false) }
    var isResumed by remember { mutableStateOf(true) }
    var progress by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    val exoPlayer = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }

    DisposableEffect(file) {
        onDispose { exoPlayer.release() }
    }

    // 处理旋转与准备
    LaunchedEffect(isActive) {
        if (isActive) {
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            exoPlayer.prepare()
            
            exoPlayer.addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    val activity = context.let {
                        var ctx = it
                        while (ctx is ContextWrapper) {
                            if (ctx is Activity) break
                            ctx = ctx.baseContext
                        }
                        ctx as? Activity
                    }
                    val orientation = if (videoSize.width > videoSize.height) 
                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    activity?.requestedOrientation = orientation
                }
            })
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            isResumed = (event == Lifecycle.Event.ON_RESUME)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(isActive, isPaused, isResumed) {
        if (isActive && !isPaused && isResumed) {
            exoPlayer.play()
            while (true) {
                if (!isDragging && exoPlayer.duration > 0) {
                    progress = exoPlayer.currentPosition.toFloat() / exoPlayer.duration.toFloat()
                }
                delay(500)
            }
        } else {
            exoPlayer.pause()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setBackgroundColor(0)
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
            Icon(Icons.Default.PlayArrow, null, Modifier.size(60.dp).align(Alignment.Center), tint = Color.White.copy(alpha = 0.2f))
        }

        // ✅ 修正后的 Slider：使用兼容性最好的颜色定义，且视觉上极淡
        Slider(
            value = progress,
            onValueChange = { isDragging = true; progress = it },
            onValueChangeFinished = {
                isDragging = false
                exoPlayer.seekTo((progress * exoPlayer.duration).toLong())
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 40.dp, vertical = 4.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White.copy(alpha = 0.05f),
                activeTrackColor = Color.White.copy(alpha = 0.05f),
                inactiveTrackColor = Color.White.copy(alpha = 0.01f),
                disabledThumbColor = Color.Transparent,
                disabledActiveTrackColor = Color.Transparent,
                disabledInactiveTrackColor = Color.Transparent
            )
        )
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
