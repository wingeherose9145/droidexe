package com.example.droidexe

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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

// 安全获取 Activity
fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@UnstableApi
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Black
            ) {
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

    var videoFiles by remember {
        mutableStateOf(loadInternalVideos(context))
    }

    var isImporting by remember { mutableStateOf(false) }
    var pausedByUser by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            isImporting = true
            scope.launch {
                importVideos(context, uris)
                videoFiles = loadInternalVideos(context)
                isImporting = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {

        // 🚨 空数据保护（关键）
        if (videoFiles.isEmpty()) {

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "暂无视频\n点击右下角 + 导入视频",
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
            }

        } else {

            val pagerState = rememberPagerState(
                pageCount = { videoFiles.size.coerceAtLeast(1) }
            )

            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondBoundsPageCount = 1
            ) { page ->

                if (page < videoFiles.size) {

                    val isActive =
                        pagerState.currentPage == page &&
                                !pagerState.isScrollInProgress

                    VideoPage(
                        file = videoFiles[page],
                        play = isActive,
                        onPauseStateChange = { pausedByUser = it }
                    )
                }
            }
        }

        // 导入按钮
        if ((videoFiles.isEmpty() || pausedByUser) && !isImporting) {

            FloatingActionButton(
                onClick = { launcher.launch("video/*") },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 90.dp, end = 30.dp),
                containerColor = Color.White.copy(alpha = 0.15f),
                contentColor = Color.White.copy(alpha = 0.6f)
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

@UnstableApi
@Composable
fun VideoPage(
    file: File,
    play: Boolean,
    onPauseStateChange: (Boolean) -> Unit
) {

    // 🚨 文件保护（关键）
    if (!file.exists()) return

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var paused by remember { mutableStateOf(false) }
    var isForeground by remember { mutableStateOf(true) }
    var progress by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    val exoPlayer = remember(file) {
        ExoPlayer.Builder(context).build().apply {

            runCatching {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                prepare()
            }

            repeatMode = Player.REPEAT_MODE_ONE
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            isForeground = event == Lifecycle.Event.ON_RESUME
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(play, paused, isForeground) {

        if (play && !paused && isForeground) {

            exoPlayer.play()

            while (exoPlayer.isPlaying) {

                if (!isDragging) {

                    val duration =
                        exoPlayer.duration.coerceAtLeast(1L)

                    progress =
                        exoPlayer.currentPosition.toFloat() / duration
                }

                delay(120)
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
                    resizeMode =
                        AspectRatioFrameLayout.RESIZE_MODE_FIT
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

        if (paused) {
            Icon(
                Icons.Default.PlayArrow,
                null,
                Modifier
                    .size(80.dp)
                    .align(Alignment.Center),
                tint = Color.White.copy(alpha = 0.08f)
            )
        }

               // 改进版可拖动进度条
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 30.dp),
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
                    if (exoPlayer.duration > 0) {
                        exoPlayer.seekTo((progress * exoPlayer.duration).toLong())
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White.copy(alpha = 0.7f),
                    inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                )
            )
        }

// -------------------- 数据层 --------------------

fun loadInternalVideos(context: Context): List<File> {
    val folder = File(context.filesDir, "videos")
    if (!folder.exists()) folder.mkdirs()

    return folder.listFiles()
        ?.filter { it.extension.lowercase() in listOf("mp4", "mkv", "mov", "webm") }
        ?.sortedByDescending { it.lastModified() }
        ?: emptyList()
}

suspend fun importVideos(context: Context, uris: List<Uri>) =
    withContext(Dispatchers.IO) {

        val folder = File(context.filesDir, "videos")
        if (!folder.exists()) folder.mkdirs()

        uris.forEach { uri ->

            val dest = File(
                folder,
                "tok_${System.currentTimeMillis()}.mp4"
            )

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }
