package com.example.tokplayer

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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

    // 3秒无操作自动隐藏导航栏
    LaunchedEffect(isNavVisible) {
        if (isNavVisible) {
            delay(3000)
            isNavVisible = false
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // 1. 视频播放层
        TikTokPlayer(videos = videos)

        // 2. 透明交互层：将屏幕分为上下两部分
        Column(modifier = Modifier.fillMaxSize()) {
            // 点击上方 1/3 区域显隐导航
            Box(modifier = Modifier.fillMaxWidth().weight(1f).clickable { isNavVisible = !isNavVisible })
            // 下方 2/3 区域留给视频原有的播放/暂停点击逻辑
            Box(modifier = Modifier.fillMaxWidth().weight(2f))
        }

        // 3. 导航栏层
        AnimatedVisibility(
            visible = isNavVisible,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically()
        ) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(top = 50.dp, bottom = 20.dp),
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(25.dp)
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
        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.3f),
        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
        fontSize = if (isSelected) 16.sp else 14.sp,
        modifier = Modifier.clickable { onClick() }
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

    // 处理后台播放问题
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
                tint = Color.White.copy(alpha = 0.15f)
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
