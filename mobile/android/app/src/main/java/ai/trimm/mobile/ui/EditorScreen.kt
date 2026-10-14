package ai.trimm.mobile.ui

import ai.trimm.mobile.ChatState
import ai.trimm.mobile.ChatViewModel
import ai.trimm.mobile.billing.BillingManager
import ai.trimm.mobile.domain.UiChatMessage
import ai.trimm.mobile.domain.UndoTarget
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.TextStyle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import ai.trimm.mobile.domain.TimelineNode
import ai.trimm.mobile.domain.RemoveRanges
import ai.trimm.mobile.media.ExportCoordinator
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import android.view.View
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.draw.clipToBounds
import ai.trimm.mobile.domain.displaySize
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.platform.LocalDensity

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
fun EditorScreen(
    vm: ChatViewModel,
    onOpenDrawer: () -> Unit
) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val balance by BillingManager.balance.collectAsStateWithLifecycle()

    var prompt by remember { mutableStateOf("") }
    var isVideoVisible by remember { mutableStateOf(true) }
    var showAssetsModal by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var selectedUndoTarget by remember { mutableStateOf<UndoTarget?>(null) }
    var playbackPositionMs by remember { mutableLongStateOf(0L) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Native hardware video player (zero color tint, direct SurfaceView output)
    val player = remember {
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 50_000,
                /* bufferForPlaybackMs = */ 100,
                /* bufferForPlaybackAfterRebufferMs = */ 250
            )
            .setBufferDurationsMsForLocalPlayback(
                /* minBufferForLocalPlaybackMs = */ 15_000,
                /* maxBufferForLocalPlaybackMs = */ 50_000,
                /* bufferForPlaybackForLocalPlaybackMs = */ 100,
                /* bufferForPlaybackAfterRebufferForLocalPlaybackMs = */ 250
            )
            .setBackBuffer(5_000, true)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setPrioritizeTimeOverSizeThresholdsForLocalPlayback(true)
            .build()

        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .experimentalSetEnableMediaCodecVideoRendererPrewarming(true)

        ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(5000)
            .setSeekForwardIncrementMs(15000)
            .build().apply {
                repeatMode = Player.REPEAT_MODE_ALL
                playWhenReady = true
            }
    }

    DisposableEffect(player) {
        onDispose {
            player.release()
        }
    }

    // Continuously monitor playback position for synchronized overlay rendering
    LaunchedEffect(player, state.compositionSchema, state.previewUri) {
        while (true) {
            val schema = state.compositionSchema
            val vNodes = schema?.timeline?.filterIsInstance<TimelineNode.Video>()?.filter { (it.endMs - it.startMs) > 0 } ?: emptyList()
            if (!state.previewUri.isNullOrEmpty()) {
                // Preview proxy is a single continuous stitched video: player position matches timeline 1:1!
                playbackPositionMs = player.currentPosition
            } else if (vNodes.isNotEmpty()) {
                val curIndex = player.currentMediaItemIndex
                if (curIndex in vNodes.indices) {
                    playbackPositionMs = vNodes[curIndex].startMs + player.currentPosition
                }
            } else {
                playbackPositionMs = player.currentPosition
            }
            delay(33)
        }
    }

    var currentLoadedSignature by remember { mutableStateOf<String?>(null) }

    // Refresh player media source when project, composition schema, or previewUri changes
    LaunchedEffect(state.project, state.compositionSchema, state.previewUri) {
        val proj = state.project ?: return@LaunchedEffect
        val schema = state.compositionSchema
        val sourceUri = state.source?.uri ?: proj.orderedSources.firstOrNull()?.uri

        // Priority 1: Fast Local Preview Proxy (single continuous file, 0 transitions, 60fps scrub)
        if (!state.previewUri.isNullOrEmpty()) {
            val proxyUriStr = state.previewUri
            if (currentLoadedSignature != proxyUriStr || player.mediaItemCount != 1) {
                currentLoadedSignature = proxyUriStr
                val wasPlaying = player.isPlaying
                val currentPos = player.currentPosition
                player.setMediaItem(MediaItem.fromUri(Uri.parse(proxyUriStr)))
                player.prepare()
                if (currentPos > 0) player.seekTo(currentPos)
                if (wasPlaying) player.play()
            }
            return@LaunchedEffect
        }

        // Priority 2: Direct playlist of video cuts while proxy is rendering in background
        val videoNodes = schema?.timeline?.filterIsInstance<TimelineNode.Video>()?.filter { (it.endMs - it.startMs) > 0 } ?: emptyList()
        if (videoNodes.isNotEmpty()) {
            val signature = "cuts:" + videoNodes.joinToString(";") { "${it.src}:${it.sourceStartMs}:${it.startMs}:${it.endMs}" }
            if (currentLoadedSignature != signature || player.mediaItemCount != videoNodes.size) {
                currentLoadedSignature = signature
                val items = videoNodes.map { node ->
                    val durationMs = node.endMs - node.startMs
                    val clipping = MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(node.sourceStartMs)
                        .setEndPositionMs(node.sourceStartMs + durationMs)
                        .build()
                    val uriString = node.src.takeIf { it.isNotBlank() } ?: sourceUri ?: ""
                    MediaItem.Builder()
                        .setUri(Uri.parse(uriString))
                        .setClippingConfiguration(clipping)
                        .build()
                }
                player.setMediaItems(items)
                player.prepare()
            }
        } else if (proj.operations.any { it is RemoveRanges && it.enabled }) {
            val durationMs = proj.durationMs
            val cuts = proj.operations.filterIsInstance<RemoveRanges>().filter { it.enabled }.flatMap { it.ranges }.sortedBy { it.startMs }
            val retained = ExportCoordinator.retainedRanges(durationMs, cuts)
            val signature = "ops:" + retained.joinToString(";") { "${it.startMs}:${it.endMs}" }
            if (currentLoadedSignature != signature) {
                currentLoadedSignature = signature
                if (retained.isNotEmpty() && !sourceUri.isNullOrEmpty()) {
                    val items = retained.map { r ->
                        val clipping = MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(r.startMs)
                            .setEndPositionMs(r.endMs)
                            .build()
                        MediaItem.Builder()
                            .setUri(Uri.parse(sourceUri))
                            .setClippingConfiguration(clipping)
                            .build()
                    }
                    player.setMediaItems(items)
                    player.prepare()
                } else if (!sourceUri.isNullOrEmpty()) {
                    player.setMediaItem(MediaItem.fromUri(Uri.parse(sourceUri)))
                    player.prepare()
                }
            }
        } else if (!sourceUri.isNullOrEmpty()) {
            val signature = "single:$sourceUri"
            if (currentLoadedSignature != signature) {
                currentLoadedSignature = signature
                player.setMediaItem(MediaItem.fromUri(Uri.parse(sourceUri)))
                player.prepare()
            }
        }
    }

    // Scroll to bottom when messages change
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    val clipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            vm.import(uris)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.project?.title ?: "Edit",
                        color = Cream,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Open Drawer",
                            tint = Cream
                        )
                    }
                },
                actions = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        // Credit balance capsule
                        Row(
                            modifier = Modifier
                                .height(28.dp)
                                .clip(CircleShape)
                                .background(Raised)
                                .border(1.dp, Border.copy(alpha = 0.6f), CircleShape)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { BillingManager.setStorePresented(true) }
                                )
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = "Credits",
                                tint = Color(0xFFFACC15),
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "$balance",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Cream
                            )
                        }

                        // Three dots options menu
                        Box {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(Raised)
                                    .border(1.dp, Border.copy(alpha = 0.6f), CircleShape)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { showMenu = true }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "More Options",
                                    tint = Lavender,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                                modifier = Modifier.background(Raised)
                            ) {
                            DropdownMenuItem(
                                text = { Text("Export Video", color = Cream) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = null,
                                        tint = Cream
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    vm.export()
                                },
                                enabled = state.project != null && !state.isProcessing && !state.isExporting
                            )
                            DropdownMenuItem(
                                text = { Text("Project Assets", color = Cream) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.VideoLibrary,
                                        contentDescription = null,
                                        tint = Cream
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    showAssetsModal = true
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (isVideoVisible) "Hide Video Preview" else "Show Video Preview",
                                        color = Cream
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (isVideoVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = null,
                                        tint = Cream
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    isVideoVisible = !isVideoVisible
                                }
                            )
                        }
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Ink,
                navigationIconContentColor = Cream,
                titleContentColor = Cream,
                actionIconContentColor = Cream
            )
        )
    },
    containerColor = Ink
) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(Modifier.fillMaxSize()) {
                // Top Video Player (40% height)
                AnimatedVisibility(
                    visible = isVideoVisible,
                    enter = expandVertically(animationSpec = tween(300)) + fadeIn(),
                    exit = shrinkVertically(animationSpec = tween(300)) + fadeOut()
                ) {
                    val sourceMedia = state.source ?: state.project?.orderedSources?.firstOrNull()
                    val isSourcePortrait = sourceMedia?.displaySize()?.let { it.height > it.width } ?: true
                    val effectiveAspectRatio = when (state.compositionSchema?.settings?.aspectRatio) {
                        "9:16" -> 9f / 16f
                        "1:1" -> 1f
                        "4:5" -> 4f / 5f
                        "16:9" -> if (isSourcePortrait) 9f / 16f else 16f / 9f
                        else -> if (isSourcePortrait) 9f / 16f else 16f / 9f
                    }

                    var isControlsVisible by remember { mutableStateOf(false) }

                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.40f)
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        val containerWidth = maxWidth
                        val containerHeight = maxHeight
                        val (videoWidth, videoHeight) = if ((containerWidth / containerHeight) > effectiveAspectRatio) {
                            (containerHeight * effectiveAspectRatio) to containerHeight
                        } else {
                            containerWidth to (containerWidth / effectiveAspectRatio)
                        }

                        if (state.source != null) {
                            // 1. PlayerView fills the entire container so ExoPlayer controls have full width.
                            // This ensures PlayerControlView has adequate width (>240dp), completely preventing
                            // minimal mode from hiding the rewind button, fast-forward button, and the bottom bar (time elapsed / total duration).
                            AndroidView(
                                factory = { ctx ->
                                    PlayerView(ctx).apply {
                                        this.player = player
                                        useController = true
                                        setShowMultiWindowTimeBar(true)
                                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                        setShowPreviousButton(false)
                                        setShowNextButton(false)
                                        setShowRewindButton(true)
                                        setShowFastForwardButton(true)
                                        controllerShowTimeoutMs = 3000
                                        controllerHideOnTouch = true
                                        setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                                            isControlsVisible = (visibility == View.VISIBLE)
                                        })
                                    }
                                },
                                update = { if (it.player != player) it.player = player },
                                modifier = Modifier.fillMaxSize()
                            )

                            // 2. On-screen subtitle/caption overlay:
                            // Strictly bounded within the 9:16 portrait video frame (videoWidth x videoHeight),
                            // and only visible when player controls are hidden so captions never display on top of or collide with controls.
                            val activeCaption = remember(playbackPositionMs, state.compositionSchema) {
                                val schema = state.compositionSchema
                                schema?.timeline?.filterIsInstance<TimelineNode.Text>()
                                    ?.firstOrNull { it.isVisible && playbackPositionMs in it.startMs..it.endMs }
                            }

                            if (!isControlsVisible && activeCaption != null) {
                                val density = LocalDensity.current
                                val scaleFactor = (videoWidth.value / 1000f)
                                val rawFontSize = activeCaption.style?.fontSize ?: 60f
                                val rawScale = activeCaption.style?.scale ?: 1f
                                val effectiveFontSize = (rawFontSize * rawScale * scaleFactor).coerceAtLeast(12f)
                                val fontSizeSp = effectiveFontSize.sp

                                val rawStrokeWidth = activeCaption.style?.strokeWidth ?: 4f
                                val strokeWidthPx = with(density) {
                                    val swDp = (rawStrokeWidth * rawScale * scaleFactor).dp
                                    swDp.toPx().coerceAtLeast(1.5f)
                                }

                                val defaultColorHex = activeCaption.style?.color ?: "#FFFFFF"
                                val baseTextColor = remember(defaultColorHex) {
                                    runCatching { Color(android.graphics.Color.parseColor(defaultColorHex)) }.getOrDefault(Color.White)
                                }

                                val strokeColorHex = activeCaption.style?.strokeColor ?: "#000000"
                                val strokeColor = remember(strokeColorHex) {
                                    runCatching { Color(android.graphics.Color.parseColor(strokeColorHex)) }.getOrDefault(Color.Black)
                                }

                                val annotatedText = remember(activeCaption.text, activeCaption.segments, baseTextColor) {
                                    if (!activeCaption.segments.isNullOrEmpty()) {
                                        buildAnnotatedString {
                                            activeCaption.segments.forEach { seg ->
                                                val segColor = seg.color?.let { c ->
                                                    runCatching { Color(android.graphics.Color.parseColor(c)) }.getOrNull()
                                                } ?: baseTextColor
                                                withStyle(SpanStyle(color = segColor)) {
                                                    append(seg.text)
                                                }
                                            }
                                        }
                                    } else null
                                }

                                val posX = (activeCaption.style?.posX ?: 0f).coerceIn(-1f, 1f)
                                val posY = (activeCaption.style?.posY ?: -0.35f).coerceIn(-1f, 1f)

                                val videoWidthPx = with(density) { videoWidth.toPx() }
                                val videoHeightPx = with(density) { videoHeight.toPx() }

                                // Exact 1:1 mathematical coordinate mapping matching TextGraphicOverlay (Android & iOS):
                                // posX: normalized horizontal anchor (-1.0 left, 0.0 center, 1.0 right) -> cx = width * (0.5 + posX / 2)
                                // posY: normalized vertical anchor (-1.0 bottom, 0.0 center, 1.0 top) -> cy = height * (0.5 - posY / 2)
                                val targetCenterX = videoWidthPx * (0.5f + posX / 2f)
                                val targetCenterY = videoHeightPx * (0.5f - posY / 2f)

                                Box(
                                    modifier = Modifier
                                        .width(videoWidth)
                                        .height(videoHeight)
                                        .clipToBounds()
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .layout { measurable, constraints ->
                                                val maxTextWidth = (videoWidthPx * 0.9f).roundToInt()
                                                val childConstraints = constraints.copy(
                                                    minWidth = 0,
                                                    maxWidth = maxTextWidth.coerceAtMost(constraints.maxWidth)
                                                )
                                                val placeable = measurable.measure(childConstraints)
                                                layout(constraints.maxWidth, constraints.maxHeight) {
                                                    val x = (targetCenterX - placeable.width / 2f).roundToInt()
                                                    val y = (targetCenterY - placeable.height / 2f).roundToInt()
                                                    placeable.placeRelative(x, y)
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            // Pass 1: Outline stroke (drawn behind)
                                            if (strokeWidthPx > 0f) {
                                                Text(
                                                    text = activeCaption.text,
                                                    color = strokeColor,
                                                    fontSize = fontSizeSp,
                                                    fontWeight = FontWeight.Bold,
                                                    textAlign = TextAlign.Center,
                                                    style = TextStyle(
                                                        drawStyle = Stroke(
                                                            width = strokeWidthPx,
                                                            join = StrokeJoin.Round
                                                        )
                                                    )
                                                )
                                            }
                                            // Pass 2: Main fill text + drop shadow (drawn on top, NO translucent background box)
                                            if (annotatedText != null) {
                                                Text(
                                                    text = annotatedText,
                                                    fontSize = fontSizeSp,
                                                    fontWeight = FontWeight.Bold,
                                                    textAlign = TextAlign.Center,
                                                    style = TextStyle(
                                                        drawStyle = Fill,
                                                        shadow = Shadow(
                                                            color = Color.Black.copy(alpha = 0.85f),
                                                            offset = Offset(0f, 2f),
                                                            blurRadius = 4f
                                                        )
                                                    )
                                                )
                                            } else {
                                                Text(
                                                    text = activeCaption.text,
                                                    color = baseTextColor,
                                                    fontSize = fontSizeSp,
                                                    fontWeight = FontWeight.Bold,
                                                    textAlign = TextAlign.Center,
                                                    style = TextStyle(
                                                        drawStyle = Fill,
                                                        shadow = Shadow(
                                                            color = Color.Black.copy(alpha = 0.85f),
                                                            offset = Offset(0f, 2f),
                                                            blurRadius = 4f
                                                        )
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            CircularProgressIndicator(color = Lavender)
                        }
                    }
                }

                // Middle: Conversation Area
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Ink)
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(state.messages, key = { it.id }) { msg ->
                            val target = vm.undoTarget(msg)
                            ChatBubbleItem(
                                message = msg,
                                onUndo = if (target != null) { { selectedUndoTarget = target } } else null
                            )
                        }

                        // Clarification Options Card
                        state.pendingClarification?.let { clarification ->
                            item {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    clarification.options.forEach { option ->
                                        val isRecommended = option.id == clarification.recommendedOptionId
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(Workspace)
                                                .border(
                                                    width = if (isRecommended) 1.5.dp else 1.dp,
                                                    color = if (isRecommended) Color(0xFFFACC15).copy(alpha = 0.8f) else Border,
                                                    shape = RoundedCornerShape(12.dp)
                                                )
                                                .clickable {
                                                    vm.answerClarification(
                                                        option.label
                                                    )
                                                }
                                                .padding(16.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = option.label,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Cream
                                            )
                                            if (isRecommended) {
                                                Icon(
                                                    imageVector = Icons.Default.Star,
                                                    contentDescription = "Recommended",
                                                    tint = Color(0xFFFACC15),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Processing fallback spinner
                        if (state.isProcessing && state.messages.none {
                            it.text.startsWith("Thinking") || it.text.startsWith("Editing")
                        }) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(Workspace)
                                        .border(1.dp, Border.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        color = Lavender,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = state.jobMessage ?: "Processing...",
                                        fontSize = 12.sp,
                                        color = Muted
                                    )
                                }
                            }
                        }
                    }
                }

                // Bottom: Input Area
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Ink,
                    tonalElevation = 0.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(bottom = 10.dp)
                    ) {
                        // Suggestion Chips Row
                        val suggestions = listOf(
                            "Make a clean, natural first cut",
                            "Turn into vertical short with captions",
                            "Keep best takes and remove mistakes",
                            "Cut awkward silences and pauses"
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            suggestions.forEach { suggestion ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(Workspace)
                                        .border(1.dp, Border.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                                        .clickable(enabled = !state.isProcessing) {
                                            prompt = suggestion
                                        }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = suggestion,
                                        fontSize = 12.sp,
                                        color = CreamRaised
                                    )
                                }
                            }
                        }

                        // Input Capsule
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .clip(RoundedCornerShape(26.dp))
                                .background(Workspace)
                                .border(1.dp, Border.copy(alpha = 0.6f), RoundedCornerShape(26.dp))
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Plus button inside capsule
                            IconButton(
                                onClick = {
                                    clipPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                    )
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Add video clip",
                                    tint = Lavender,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Text input field inside capsule
                            BasicTextField(
                                value = prompt,
                                onValueChange = { prompt = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                textStyle = TextStyle(
                                    color = CreamRaised,
                                    fontSize = 14.sp
                                ),
                                enabled = !state.isProcessing &&
                                    (state.pendingClarification == null || state.pendingClarification?.allowWriteIn == true),
                                singleLine = true,
                                decorationBox = { innerTextField ->
                                    if (prompt.isEmpty()) {
                                        Text(
                                            text = "Ask Trimm to change something...",
                                            color = Muted,
                                            fontSize = 14.sp
                                        )
                                    }
                                    innerTextField()
                                }
                            )

                            // Send up-arrow button inside capsule
                            val canSend = prompt.isNotBlank() && !state.isProcessing
                            IconButton(
                                onClick = {
                                    val text = prompt.trim()
                                    prompt = ""
                                    vm.reviseStory(text)
                                },
                                enabled = canSend,
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(if (canSend) Coral else Raised)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowUpward,
                                    contentDescription = "Send",
                                    tint = if (canSend) DarkText else Muted,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Exporting Modal Overlay
            if (state.isExporting) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.82f))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Raised)
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        Text(
                            text = "Exporting Video...",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Cream
                        )

                        LinearProgressIndicator(
                            progress = { state.exportProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = Coral,
                            trackColor = Workspace,
                        )

                        Text(
                            text = "${(state.exportProgress * 100).toInt()}%",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = Cream
                        )

                        Button(
                            onClick = { vm.cancelExport() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Workspace,
                                contentColor = Coral
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Cancel", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // Project Assets Bottom Sheet
            if (showAssetsModal) {
                ProjectAssetsBottomSheet(
                    sources = state.sources,
                    onDismiss = { showAssetsModal = false }
                )
            }



            // Undo Confirmation Dialog
            selectedUndoTarget?.let { target ->
                AlertDialog(
                    onDismissRequest = { selectedUndoTarget = null },
                    containerColor = Raised,
                    title = {
                        Text(
                            text = "Undo Edits",
                            color = Cream,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    text = {
                        Text(
                            text = "“${target.userPrompt}”\n\nAre you sure you want to undo? All video edits and chat messages from this point forward will be removed.",
                            color = CreamRaised
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                vm.undoTurn(target)
                                selectedUndoTarget = null
                            }
                        ) {
                            Text("Undo", color = Coral, fontWeight = FontWeight.SemiBold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { selectedUndoTarget = null }) {
                            Text("Cancel", color = Cream)
                        }
                    }
                )
            }

            // Error Message Dialog
            state.errorMessage?.let { errorMsg ->
                AlertDialog(
                    onDismissRequest = { vm.clearErrorMessage() },
                    containerColor = Raised,
                    title = {
                        Text("Error", color = Cream, fontWeight = FontWeight.Bold)
                    },
                    text = {
                        Text(errorMsg, color = CreamRaised)
                    },
                    confirmButton = {
                        TextButton(onClick = { vm.clearErrorMessage() }) {
                            Text("OK", color = Cream, fontWeight = FontWeight.SemiBold)
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatBubbleItem(
    message: UiChatMessage,
    onUndo: (() -> Unit)? = null
) {
    val isThinkingOrEditing = message.text.startsWith("Thinking") || message.text.startsWith("Editing")
    val clipboardManager = LocalClipboardManager.current
    var showContextMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        if (message.isUser) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (onUndo != null) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onUndo
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(Workspace)
                                .border(1.dp, Border.copy(alpha = 0.5f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Undo,
                                contentDescription = "Undo this edit",
                                tint = Lavender.copy(alpha = 0.85f),
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Raised)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { showContextMenu = true }
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = message.text,
                        fontSize = 15.sp,
                        color = Cream,
                        lineHeight = 20.sp
                    )

                    DropdownMenu(
                        expanded = showContextMenu,
                        onDismissRequest = { showContextMenu = false },
                        modifier = Modifier.background(Raised)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Copy", color = Cream) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = null,
                                    tint = Cream
                                )
                            },
                            onClick = {
                                showContextMenu = false
                                clipboardManager.setText(AnnotatedString(message.text))
                            }
                        )
                        if (onUndo != null) {
                            DropdownMenuItem(
                                text = { Text("Undo this edit", color = Coral) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Undo,
                                        contentDescription = null,
                                        tint = Coral
                                    )
                                },
                                onClick = {
                                    showContextMenu = false
                                    onUndo()
                                }
                            )
                        }
                    }
                }
            }
        } else if (isThinkingOrEditing) {
            ThinkingShimmerView(text = message.text)
        } else {
            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Workspace)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { showContextMenu = true }
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                AnimatedResponseText(
                    text = message.text,
                    color = Cream,
                    fontSize = 15.sp,
                    lineHeight = 20.sp
                )

                DropdownMenu(
                    expanded = showContextMenu,
                    onDismissRequest = { showContextMenu = false },
                    modifier = Modifier.background(Raised)
                ) {
                    DropdownMenuItem(
                        text = { Text("Copy", color = Cream) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                tint = Cream
                            )
                        },
                        onClick = {
                            showContextMenu = false
                            clipboardManager.setText(AnnotatedString(message.text))
                        }
                    )
                    if (onUndo != null) {
                        DropdownMenuItem(
                            text = { Text("Undo this edit", color = Coral) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Undo,
                                    contentDescription = null,
                                    tint = Coral
                                )
                            },
                            onClick = {
                                showContextMenu = false
                                onUndo()
                            }
                        )
                    }
                }
            }
        }
    }
}
