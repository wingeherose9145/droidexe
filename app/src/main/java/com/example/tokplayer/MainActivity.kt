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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_VIDEO
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_MEDIA_VIDEO),
                1
            )
        }

        setContent {
            TikTokPlayer()
        }
    }

    override fun onPause() {
        super.onPause()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TikTokPlayer() {

    val context = LocalContext.current

    val videos = remember {
        getAllVideos(context)
    }

    val pagerState = rememberPagerState(
        pageCount = { videos.size }
    )

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

    var paused by remember {
        mutableStateOf(false)
    }

    val exoPlayer = remember {

        ExoPlayer.Builder(context).build().apply {

            setMediaItem(
                MediaItem.fromUri(uri)
            )

            prepare()

            repeatMode = ExoPlayer.REPEAT_MODE_ONE
        }
    }

    LaunchedEffect(play, paused) {

        if (play && !paused) {

            exoPlayer.play()

        } else {

            exoPlayer.pause()
        }
    }

    DisposableEffect(Unit) {

        onDispose {

            exoPlayer.pause()

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

                paused = !paused
            }
    )
}

fun getAllVideos(context: android.content.Context): List<Uri> {

    val videoList = mutableListOf<Uri>()

    val collection =
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    val projection = arrayOf(
        MediaStore.Video.Media._ID
    )

    context.contentResolver.query(

        collection,
        projection,
        null,
        null,
        MediaStore.Video.Media.DATE_ADDED + " DESC"

    )?.use { cursor ->

        val idColumn = cursor.getColumnIndexOrThrow(
            MediaStore.Video.Media._ID
        )

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
