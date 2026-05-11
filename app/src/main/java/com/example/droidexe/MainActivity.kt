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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { MainScreen() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // 1. 状态管理：视频列表与导入状态
    var videoFiles by remember { mutableStateOf(loadInternalVideos(context)) }
    var isImporting by remember { mutableStateOf(false) }
    var pausedByUser by remember { mutableStateOf(false) }

    // 2. 视频选择器
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            isImporting = true
            scope.launch {
                importVideos(context, uris)
                videoFiles = loadInternalVideos(context) // 刷新列表
                isImporting = false
            }
        }
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val pagerState = rememberPagerState(pageCount = { videoFiles.size })

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (videoFiles.isEmpty()) {
            // 空状态提示
            Text("点击 + 号导入视频", color = Color.Gray, modifier = Modifier.align(Alignment.Center))
        } else {
            // 3. 自适应播放器：竖屏垂直，横屏水平
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

        // 4. 导入按钮：仅在暂停且未在导入时显示
        if (pausedByUser && !isImporting) {
            FloatingActionButton(
                onClick = { launcher.launch("video/*") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(30.dp),
                containerColor = Color.White.copy(alpha = 0.5f),
                contentColor = Color.Black
            ) {
                Icon(Icons.Default.Add, contentDescription = "Import")
            }
        }

        // 5. 导入加载中遮罩
        if (isImporting) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
                Text("正在复制视频到App内部...", color = Color.White, modifier = Modifier.padding(top = 80.dp))
            }
        }
    }
}

@Composable
fun VideoPage(file: File, play: Boolean, onPauseStateChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    var paused by remember { mutableStateOf(false) }
    
    // 进度条相关状态
    var progress by remember { mutableFloatStateOf(0f) }
    var duration by remember { mutableLongStateOf(1L) }

    val exoPlayer = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }

    // 监听播放状态和进度更新
    LaunchedEffect(play, paused) {
        if (play && !paused) {
            exoPlayer.play()
            while (true) {
                progress = exoPlayer.currentPosition.toFloat() / exoPlayer.duration.coerceAtLeast(1)
                duration = exoPlayer.duration
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
        AndroidView(
            factory = { PlayerView(it).apply { player = exoPlayer; useController = false } },
            modifier = Modifier.fillMaxSize().clickable { 
                paused = !paused
                onPauseStateChange(paused)
            }
        )

        // 暂停图标
        if (paused) {
            Icon(Icons.Default.PlayArrow, null, Modifier.size(80.dp).align(Alignment.Center), tint = Color.White.copy(alpha = 0.3f))
        }

        // 6. 沉浸式进度条
        Slider(
            value = progress,
            onValueChange = { 
                progress = it
                exoPlayer.seekTo((it * exoPlayer.duration).toLong())
            },
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 10.dp).height(20.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.Gray.copy(alpha = 0.5f)
            )
        )
    }
}

/**
 * 核心逻辑：加载 App 内部存储的视频文件
 */
fun loadInternalVideos(context: Context): List<File> {
    val folder = File(context.filesDir, "videos")
    if (!folder.exists()) folder.mkdirs()
    return folder.listFiles()?.filter { it.extension in listOf("mp4", "mkv", "mov") }?.sortedByDescending { it.lastModified() } ?: emptyList()
}

/**
 * 核心逻辑：将外部视频复制到沙盒目录
 */
suspend fun importVideos(context: Context, uris: List<Uri>) = withContext(Dispatchers.IO) {
    val folder = File(context.filesDir, "videos")
    if (!folder.exists()) folder.mkdirs()

    uris.forEach { uri ->
        val fileName = "vid_${System.currentTimeMillis()}_${uri.lastPathSegment}.mp4"
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
