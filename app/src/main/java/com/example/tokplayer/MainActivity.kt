package com.example.tokplayer

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) 
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_MEDIA_VIDEO), 1)
        }

        setContent {
            MainScreen()
        }
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    
    var currentPlaylistName by remember { mutableStateOf<String?>(null) } 
    
    val availableFolders = remember {
        getAvailableVideoFolders(context)
    }

    val videos = remember(currentPlaylistName) {
        getVideos(context, currentPlaylistName)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        TikTokPlayer(videos = videos)

        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 40.dp, bottom = 10.dp),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item {
                CategoryTab(
                    title = "全部",
                    isSelected = currentPlaylistName == null,
                    onClick = { currentPlaylistName = null }
                )
            }
            items(availableFolders) { folder ->
                CategoryTab(
                    title = folder,
                    isSelected = currentPlaylistName == folder,
                    onClick = { currentPlaylistName = folder }
                )
            }
        }
    }
}

// 改善 1：导航字体更小，未选中状态更透明 (alpha = 0.3f)
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
    if (videos.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("没有找到视频", color = Color.White.copy(alpha = 0.5f))
        }
        return
    }

    val pagerState = rememberPagerState(pageCount = { videos.size })

    LaunchedEffect(videos) {
        pagerState.scrollToPage(0)
    }

    VerticalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize()
    ) { page ->
        VideoPage(
            uri = videos[page],
            play = pagerState.currentPage == page
        )
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
            when (event) {
                Lifecycle.Event.ON_PAUSE -> isAppInForeground = false
                Lifecycle.Event.ON_RESUME -> isAppInForeground = true
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            repeatMode = ExoPlayer.REPEAT_MODE_ONE
        }
    }

    LaunchedEffect(play, pausedByUser, isAppInForeground) {
        if (play && !pausedByUser && isAppInForeground) {
            exoPlayer.play()
        } else {
            exoPlayer.pause()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable { pausedByUser = !pausedByUser }, 
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    player = exoPlayer
                    useController = false 
                    setBackgroundColor(android.graphics.Color.BLACK) 
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 改善 2：暂停图标透明度降至 0.2f，更加隐蔽
        if (pausedByUser) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Play",
                modifier = Modifier.size(72.dp),
                tint = Color.White.copy(alpha = 0.2f) 
            )
        }
    }
}

fun getAvailableVideoFolders(context: android.content.Context): List<String> {
    val folders = mutableSetOf<String>()
    val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)

    context.contentResolver.query(
        collection,
        projection,
        null,
        null,
        null
    )?.use { cursor ->
        val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
        while (cursor.moveToNext()) {
            val folderName = cursor.getString(columnIndex)
            if (folderName != null) {
                folders.add(folderName)
            }
        }
    }
    return folders.toList().sorted()
}

fun getVideos(context: android.content.Context, albumName: String? = null): List<Uri> {
    val videoList = mutableListOf<Uri>()
    val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(MediaStore.Video.Media._ID)
    
    val selection = if (albumName != null) {
        "${MediaStore.Video.Media.BUCKET_DISPLAY_NAME} = ?"
    } else null
    
    val selectionArgs = if (albumName != null) {
        arrayOf(albumName)
    } else null

    context.contentResolver.query(
        collection,
        projection,
        selection,
        selectionArgs,
        MediaStore.Video.Media.DATE_ADDED + " DESC"
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            val contentUri = Uri.withAppendedPath(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                id.toString()
            )
            videoList.add(contentUri)
        }
    }
    return videoList
}
