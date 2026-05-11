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
import androidx.compose.ui.platform.LocalLifecycleOwner // ✅ 新增：生命周期管理
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle // ✅ 新增
import androidx.lifecycle.LifecycleEventObserver // ✅ 新增
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout // ✅ 新增
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
        // 保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { MainScreen() }
    }
}

@UnstableApi
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // 狀態管理
    var videoFiles by remember { mutableStateOf(loadInternalVideos(context)) }
    var isImporting by remember { mutableStateOf(false) }
    var pausedByUser by remember { mutableStateOf(false) }

    // 視頻選擇器
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            isImporting = true
            scope.launch {
                importVideos(context, uris)
                videoFiles = loadInternalVideos(context) // 刷新列表
                isImporting = false
                pausedByUser = false // 導入後自動播放
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
                    text = "還沒有視頻\n請點擊右下角 + 號導入",
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            // 自適應播放器：豎屏垂直滑動，橫屏水平滑動
            // ✅ 這裡現在能正確檢測到系統配置變化了
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

        // 導入按钮顯示邏輯
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

        // 導入中的遮罩
        if (isImporting) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(modifier = Modifier.height(20.dp))
                    Text("正在安全複製到本地倉庫...", color = Color.White)
                }
            }
        }
    }
}

@UnstableApi
@Composable
fun VideoPage(file: File, play: Boolean, onPauseStateChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current // ✅ 獲取生命周期所有者
    var paused by remember { mutableStateOf(false) }
    
    // 解決後台播放：監聽 App 生命周期的變化
    var isAppInForeground by remember { mutableStateOf(true) } // ✅ 新增：App 是否在前台
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                isAppInForeground = false // ✅ App 去後台，暫停
            } else if (event == Lifecycle.Event.ON_RESUME) {
                isAppInForeground = true // ✅ App 回前台，恢復
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 进度条相关狀態
    var progress by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    val exoPlayer = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
            repeatMode = Player.REPEAT_MODE_ONE
        }
    }

    // 播放/暫停控制及進度更新
    LaunchedEffect(play, paused, isAppInForeground) { // ✅ 新增了isAppInForeground
        if (play && !paused && isAppInForeground) { // ✅ 只有同時滿足才能播放
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
        AndroidView(
            factory = { 
                PlayerView(it).apply { 
                    player = exoPlayer
                    useController = false
                    // ✅ 新增：解決最大化播放問題。設置縮放模式為填滿但不變形。
                    // FIT模式下，如果手機物理旋轉到橫屏，ExoPlayer 會自動最大化填滿。
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                } 
            },
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { 
                    paused = !paused
                    onPauseStateChange(paused)
                }
        )

        // 暫停時的中間大圖標
        if (paused) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(80.dp).align(Alignment.Center),
                tint = Color.White.copy(alpha = 0.3f)
            )
        }

        // 沉浸式進度條 (Slider)
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
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
            )
        )
    }
}

/**
 * 加載 App 內部 videos 目錄下的文件
 */
fun loadInternalVideos(context: Context): List<File> {
    val folder = File(context.filesDir, "videos")
    if (!folder.exists()) folder.mkdirs()
    return folder.listFiles()?.filter { 
        it.extension.lowercase() in listOf("mp4", "mkv", "mov", "webm") 
    }?.sortedByDescending { it.lastModified() } ?: emptyList()
}

/**
 * 將外部視頻複製到 App 專屬沙盒
 */
suspend fun importVideos(context: Context, uris: List<Uri>) = withContext(Dispatchers.IO) {
    val folder = File(context.filesDir, "videos")
    if (!folder.exists()) folder.mkdirs()

    uris.forEach { uri ->
        // 使用時間戳命名防止重複
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
