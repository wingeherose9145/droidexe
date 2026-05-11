package com.example.droidexe

import android.content.Context
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
import androidx.compose.foundation.pager.HorizontalPager
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
        
        // ✅ 核心修改 1：开启硬件常亮和全屏沉浸模式（隐藏状态栏和导航栏，解决横屏黑边）
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        // 适配异形屏（刘海屏/挖孔屏），让画面真正拓展到屏幕边缘
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = 
                WindowManager.LayoutParams.LAYOUT_INDISPLAY_CUTOUT_MODE_SHORT_EDGES
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
    
    // 状态管理
    var videoFiles by remember { mutableStateOf(loadInternalVideos(context)) }
    var isImporting by remember { mutableStateOf(false) }
    var pausedByUser by remember { mutableStateOf(false) }

    // 视频选择器
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            isImporting = true
            scope.launch {
                importVideos(context, uris)
                videoFiles = loadInternalVideos(context) // 刷新列表
                isImporting = false
                pausedByUser = false // 导入后自动播放
            }
        }
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val pagerState = rememberPagerState(pageCount = { videoFiles.size })

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (videoFiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { 
                        pausedByUser = !pausedByUser 
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "还没有视频\n请点击右下角 + 号导入",
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            // 自适应播放器：竖屏垂直滑动，横屏水平滑动
            if (isLandscape) {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    VideoPage(videoFiles[page], pagerState.currentPage == page, 
                        onPauseStateChange = { pausedByUser = it })
                }
            } else {
                VerticalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    VideoPage(videoFiles[page], pagerState.currentPage == page,
                        onPauseStateChange = { pausedByUser = it })
                }
            }
        }

        // 导入按钮显示逻辑（位置微调，避开沉浸式区域）
        if ((videoFiles.isEmpty() || pausedByUser) && !isImporting) {
            FloatingActionButton(
                onClick = { launcher.launch("video/*") },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 60.dp, end = 30.dp),
                containerColor = Color.White.copy(alpha = 0.8f),
                contentColor = Color.Black
            ) {
                Icon(Icons.Default.Add, contentDescription = "Import", modifier = Modifier.size(30.dp))
            }
        }

        // 导入中的遮罩
        if (isImporting) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(modifier = Modifier.height(20.dp))
                    Text("正在安全复制到本地仓库...", color = Color.White)
                }
            }
        }
    }
}

@UnstableApi
@Composable
fun VideoPage(file: File, play: Boolean, onPauseStateChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current 
    var paused by remember { mutableStateOf(false) }
    
    // 解决后台播放：监听 App 生命周期的变化
    var isAppInForeground by remember { mutableStateOf(true) } 
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                isAppInForeground = false 
            } else if (event == Lifecycle.Event.ON_RESUME) {
                isAppInForeground = true 
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // ✅ 核心修改 2：进度条相关状态优化
    var progress by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    // 控制进度条层的点击热区拦截
    val interactionSource = remember { MutableInteractionSource() }

    val exoPlayer = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }

    // 播放/暂停控制及进度更新
    LaunchedEffect(play, paused, isAppInForeground) { 
        if (play && !paused && isAppInForeground) { 
            exoPlayer.play()
            while (true) {
                if (!isDragging) {
                    val current = exoPlayer.currentPosition.toFloat()
                    val total = exoPlayer.duration.coerceAtLeast(1L).toFloat()
                    progress = current / total
                }
                delay(500)
            }
        } else {
            exoPlayer.pause()
        }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 视频画面层
        AndroidView(
            factory = { 
                PlayerView(it).apply { 
                    player = exoPlayer
                    useController = false
                    // ✅ 核心修改 3：配合全屏模式，使用 RESIZE_MODE_FILL 彻底最大化（不裁剪）
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                } 
            },
            modifier = Modifier
                .fillMaxSize()
                // 手指点击视频画面，触发暂停
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { 
                    paused = !paused
                    onPauseStateChange(paused)
                }
        )

        // 暂停时的中间大图标
        if (paused) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(80.dp).align(Alignment.Center),
                tint = Color.White.copy(alpha = 0.3f)
            )
        }

        // ✅ 核心修改 4：超灵敏沉浸式进度条 (Slider 区域加大)
        // 这个 Box 专门作为一个透明的拦截层，用于容纳 Slider
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(80.dp) // 给一个很大的高度，作为点击热区
                // ✅ 极其关键：拦截这个区域内所有的点击手势，确保不会透过点击由于 Slider 细导致的视频暂停层
                .clickable(
                    interactionSource = interactionSource,
                    indication = null // 去掉点击时的灰色阴影
                ) {}, // 虽然不做任何事，但它拦截了手势
            contentAlignment = Alignment.Center
        ) {
            Slider(
                value = progress,
                onValueChange = { 
                    isDragging = true
                    progress = it
                },
                onValueChangeFinished = {
                    isDragging = false
                    val seekPos = (progress * exoPlayer.duration).toLong()
                    exoPlayer.seekTo(seekPos)
                },
                // 将 Slider 浮到 Box 的上面，确保它接收滑动手势
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 25.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                )
            )
        }
    }
}

/**
 * 加载 App 内部 videos 目录下的文件
 */
fun loadInternalVideos(context: Context): List<File> {
    val folder = File(context.filesDir, "videos")
    if (!folder.exists()) folder.mkdirs()
    return folder.listFiles()?.filter { 
        it.extension.lowercase() in listOf("mp4", "mkv", "mov", "webm") 
    }?.sortedByDescending { it.lastModified() } ?: emptyList()
}

/**
 * 将外部视频复制到 App 专属沙盒
 */
suspend fun importVideos(context: Context, uris: List<Uri>) = withContext(Dispatchers.IO) {
    val folder = File(context.filesDir, "videos")
    if (!folder.exists()) folder.mkdirs()

    uris.forEach { uri ->
        // 使用时间戳命名防止重复
        val fileName = "droid_${System.currentTimeMillis()}.mp4"
        val destFile = File(folder, fileName)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
