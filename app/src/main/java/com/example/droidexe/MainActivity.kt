// ... 保持之前的 import 不变 ...

@UnstableApi
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope() // ✅ 用于手动触发滚动对齐
    var videoFiles by remember { mutableStateOf(loadInternalVideos(context)) }
    var isImporting by remember { mutableStateOf(false) }
    var pausedByUser by remember { mutableStateOf(false) }

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

    val pagerState = rememberPagerState(pageCount = { videoFiles.size })

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (videoFiles.isEmpty()) {
            // ... 空列表显示代码 ...
        } else {
            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondBoundsPageCount = 0,
                pageSpacing = 0.dp
            ) { page ->
                // ✅ 逻辑微调：检测滚动停止
                val isCurrentPage = pagerState.currentPage == page && !pagerState.isScrollInProgress
                
                VideoPage(
                    file = videoFiles[page], 
                    play = isCurrentPage,
                    onOrientationTriggered = {
                        // ✅ 核心修复：当视频页面触发旋转时，我们让 Pager 强制对齐到当前页
                        scope.launch {
                            delay(300) // 等待系统完成布局变换
                            pagerState.animateScrollToPage(page)
                        }
                    },
                    onPauseStateChange = { pausedByUser = it }
                )
            }
        }
        // ... FAB 和 Loading 代码保持不变 ...
    }
}

@UnstableApi
@Composable
fun VideoPage(
    file: File, 
    play: Boolean, 
    onOrientationTriggered: () -> Unit, // ✅ 回调：通知父容器旋转了
    onPauseStateChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current 
    
    var paused by remember { mutableStateOf(false) }
    var isAppInForeground by remember { mutableStateOf(true) } 
    var progress by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    
    var videoWidth by remember { mutableIntStateOf(0) }
    var videoHeight by remember { mutableIntStateOf(0) }

    val exoPlayer = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
            repeatMode = Player.REPEAT_MODE_ONE
            addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    videoWidth = videoSize.width
                    videoHeight = videoSize.height
                }
            })
        }
    }

    // ✅ 修复对齐的 LaunchedEffect
    LaunchedEffect(play, videoWidth, videoHeight) {
        if (play && videoWidth > 0 && videoHeight > 0) {
            val targetOrientation = if (videoWidth > videoHeight) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }

            if (activity?.requestedOrientation != targetOrientation) {
                activity?.requestedOrientation = targetOrientation
                // 只要触发了旋转，就通知外部 Pager 强制对齐
                onOrientationTriggered()
            }
        }
    }

    // ... 下方的播放逻辑、Slider 和 AndroidView 代码保持不变 ...
}
