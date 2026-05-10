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
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
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

        // 请求权限
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
    
    // 状态管理：当前的播放列表
    var currentPlaylistName by remember { mutableStateOf<String?>(null) } // null 表示“全部”
    
    // 根据状态获取不同的视频列表
    val videos = remember(currentPlaylistName) {
        getVideos(context, currentPlaylistName)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 播放器组件
        TikTokPlayer(videos = videos)

        // 顶层控制栏：演示如何切换不同的播放列表
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 40.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = { currentPlaylistName = null }) {
                Text("全部")
            }
            Button(onClick = { currentPlaylistName = "Camera" }) {
                Text("相机")
            }
            Button(onClick = { currentPlaylistName = "Download" }) {
                Text("下载")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TikTokPlayer(videos: List<Uri>) {
    if (videos.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("没有找到视频", color = Color.White)
        }
        return
    }

    val pagerState = rememberPagerState(pageCount = { videos.size })

    // 解决切换列表时 Pager 状态重置的问题
    LaunchedEffect(videos) {
        pagerState.scrollToPage(0)
    }

    VerticalPager(
        state = pagerState,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
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

    // --- 核心修改：监听生命周期，解决后台播放问题 ---
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

    // 控制播放逻辑：必须同时满足（在当前页 && 用户没点暂停 && 应用在前台）
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

    AndroidView(
        factory = {
            PlayerView(it).apply {
                player = exoPlayer
                useController = false
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .clickable {
                pausedByUser = !pausedByUser
            }
    )
}

/**
 * 获取视频列表
 * @param albumName 文件夹名称（例如 "Camera", "Download"），传入 null 则获取全部
 */
fun getVideos(context: android.content.Context, albumName: String? = null): List<Uri> {
    val videoList = mutableListOf<Uri>()
    val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(MediaStore.Video.Media._ID)
    
    // 多列表逻辑：通过 BUCKET_DISPLAY_NAME 过滤文件夹
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
