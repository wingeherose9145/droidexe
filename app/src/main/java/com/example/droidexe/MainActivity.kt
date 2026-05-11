package com.example.droidexe

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 1. 解决自动熄屏问题
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) 
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_MEDIA_VIDEO), 1)
        }
        setContent { MainScreen() }
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    var currentPlaylistName by remember { mutableStateOf<String?>(null) }
    var isNavVisible by remember { mutableStateOf(false) } 
    
    val availableFolders = remember { getAvailableVideoFolders(context) }
    val videos = remember(currentPlaylistName) { getVideos(context, currentPlaylistName) }

    val lazyListState = rememberLazyListState()
    var hideTimerResetTrigger by remember { mutableIntStateOf(0) }

    // 自动隐藏逻辑：操作或滑动时重置3秒计时
    LaunchedEffect(isNavVisible, hideTimerResetTrigger, lazyListState.isScrollInProgress) {
        if (isNavVisible) {
            if (lazyListState.isScrollInProgress) return@LaunchedEffect
            delay(3000) 
            if (isNavVisible) isNavVisible = false
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        TikTokPlayer(videos = videos)

        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null 
                ) { 
                    isNavVisible = !isNavVisible 
                    if (isNavVisible) hideTimerResetTrigger++
                }
            )
            Box(modifier = Modifier.fillMaxWidth().weight(2f)) 
        }

        AnimatedVisibility(
            visible = isNavVisible,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically()
        ) {
            LazyRow(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(top = 50.dp, bottom = 20.dp),
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(15.dp)
            ) {
                item {
                    CategoryTab("全部", currentPlaylistName == null) { 
                        currentPlaylistName = null
                        isNavVisible = false 
                    }
                }
                items(availableFolders) { folder ->
                    CategoryTab(folder, currentPlaylistName == folder) { 
                        currentPlaylistName = folder
                        isNavVisible = false 
                    }
                }
            }
        }
    }
}

@Composable
fun CategoryTab(title: String, isSelected: Boolean, onClick: () -> Unit) {
    Text(
        text = title,
        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.35f),
        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
        fontSize = if (isSelected) 18.sp else 16.sp,
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null 
            ) { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp) 
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TikTokPlayer(videos: List<Uri>) {
    if (videos.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { videos.size })
    VerticalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        VideoPage(uri = videos[page], play = pagerState.currentPage == page)
    }
}

@Composable
fun VideoPage(uri: Uri, play: Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var pausedByUser by remember { mutableStateOf(false) }
    var isAppInForeground by remember { mutableStateOf(true) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) isAppInForeground = false
            if (event == Lifecycle.Event.ON_RESUME) isAppInForeground = true
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            repeatMode = ExoPlayer.REPEAT_MODE_ONE
        }
    }

    LaunchedEffect(play, pausedByUser, isAppInForeground) {
        if (play && !pausedByUser && isAppInForeground) exoPlayer.play() else exoPlayer.pause()
    }

    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

    Box(
        modifier = Modifier.fillMaxSize().clickable { pausedByUser = !pausedByUser },
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { PlayerView(it).apply { player = exoPlayer; useController = false } },
            modifier = Modifier.fillMaxSize()
        )
        if (pausedByUser) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = Color.White.copy(alpha = 0.2f)
            )
        }
    }
}

fun getAvailableVideoFolders(context: android.content.Context): List<String> {
    val folders = mutableSetOf<String>()
    val projection = arrayOf(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
    context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, null, null, null)?.use { cursor ->
        val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
        while (cursor.moveToNext()) { cursor.getString(columnIndex)?.let { folders.add(it) } }
    }
    return folders.toList().sorted()
}

fun getVideos(context: android.content.Context, albumName: String? = null): List<Uri> {
    val videoList = mutableListOf<Uri>()
    val selection = if (albumName != null) "${MediaStore.Video.Media.BUCKET_DISPLAY_NAME} = ?" else null
    val selectionArgs = if (albumName != null) arrayOf(albumName) else null
    context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Video.Media._ID), selection, selectionArgs, MediaStore.Video.Media.DATE_ADDED + " DESC")?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        while (cursor.moveToNext()) {
            videoList.add(Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idColumn).toString()))
        }
    }
    return videoList
}
