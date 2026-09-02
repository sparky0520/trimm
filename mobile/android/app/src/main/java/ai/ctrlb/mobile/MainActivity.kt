package ai.ctrlb.mobile

import ai.ctrlb.mobile.domain.*
import ai.ctrlb.mobile.media.ExportCoordinator
import ai.ctrlb.mobile.media.DeviceCapability
import ai.ctrlb.mobile.work.ExportWorker
import ai.ctrlb.mobile.planner.AgentRecoveryAction
import ai.ctrlb.mobile.planner.AgentRecoveryStatus
import ai.ctrlb.mobile.planner.AgentUsageSummary
import ai.ctrlb.mobile.planner.AgentDecisionMapper
import ai.ctrlb.mobile.planner.ClarificationQuestion
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Bundle
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.delay
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.auth
import ai.ctrlb.mobile.network.supabase
import ai.ctrlb.mobile.auth.signInWithGoogle
import android.app.Activity
import androidx.media3.common.MediaItem
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Trimm's deliberately restrained, shadcn-inspired dark palette.
private val Ink = Color(0xFF09090B)
private val Workspace = Color(0xFF18181B)
private val Raised = Color(0xFF27272A)
private val Border = Color(0xFF3F3F46)
private val Cream = Color(0xFFFAFAFA)
private val CreamRaised = Color(0xFFF4F4F5)
private val DarkText = Color(0xFF09090B)
private val Muted = Color(0xFFA1A1AA)
private val Coral = Color(0xFFFAFAFA)
private val Lavender = Color(0xFFD4D4D8)
private val Green = Color(0xFFE4E4E7)
private val UserBlue = Color(0xFF1B365D)

class MainActivity : ComponentActivity() {
    private val appUpdateManager by lazy { com.google.android.play.core.appupdate.AppUpdateManagerFactory.create(this) }
    private val updateType = com.google.android.play.core.install.model.AppUpdateType.FLEXIBLE
    private val updateRequestCode = 1001

    private fun checkForAppUpdates() {
        appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
            val isUpdateAvailable = info.updateAvailability() == com.google.android.play.core.install.model.UpdateAvailability.UPDATE_AVAILABLE
            val isUpdateAllowed = info.isUpdateTypeAllowed(updateType)
            if (isUpdateAvailable && isUpdateAllowed) {
                try {
                    appUpdateManager.startUpdateFlowForResult(info, updateType, this, updateRequestCode)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed to start update flow", e)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
            if (info.installStatus() == com.google.android.play.core.install.model.InstallStatus.DOWNLOADED) {
                appUpdateManager.completeUpdate()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) {
            checkForAppUpdates()
        }
        Log.i("DeviceCapability", DeviceCapability.report(this).diagnosticSummary())
        if (BuildConfig.FORCE_4K_EXPORT) {
            intent.getStringExtra("forceExportProjectId")?.takeIf(String::isNotBlank)?.let { projectId ->
                Log.w("ExportWorker", "Enqueuing debug-only forced 4K export for project $projectId")
                WorkManager.getInstance(this).enqueue(
                    OneTimeWorkRequestBuilder<ExportWorker>()
                        .setInputData(workDataOf("projectId" to projectId))
                        .addTag("forced-4k-export:$projectId")
                        .build(),
                )
            }
        }
        enableEdgeToEdge()
        setContent { TrimmTheme { Surface(Modifier.fillMaxSize(), color = Ink) { AppWithDrawer() } } }
    }
}

@Composable private fun AppWithDrawer(vm: EditorViewModel = viewModel()) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Color(0xFF171717),
                drawerContentColor = Cream,
                modifier = Modifier.width(300.dp)
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp).statusBarsPadding(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Trimm",
                                color = Cream,
                                fontFamily = FontFamily.SansSerif,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = (-1).sp,
                                maxLines = 1,
                            )
                        }
                        IconButton(onClick = {
                            vm.loadProject(java.util.UUID.randomUUID().toString())
                            scope.launch { drawerState.close() }
                        }) { Text("+", fontSize = 24.sp, color = Cream) }
                    }
                    val state by vm.state.collectAsStateWithLifecycle()
                    LazyColumn(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        item { Text("Past Chats", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(8.dp)) }
                        items(state.pastChats.size) { index ->
                            val pastChat = state.pastChats[index]
                            TextButton(
                                onClick = { 
                                    vm.loadProject(pastChat.id)
                                    scope.launch { drawerState.close() }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                                    ChatTitleText(pastChat)
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = Color.DarkGray)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        AccountControl(LocalContext.current as Activity)
                    }
                }
            }
        }
    ) {
        TrimmApp(vm, onOpenDrawer = { scope.launch { drawerState.open() } })
    }
}

@Composable fun ChatTitleText(pastChat: ai.ctrlb.mobile.data.ChatSummary) {
    val cleanTitle = pastChat.title

    var displayedText by rememberSaveable(cleanTitle) { mutableStateOf("") }
    LaunchedEffect(cleanTitle) {
        if (displayedText != cleanTitle) {
            displayedText = ""
            for (char in cleanTitle) {
                displayedText += char
                delay(30)
            }
        } else {
            displayedText = cleanTitle
        }
    }
    Text(displayedText.takeIf { it.isNotBlank() } ?: "Untitled Chat", color = Cream)
}

@Composable private fun TrimmApp(vm: EditorViewModel, onOpenDrawer: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var isOnline by rememberSaveable { mutableStateOf(true) }
    var showBackOnline by rememberSaveable { mutableStateOf(false) }
    var wasOffline by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(isOnline) {
        if (!isOnline) {
            wasOffline = true
            showBackOnline = false
        } else if (wasOffline) {
            showBackOnline = true
            wasOffline = false
        }
    }

    LaunchedEffect(showBackOnline) {
        if (showBackOnline) {
            delay(3000)
            showBackOnline = false
        }
    }

    DisposableEffect(context) {
        val connectivityManager = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { isOnline = true }
            override fun onLost(network: android.net.Network) { isOnline = false }
        }
        val request = android.net.NetworkRequest.Builder()
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)
        
        val activeNetwork = connectivityManager.activeNetwork
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
        isOnline = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        onDispose { connectivityManager.unregisterNetworkCallback(callback) }
    }

    Box(Modifier.fillMaxSize()) {
        when {
            state.source == null -> ImportScreen(LocalContext.current as Activity, vm::import, onOpenDrawer)
            state.project != null -> EditorScreen(
                state, vm::undo, vm::export, vm::applyProposal, vm::rejectProposal,
                vm::undoCheckpoint,
                vm::reviseStory, vm::retryTurn, vm::switchBranch, vm::recoverAgent, vm::cancelAnalysis,
                vm::relinkVideo,
                isOnline,
                onOpenDrawer
            )
            else -> NewEditChatScreen(state, vm::generateDraft, vm::moveClip, vm::relinkVideo, isOnline, onOpenDrawer)
        }

        if (!isOnline) {
            Box(Modifier.fillMaxWidth().background(Coral).statusBarsPadding().padding(8.dp), contentAlignment = Alignment.Center) {
                Text("You're offline. AI features are disabled.", color = DarkText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        } else if (showBackOnline) {
            Box(Modifier.fillMaxWidth().background(Color(0xFF4ADE80)).statusBarsPadding().padding(8.dp), contentAlignment = Alignment.Center) {
                Text("Back online.", color = DarkText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable private fun BrandBar(
    actionLabel: String? = null,
    actionEnabled: Boolean = true,
    onAction: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    onOpenDrawer: (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onOpenDrawer != null) {
            IconButton(
                onClick = onOpenDrawer,
                modifier = Modifier.border(1.dp, Border, RoundedCornerShape(percent = 50))
            ) {
                Text("☰", fontSize = 24.sp, color = Cream)
            }
        }
        Spacer(Modifier.weight(1f))

        if (secondaryLabel != null && onSecondary != null) {
            TextButton(
                onClick = onSecondary,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) { Text(secondaryLabel, color = Cream, fontSize = 13.sp) }
        }
        if (actionLabel != null && onAction != null) {
            Button(
                onClick = onAction,
                enabled = actionEnabled,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = DarkText),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(actionLabel, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}

@Composable private fun AccountControl(activity: Activity) {
    val sessionStatus by supabase.auth.sessionStatus.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showMenu by remember { mutableStateOf(false) }

    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { if (sessionStatus is SessionStatus.Authenticated) showMenu = true }
        ) {
            if (BuildConfig.SUPABASE_PUBLISHABLE_KEY.isBlank()) {
                Text("Offline Mode", color = Cream)
            } else {
                when (sessionStatus) {
                    is SessionStatus.Authenticated -> {
                        val user = supabase.auth.currentUserOrNull()
                        val avatarUrl = user?.userMetadata?.get("avatar_url")?.jsonPrimitive?.content
                        val name = user?.userMetadata?.get("full_name")?.jsonPrimitive?.content ?: user?.userMetadata?.get("name")?.jsonPrimitive?.content ?: "User"

                        if (avatarUrl != null) {
                            coil.compose.AsyncImage(
                                model = avatarUrl,
                                contentDescription = "Profile Picture",
                                modifier = Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape)
                            )
                        } else {
                            Box(Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Border))
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(name, color = Cream, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    }
                    else -> TextButton(
                        onClick = { scope.launch { signInWithGoogle(activity) } },
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("Sign in", color = Cream) }
                }
            }
        }
        
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            val user = supabase.auth.currentUserOrNull()
            val email = user?.email
            if (email != null) {
                DropdownMenuItem(
                    text = { Text(email, color = Muted, fontSize = 12.sp) },
                    onClick = { },
                    enabled = false
                )
            }
            DropdownMenuItem(
                text = { Text("Sign out") },
                onClick = { 
                    showMenu = false
                    scope.launch { supabase.auth.signOut() }
                }
            )
        }
    }
}

@Composable private fun ImportScreen(activity: Activity, onImport: (List<Uri>) -> Unit, onOpenDrawer: (() -> Unit)? = null) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) onImport(it) }
    val sessionStatus by supabase.auth.sessionStatus.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize().background(Ink).statusBarsPadding().navigationBarsPadding()) {
        if (onOpenDrawer != null) {
            IconButton(
                onClick = onOpenDrawer,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 16.dp, top = 8.dp)
                    .border(1.dp, Border, RoundedCornerShape(percent = 50))
            ) {
                Text("☰", fontSize = 24.sp, color = Cream)
            }
        }
        Column(Modifier.align(Alignment.Center).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(id = R.drawable.trimm_launcher),
                contentDescription = "Trimm Logo",
                modifier = Modifier.size(108.dp)
            )
            Spacer(Modifier.height(48.dp))
            Text("START A NEW EDIT", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            Spacer(Modifier.height(16.dp))
            Text("Turn a rough recording\ninto something worth sharing.", color = CreamRaised, fontSize = 26.sp,
                lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Text("Bring in your talking-head and product clips,\nthen shape them naturally with AI.", color = Muted, lineHeight = 22.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(32.dp))
            
            if (BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank() && sessionStatus !is SessionStatus.Authenticated) {
                Image(
                    painter = painterResource(id = R.drawable.btn_google_signin),
                    contentDescription = "Sign in with Google",
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = androidx.compose.ui.semantics.Role.Button,
                            onClick = { scope.launch { signInWithGoogle(activity) } }
                        )
                )
            } else {
                Button(onClick = { launcher.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = DarkText)) {
                    Text("Select clips to edit", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                }
            }
        }
        Text("YOUR VIDEO STAYS ON DEVICE", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp))
    }
}

@Composable private fun NewEditChatScreen(state: EditorState, createDraft: (String) -> Unit, moveClip: (Int, Int) -> Unit, onRelink: (Uri) -> Unit, isOnline: Boolean, onOpenDrawer: () -> Unit) {
    val relinkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { if (it != null) onRelink(it) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf("") }
    val suggestions = listOf(
        "Make a clean, natural first cut",
        "Turn this into a tight vertical short with captions",
        "Keep the best takes and remove mistakes",
    )
    Column(Modifier.fillMaxSize().background(Ink)) {
        BrandBar(onOpenDrawer = onOpenDrawer)
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Box(Modifier.fillMaxWidth().background(Workspace, RoundedCornerShape(18.dp)).border(1.dp, Border, RoundedCornerShape(18.dp)).padding(10.dp)) {
                    val first = state.sources.firstOrNull()
                    if (first?.uri.isNullOrEmpty()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(32.dp)) {
                            Text("Missing Video File", color = Coral, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Spacer(Modifier.height(4.dp))
                            Text("This video is no longer on your device.", color = Muted, fontSize = 12.sp)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { relinkLauncher.launch(arrayOf("video/*")) },
                                colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = DarkText)) {
                                Text("Relink Video")
                            }
                        }
                    } else {
                        VideoPreview(state.sources.map { it.uri }, Modifier.fillMaxWidth().aspectRatio(first!!.displaySize().aspectRatio))
                        SmallAction("Expand", Modifier.align(Alignment.TopEnd).padding(8.dp)) { fullscreen = true }
                    }
                }
            }
            item { ClipOrder(state.sources, moveClip) }
            item {
                Column(
                    Modifier.fillMaxWidth(.88f).background(Raised, RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp)).padding(14.dp)
                ) {
                    Text("Trimm", color = Lavender, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "I have ${state.sources.size} clip${if (state.sources.size == 1) "" else "s"}. What should the finished video feel like? Mention the audience, length, format, captions, music, or anything that must stay.",
                        color = CreamRaised, lineHeight = 20.sp
                    )
                }
            }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestions.forEach { suggestion ->
                        SuggestionChip(
                            onClick = { message = suggestion },
                            label = { Text(suggestion, fontSize = 11.sp) },
                            colors = SuggestionChipDefaults.suggestionChipColors(containerColor = Workspace),
                            border = null
                        )
                    }
                }
            }
        }
        Surface(color = Color.Transparent) {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it.take(2_000) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Describe the edit you want…") },
                    minLines = 1,
                    maxLines = 5,
                    enabled = isOnline,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = CreamRaised, unfocusedTextColor = CreamRaised,
                        focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Workspace, unfocusedContainerColor = Workspace
                    )
                )
                Button(
                    onClick = { createDraft(message) },
                    enabled = message.isNotBlank() && isOnline,
                    modifier = Modifier.height(56.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = DarkText),
                ) { Text("Send", fontWeight = FontWeight.Bold) }
            }
        }
    }
    if (fullscreen) FullscreenPreview(state.sources.map { it.uri }) { fullscreen = false }
}

@Composable private fun <T> Choice(label: String, values: List<T>, selected: T, select: (T) -> Unit) {
    Text(label, color = Cream, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { value ->
            val chosen = value == selected
            FilterChip(selected = chosen, onClick = { select(value) }, label = { Text(value.toString().lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }) },
                shape = RoundedCornerShape(9.dp), colors = FilterChipDefaults.filterChipColors(containerColor = Raised, labelColor = Muted, selectedContainerColor = Coral.copy(alpha = .18f), selectedLabelColor = Cream),
                border = FilterChipDefaults.filterChipBorder(enabled = true, selected = chosen, borderColor = Border, selectedBorderColor = Coral))
        }
    }
    Spacer(Modifier.height(20.dp))
}

private sealed interface ChatElement {
    val timestamp: Long
    data class InitialRequest(override val timestamp: Long, val request: String) : ChatElement
    data class Findings(override val timestamp: Long, val read: ai.ctrlb.mobile.domain.EditorialRead) : ChatElement
    data class Turn(override val timestamp: Long, val turn: ai.ctrlb.mobile.domain.EditorialConversationTurn) : ChatElement
    data class Checkpoint(override val timestamp: Long, val checkpoint: ai.ctrlb.mobile.domain.AppliedEditCheckpoint) : ChatElement
}

@Composable private fun EditorScreen(
    state: EditorState,
    undo: () -> Unit,
    export: () -> Unit,
    applyProposal: () -> Unit,
    rejectProposal: () -> Unit,
    undoCheckpoint: (String) -> Unit,
    reviseStory: (String) -> Unit,
    retryTurn: (String) -> Unit,
    switchBranch: (String) -> Unit,
    recoverAgent: (AgentRecoveryAction) -> Unit,
    cancelAnalysis: () -> Unit,
    onRelink: (Uri) -> Unit,
    isOnline: Boolean,
    onOpenDrawer: () -> Unit,
) {
    val relinkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { if (it != null) onRelink(it) }
    val project = requireNotNull(state.project)
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var showVideo by rememberSaveable { mutableStateOf(false) }
    var request by rememberSaveable { mutableStateOf("") }
    val read = EditorialReviewBuilder.editorialRead(project)
    val review = EditorialReviewBuilder.proposal(project)
    val followUpSuggestions = listOf(
        "Keep only the best takes",
        "Remove silent pauses & stumbles",
        "Add punchy captions for TikTok",
        "Make the pace faster"
    )

    Column(Modifier.fillMaxSize().background(Ink)) {
        BrandBar(
            actionLabel = "Export ${minOf(project.export.width, project.export.height)}p",
            onAction = export,
            secondaryLabel = if (showVideo) "Hide Video" else "Show Video",
            onSecondary = { showVideo = !showVideo },
            onOpenDrawer = onOpenDrawer
        )
        if (showVideo) {
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp).background(Workspace, RoundedCornerShape(18.dp)).border(1.dp, Border, RoundedCornerShape(18.dp))) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("YOUR VIDEO", color = Coral, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text(if (state.previewUri == null) "Original clips" else "Current edit", color = CreamRaised, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                    SmallAction("Expand") { fullscreen = true }
                }
                val ratio = project.export.width.toFloat() / project.export.height
                Box(Modifier.fillMaxWidth().padding(10.dp).background(Ink, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    if (state.sources.firstOrNull()?.uri.isNullOrEmpty()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                            Text("Missing Video File", color = Coral, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Spacer(Modifier.height(4.dp))
                            Text("This video is no longer on your device.", color = Muted, fontSize = 12.sp)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { relinkLauncher.launch(arrayOf("video/*")) },
                                colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = DarkText)) {
                                Text("Relink Video")
                            }
                        }
                    } else {
                        CanonicalVideoPreview(project, Modifier.fillMaxWidth().aspectRatio(ratio))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("00:00", color = Muted, fontSize = 10.sp)
                    Text("${project.orderedSources.size} CLIPS  •  ${project.preferences.aspectRatio}", color = Muted, fontSize = 9.sp, letterSpacing = .6.sp)
                }
            }
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val chatElements = buildList {
                state.initialRequest
                    ?.takeIf { initial -> project.analysis.revisionConversation.none { it.request == initial } }
                    ?.let { initial -> add(ChatElement.InitialRequest(0L, initial)) }

                project.analysis.completedAtEpochMs?.let { time ->
                    if (read.technicalProblems.isNotEmpty()) {
                        add(ChatElement.Findings(time, read))
                    }
                }

                project.analysis.activeConversationPath.forEach { turn ->
                    add(ChatElement.Turn(turn.createdAtEpochMs, turn))
                }
                
                project.analysis.activeCheckpointPath.forEach { checkpoint ->
                    add(ChatElement.Checkpoint(checkpoint.createdAtEpochMs, checkpoint))
                }
            }.sortedBy { it.timestamp }

            items(chatElements, key = { it.hashCode() }) { element ->
                when (element) {
                    is ChatElement.InitialRequest -> {
                        UserChatBubble(element.request)
                    }
                    is ChatElement.Findings -> {
                        FindingsChatBubble(element.read)
                    }
                    is ChatElement.Turn -> {
                        val turn = element.turn
                        val siblings = project.analysis.revisionConversation.filter { it.parentId == turn.parentId }
                        val index = siblings.indexOf(turn)
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Box(modifier = Modifier.weight(1f)) {
                                    UserChatBubble(turn.request)
                                }
                                if (siblings.size > 1) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
                                        TextButton(
                                            onClick = { if (index > 0) switchBranch(siblings[index - 1].id) },
                                            enabled = index > 0,
                                            contentPadding = PaddingValues(0.dp)
                                        ) { Text("<", color = if (index > 0) Cream else Muted, fontSize = 12.sp) }
                                        Text("${index + 1} / ${siblings.size}", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp))
                                        TextButton(
                                            onClick = { if (index < siblings.size - 1) switchBranch(siblings[index + 1].id) },
                                            enabled = index < siblings.size - 1,
                                            contentPadding = PaddingValues(0.dp)
                                        ) { Text(">", color = if (index < siblings.size - 1) Cream else Muted, fontSize = 12.sp) }
                                    }
                                }
                            }
                            AssistantChatBubble(accent = if (turn.isError) Color(0xFFE4E4E7) else Lavender) {
                                when {
                                    turn.isPending -> {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Lavender)
                                            Text(turn.response, color = CreamRaised, lineHeight = 20.sp)
                                        }
                                    }
                                    turn.isError -> {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(formatUserFacingError(turn.response), color = CreamRaised, lineHeight = 20.sp, fontSize = 13.sp)
                                            Button(
                                                onClick = { retryTurn(turn.id) },
                                                shape = RoundedCornerShape(8.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = Raised, contentColor = Cream),
                                                border = BorderStroke(1.dp, Border),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    Text("↻", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Coral)
                                                    Text("Retry request", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                }
                                            }
                                        }
                                    }
                                    else -> {
                                        Text(turn.response, color = CreamRaised, lineHeight = 20.sp)
                                    }
                                }
                            }
                        }
                    }
                    is ChatElement.Checkpoint -> {
                        val checkpoint = element.checkpoint
                        EditResultChatBubble(
                            checkpoint = checkpoint,
                            canUndo = project.analysis.appliedEditCheckpoints.lastOrNull()?.id == checkpoint.id,
                            undo = { undoCheckpoint(checkpoint.id) }
                        )
                    }
                }
            }

            project.analysis.agentRun?.let { run ->
                AgentDecisionMapper.clarification(run).takeIf { it.isNotEmpty() }?.let { questions ->
                    item(key = "clarification-${run.runId}-${run.events.size}") {
                        AgentClarificationCard(questions, state.isProcessing, reviseStory)
                    }
                }
                run.recovery?.let { recovery ->
                    items(recovery.conversation, key = { "recovery-${it.createdAt}-${it.action}" }) { turn ->
                        UserChatBubble(turn.message)
                    }
                    if (recovery.reviewRequested) {
                        item {
                            AssistantChatBubble(accent = Coral) {
                                Text("Response-format issue", color = CreamRaised, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(6.dp))
                                Text(recovery.issue, color = Muted, lineHeight = 19.sp)
                            }
                        }
                    }
                    if (recovery.status == AgentRecoveryStatus.REPAIR_FAILED) {
                        item { AgentRecoveryCard(recoverAgent) }
                    } else if (recovery.status == AgentRecoveryStatus.RESOLVED) {
                        item {
                            AssistantChatBubble(accent = Green) {
                                Text("Repaired proposal ready to review", color = CreamRaised, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    run.decision["summary"]?.jsonPrimitive?.content
                                        ?: "The final canonical proposal passed validation.",
                                    color = Muted,
                                    lineHeight = 19.sp
                                )
                            }
                        }
                    }
                }
                if (run.usage.calls.isNotEmpty()) {
                    item(key = "agent-usage-${run.runId}-${run.usage.totalTokens}") {
                        AgentUsageChatBubble(run.usage)
                    }
                }
            }
            if (review != null) {
                item { ProposalChatBubble(review, applyProposal, rejectProposal) }
            }
            if (!state.isProcessing && project.analysis.agentPlan != null) {
                item(key = "agent-plan-${project.analysis.agentRun?.runId}") {
                    AgentPlanBubble(project.analysis.agentPlan, project.analysis.agentRun)
                }
            }
            if (state.isProcessing && project.analysis.revisionConversation.none { it.isPending }) {
                state.jobMessage?.let { message ->
                    item(key = "job-status") {
                        AssistantChatBubble(accent = Lavender) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Lavender)
                                Text(message, color = CreamRaised, modifier = Modifier.weight(1f), fontSize = 13.sp)
                                TextButton(
                                    onClick = cancelAnalysis,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("Cancel", color = Coral, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
            state.errorMessage?.let { errorMsg ->
                item(key = "job-error") {
                    AssistantChatBubble(accent = Coral) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("⚠️", fontSize = 16.sp)
                            Text(errorMsg, color = CreamRaised, modifier = Modifier.weight(1f), fontSize = 13.sp)
                        }
                    }
                }
            }

        }
        Surface(color = Color.Transparent) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    followUpSuggestions.forEach { suggestion ->
                        SuggestionChip(
                            onClick = { if (isOnline) request = suggestion },
                            label = { Text(suggestion, fontSize = 11.sp, color = Cream) },
                            colors = SuggestionChipDefaults.suggestionChipColors(containerColor = Workspace),
                            border = null,
                            enabled = isOnline
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = request,
                        onValueChange = { request = it.take(2_000) },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Ask Trimm for changes or edits…") },
                        maxLines = 5,
                        enabled = isOnline,
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = CreamRaised, unfocusedTextColor = CreamRaised,
                            focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
                            focusedContainerColor = Workspace, unfocusedContainerColor = Workspace
                        )
                    )
                    Button(
                        onClick = {
                            val promptToSend = request
                            request = ""
                            reviseStory(promptToSend)
                        },
                        enabled = request.isNotBlank() && !state.isProcessing,
                        modifier = Modifier.height(56.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = DarkText)
                    ) { Text("Send", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
    if (fullscreen) FullscreenProjectPreview(project) { fullscreen = false }
}

@Composable private fun AgentUsageChatBubble(usage: AgentUsageSummary) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    AssistantChatBubble(accent = Lavender) {
        Text("Usage", color = Lavender, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Spacer(Modifier.height(5.dp))
        Text(
            "${usage.totalTokens} tokens  •  ${formatUsdMicros(usage.estimatedCostMicros)}",
            color = CreamRaised,
            fontWeight = FontWeight.Bold
        )
        Text(
            "${usage.inputTokens} input  •  ${usage.outputTokens} output  •  ${usage.thinkingTokens} thinking",
            color = Muted,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            "${usage.calls.size} model call${if (usage.calls.size == 1) "" else "s"}  •  ${if (expanded) "Hide details" else "Show details"}",
            color = Muted,
            fontSize = 9.sp,
            modifier = Modifier.padding(top = 3.dp).clickable { expanded = !expanded }
        )
        if (expanded) usage.calls.forEach { call ->
            val range = if (call.startMs != null && call.endMs != null) "  ${call.startMs / 1000.0}-${call.endMs / 1000.0}s ${call.resolution.orEmpty()}" else ""
            Text(
                "${call.purpose}$range\n${call.totalTokens} tokens  •  ${formatUsdMicros(call.estimatedCostMicros)}${if (call.costEstimated) " estimated" else ""}",
                color = Muted, fontSize = 9.sp, modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

private fun formatUsdMicros(micros: Long): String =
    "$" + String.format(java.util.Locale.US, "%.4f", micros / 1_000_000.0)

private fun formatUserFacingError(raw: String): String {
    return when {
        "entitlement_required" in raw || "Entitlement required" in raw ->
            "Subscription or entitlement required. Sign in or upgrade your account to continue using cloud AI runs."
        "column" in raw && "does not exist" in raw ->
            "Database schema update required. Please retry your request."
        "timed out" in raw.lowercase() || "timeout" in raw.lowercase() ->
            "The request timed out while waiting for the cloud AI agent. If the backend was sleeping, retrying now will succeed."
        ("Agent run" in raw || raw.startsWith("Agent run failed")) && "{" in raw -> {
            val jsonPart = raw.substringAfter("{", "").let { if (it.isNotBlank()) "{$it" else "" }
            runCatching {
                val json = Json.parseToJsonElement(jsonPart).jsonObject
                val msg = json["error"]?.jsonPrimitive?.content ?: json["message"]?.jsonPrimitive?.content
                if (!msg.isNullOrBlank()) "Agent run failure: $msg" else raw
            }.getOrElse { raw }
        }
        else -> raw
    }
}

@Composable private fun UserChatBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = UserBlue,
            shape = RoundedCornerShape(12.dp, 12.dp, 4.dp, 12.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Text(
                text,
                color = CreamRaised,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable private fun AssistantChatBubble(accent: Color = Lavender, content: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(
            Modifier.fillMaxWidth(.92f).background(Raised, RoundedCornerShape(4.dp, 12.dp, 12.dp, 12.dp))
                .border(1.dp, Border, RoundedCornerShape(4.dp, 12.dp, 12.dp, 12.dp)).padding(14.dp)
        ) {
            Text("Trimm", color = accent, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            Spacer(Modifier.height(5.dp))
            content()
        }
    }
}

@Composable private fun FindingsChatBubble(read: ai.ctrlb.mobile.domain.EditorialRead) {
    AssistantChatBubble {
        ChatFinding("Things to fix", read.technicalProblems)
    }
}

@Composable private fun ChatFinding(label: String, lines: List<String>) {
    Text(label, color = Green, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
    lines.forEach { Text("• $it", color = Muted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 3.dp)) }
}

@Composable private fun ProposalChatBubble(review: ProposalReview, apply: () -> Unit, reject: () -> Unit) {
    AssistantChatBubble(accent = Coral) {
        Text(review.summary, color = CreamRaised, fontWeight = FontWeight.Bold, lineHeight = 20.sp)
        Spacer(Modifier.height(8.dp))
        Text("${formatDuration(review.beforeDurationMs)} → ${formatDuration(review.afterDurationMs)}", color = Coral, fontWeight = FontWeight.Bold)
        review.removals.take(4).forEach { Text("• $it", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
        review.warnings.forEach { Text("Note: $it", color = Lavender, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp)) }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = reject, modifier = Modifier.weight(1f), border = BorderStroke(1.dp, Border)) { Text("Not this", color = Cream) }
            Button(
                onClick = apply,
                enabled = review.warnings.none { it.startsWith("This proposal targets revision") },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = DarkText)
            ) { Text("Apply edit", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable private fun CreatorPreferencesCard(
    profile: CreatorPreferenceProfile,
    record: (CreatorPreferenceKind, String) -> Unit,
    setEnabled: (CreatorPreferenceKind, String, Boolean) -> Unit,
    reset: (CreatorPreferenceKind?, String?) -> Unit
) {
    var kind by rememberSaveable { mutableStateOf(CreatorPreferenceKind.TERMINOLOGY) }
    var value by rememberSaveable { mutableStateOf("") }
    ReviewSurface("CREATOR PROFILE", "Your reusable preferences") {
        Text("Visible taste settings only. The current brief and footage evidence always win.", color = Muted, fontSize = 11.sp)
        Spacer(Modifier.height(10.dp))
        Choice("Preference type", CreatorPreferenceKind.entries, kind) { kind = it }
        OutlinedTextField(
            value = value, onValueChange = { value = it.take(500) }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Asset, term, rule, pace, or motion limit") },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = CreamRaised, unfocusedTextColor = CreamRaised,
                focusedBorderColor = Lavender, unfocusedBorderColor = Border
            )
        )
        Button(
            onClick = { record(kind, value); value = "" }, enabled = value.isNotBlank(),
            modifier = Modifier.align(Alignment.End),
            colors = ButtonDefaults.buttonColors(containerColor = Lavender, contentColor = Ink)
        ) { Text("Save explicit preference") }
        profile.entries.forEach { entry ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${entry.kind.name.lowercase().replace('_', ' ')}: ${entry.value}", color = Cream, fontSize = 11.sp)
                    Text(
                        "${if (entry.active) "Active" else "Learning"} • ${entry.confirmations} confirmation(s) • decisions ${entry.decisionIds.joinToString()}",
                        color = Muted, fontSize = 9.sp
                    )
                }
                Switch(entry.enabled, { setEnabled(entry.kind, entry.value, it) })
                TextButton(onClick = { reset(entry.kind, entry.value) }) { Text("Reset", color = Coral, fontSize = 10.sp) }
            }
        }
        if (profile.entries.isNotEmpty()) {
            OutlinedButton(onClick = { reset(null, null) }, modifier = Modifier.align(Alignment.End), border = BorderStroke(1.dp, Border)) {
                Text("Reset all", color = Cream)
            }
        }
    }
}

@Composable private fun ConversationalRevisionCard(
    project: MobileProject, 
    revise: (String) -> Unit,
    switchBranch: (String) -> Unit
) {
    var request by rememberSaveable { mutableStateOf("") }
    ReviewSurface("REVISE", "Keep editing conversationally") {
        project.analysis.activeConversationPath.takeLast(3).forEach { turn ->
            val siblings = project.analysis.revisionConversation.filter { it.parentId == turn.parentId }
            val index = siblings.indexOf(turn)
            
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(turn.request, color = CreamRaised, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.weight(1f))
                if (siblings.size > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { if (index > 0) switchBranch(siblings[index - 1].id) },
                            enabled = index > 0,
                            contentPadding = PaddingValues(0.dp)
                        ) { Text("<", color = if (index > 0) Cream else Muted, fontSize = 12.sp) }
                        
                        Text("${index + 1} / ${siblings.size}", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp))
                        
                        TextButton(
                            onClick = { if (index < siblings.size - 1) switchBranch(siblings[index + 1].id) },
                            enabled = index < siblings.size - 1,
                            contentPadding = PaddingValues(0.dp)
                        ) { Text(">", color = if (index < siblings.size - 1) Cream else Muted, fontSize = 12.sp) }
                    }
                }
            }
            Text(turn.response, color = Muted, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
        }
        OutlinedTextField(
            value = request,
            onValueChange = { request = it.take(2_000) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Why this take? Use fewer effects…") },
            minLines = 2,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = CreamRaised, unfocusedTextColor = CreamRaised,
                focusedBorderColor = Lavender, unfocusedBorderColor = Border
            )
        )
        Button(
            onClick = { revise(request); request = "" },
            enabled = request.isNotBlank(),
            modifier = Modifier.align(Alignment.End),
            colors = ButtonDefaults.buttonColors(containerColor = Lavender, contentColor = Ink)
        ) { Text("Revise proposal", fontWeight = FontWeight.Bold) }
        val constraints = project.analysis.settledConstraints
        if (constraints.isNotEmpty()) {
            Text(
                "Settled: " + constraints.joinToString { "${it.kind.name.lowercase().replace('_', ' ')} = ${it.value}" },
                color = Muted, fontSize = 10.sp
            )
        }
    }
}

@Composable private fun EditorialReadCard(read: EditorialRead) {
    ReviewSurface("WHAT TRIMM FOUND", "Editorial read") {
        ReviewSection("Likely story", read.likelyStory.ifEmpty { listOf("No evidence-backed spoken story was identified.") })
        ReviewSection("Repeated takes", read.repeatedTakes.map { comparison ->
            "“${comparison.sentence}” — ${comparison.alternatives.size + 1} take(s), ${reviewPercent(comparison.confidence)} confidence"
        }.ifEmpty { listOf("No repeated wording was detected.") })
        ReviewSection("Technical problems", read.technicalProblems.ifEmpty { listOf("No technical problems were reported.") })
        ReviewSection("Useful product shots", read.usefulProductShots.ifEmpty { listOf("No grounded product or demonstration shot was identified.") })
        ReviewSection("Uncertainty", read.uncertainty.ifEmpty { listOf("No material uncertainty was reported.") }, Lavender)
    }
}

@Composable private fun ProposalReviewCard(review: ProposalReview, apply: () -> Unit, reject: () -> Unit) {
    ReviewSurface("PROPOSED CUT", review.summary) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StructureColumn("Before", review.beforeStory, Modifier.weight(1f))
            StructureColumn("After", review.afterStory, Modifier.weight(1f))
        }
        Text(
            "${formatDuration(review.beforeDurationMs)} → ${formatDuration(review.afterDurationMs)}",
            color = Coral, fontWeight = FontWeight.Bold, fontSize = 18.sp
        )
        ReviewSection("Selected takes", review.selectedTakes.map {
            "“${it.sentence}” — selected ${it.selectedTakeId ?: "not resolved"}; alternatives: ${it.alternatives.joinToString().ifBlank { "none" }}\n${it.explanation}"
        }.ifEmpty { listOf("No repeated sentence needs a take choice.") })
        ReviewSection("Removals", review.removals.ifEmpty { listOf("No story material is proposed for removal.") })
        ReviewSection("Evidence", review.evidenceIds.map { it }.ifEmpty { listOf("No evidence IDs supplied.") })
        ReviewSection("Assumptions", review.assumptions.ifEmpty { listOf("No unstated assumptions.") })
        ReviewSection("Warnings", review.warnings.ifEmpty { listOf("No material warnings.") }, Lavender)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = reject, modifier = Modifier.weight(1f), border = BorderStroke(1.dp, Border)) {
                Text("Reject", color = Cream)
            }
            Button(onClick = apply, enabled = review.warnings.none { it.startsWith("This proposal targets revision") },
                modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = DarkText)) {
                Text("Apply edit", fontWeight = FontWeight.Bold)
            }
        }
        Text("Apply is one atomic change. Reject does not change the timeline. Undo restores the prior edit.", color = Muted, fontSize = 10.sp)
    }
}

@Composable private fun ReviewSurface(kicker: String, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Workspace, RoundedCornerShape(18.dp))
            .border(1.dp, Border, RoundedCornerShape(18.dp)).padding(16.dp)
    ) {
        Text(kicker, color = Coral, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(title, color = CreamRaised, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable private fun ReviewSection(title: String, lines: List<String>, accent: Color = Green) {
    Text(title, color = Cream, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    lines.forEach { line ->
        Row(Modifier.padding(top = 5.dp)) {
            Box(Modifier.padding(top = 6.dp).size(5.dp).background(accent, RoundedCornerShape(50)))
            Text(line, color = Muted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(start = 8.dp))
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable private fun StructureColumn(title: String, beats: List<String>, modifier: Modifier = Modifier) {
    Column(modifier.background(Raised, RoundedCornerShape(10.dp)).padding(10.dp)) {
        Text(title, color = Cream, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        beats.ifEmpty { listOf("No grounded beats") }.forEachIndexed { index, beat ->
            Text("${index + 1}. $beat", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

private fun reviewPercent(value: Float) = "${(value * 100).toInt()}%"

@Composable private fun AgentPlanBubble(plan: ai.ctrlb.mobile.planner.AgentPlanResponse, run: ai.ctrlb.mobile.planner.PersistedAgentRun?) {
    AssistantChatBubble(accent = Coral) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Execution Plan", color = CreamRaised, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            plan.tasks?.forEach { task ->
                val succeeded = run?.events?.any { it.message == "Completed task: ${task.description}" } == true
                val failed = run?.events?.any { it.message.startsWith("Task failed: ${task.description}") } == true
                
                val icon = if (succeeded) "✅" else if (failed) "❌" else "⚪"
                val color = if (succeeded) Cream else if (failed) Coral else Muted
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Text(icon, fontSize = 12.sp)
                    Column {
                        Text(task.description, color = color, fontSize = 13.sp, lineHeight = 18.sp)
                        if (task.dependsOn.isNotEmpty()) {
                            Text("↳ Depends on: ${task.dependsOn.joinToString(", ")}", color = Muted.copy(alpha = 0.7f), fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun ActionCard(title: String, detail: String, accent: Color) {
    Row(Modifier.fillMaxWidth().background(Raised, RoundedCornerShape(10.dp)).border(1.dp, Border, RoundedCornerShape(10.dp)).padding(12.dp)) {
        Box(Modifier.size(8.dp).background(accent, RoundedCornerShape(50)).align(Alignment.CenterVertically))
        Column(Modifier.padding(start = 10.dp)) { Text(title, color = Cream, fontWeight = FontWeight.Bold, fontSize = 11.sp); Text(detail, color = Muted, fontSize = 11.sp) }
    }
}

@Composable private fun StatusCard(title: String, detail: String, accent: Color) {
    Row(Modifier.fillMaxWidth().background(Raised, RoundedCornerShape(10.dp)).border(1.dp, Border, RoundedCornerShape(10.dp)).padding(12.dp)) {
        Box(Modifier.size(8.dp).background(accent, RoundedCornerShape(50)).align(Alignment.CenterVertically))
        Column(Modifier.padding(start = 10.dp)) { Text(title, color = Cream, fontWeight = FontWeight.Bold, fontSize = 11.sp); Text(detail, color = Muted, fontSize = 11.sp) }
    }
}

@Composable private fun ClipOrder(clips: List<SourceMedia>, moveClip: (Int, Int) -> Unit) {
    if (clips.size <= 1) return
    Column(Modifier.fillMaxWidth().background(Workspace, RoundedCornerShape(14.dp)).border(1.dp, Border, RoundedCornerShape(14.dp)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Story order", color = Cream, fontWeight = FontWeight.Bold); Spacer(Modifier.width(8.dp)); Text("${clips.size} clips", color = Muted, fontSize = 10.sp) }
        Spacer(Modifier.height(9.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            clips.forEachIndexed { index, clip ->
                Column(Modifier.width(142.dp).background(Raised, RoundedCornerShape(9.dp)).border(1.dp, if (index == 0) Coral else Border, RoundedCornerShape(9.dp)).padding(9.dp)) {
                    Text("${index + 1}  Clip ${index + 1}", color = Cream, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(formatDuration(clip.durationMs), color = Muted, fontSize = 10.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { moveClip(index, -1) }, enabled = index > 0, contentPadding = PaddingValues(0.dp)) { Text("← Earlier", fontSize = 10.sp) }
                        TextButton(onClick = { moveClip(index, 1) }, enabled = index < clips.lastIndex, contentPadding = PaddingValues(0.dp)) { Text("Later →", fontSize = 10.sp) }
                    }
                }
            }
        }
    }
}

@Composable private fun SmallAction(label: String, modifier: Modifier = Modifier, action: () -> Unit) {
    OutlinedButton(onClick = action, modifier = modifier, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, Border), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Cream)) { Text(label, fontSize = 11.sp) }
}

@Composable private fun FullscreenPreview(uris: List<String>, close: () -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            VideoPreview(uris, Modifier.fillMaxSize())
            SmallAction("Close", Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(20.dp), close)
        }
    }
}

@Composable private fun EditResultChatBubble(
    checkpoint: AppliedEditCheckpoint,
    canUndo: Boolean,
    undo: () -> Unit
) {
    AssistantChatBubble(accent = Green) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Applied edit", color = Green, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Text(checkpoint.summary, color = CreamRaised, fontWeight = FontWeight.Bold, lineHeight = 20.sp)
            }
            OutlinedButton(
                onClick = undo,
                enabled = canUndo,
                border = BorderStroke(1.dp, if (canUndo) Green else Border)
            ) { Text(if (canUndo) "Undo" else "Checkpoint", color = if (canUndo) Green else Muted) }
        }
        Spacer(Modifier.height(8.dp))
        checkpoint.changes.forEach { change ->
            val color = when {
                change.startsWith("+") -> Green
                change.startsWith("−") -> Coral
                else -> Lavender
            }
            Text(change, color = color, fontSize = 11.sp, lineHeight = 17.sp)
        }
        Text(
            "Revision ${checkpoint.revisionBefore} → ${checkpoint.revisionAfter}",
            color = Muted,
            fontSize = 9.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable private fun AgentRecoveryCard(recover: (AgentRecoveryAction) -> Unit) {
    AssistantChatBubble(accent = Coral) {
        Text("I’m keeping your current edit and conversation unchanged.", color = CreamRaised, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Choose how to continue fixing the response format.", color = Muted, lineHeight = 19.sp)
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AssistChip(
                onClick = { recover(AgentRecoveryAction.RETRY_REPAIR) },
                label = { Text("Retry repair") }
            )
            AssistChip(
                onClick = { recover(AgentRecoveryAction.REGENERATE_PROPOSAL) },
                label = { Text("Regenerate proposal") }
            )
            AssistChip(
                onClick = { recover(AgentRecoveryAction.REVIEW_ISSUE) },
                label = { Text("Review issue") }
            )
        }
    }
}

@Composable private fun AgentClarificationCard(
    questions: List<ClarificationQuestion>,
    isProcessing: Boolean,
    submit: (String) -> Unit
) {
    var selected by rememberSaveable(questions) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var custom by rememberSaveable(questions) { mutableStateOf<Map<String, String>>(emptyMap()) }
    val complete = questions.all { question -> selected[question.id] != null || !custom[question.id].isNullOrBlank() }
    AssistantChatBubble(accent = Lavender) {
        Text("A few choices before I edit", color = CreamRaised, fontWeight = FontWeight.Bold)
        questions.forEach { question ->
            Spacer(Modifier.height(12.dp))
            Text(question.prompt, color = CreamRaised, fontWeight = FontWeight.SemiBold)
            question.options.forEach { option ->
                val active = selected[question.id] == option.id
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).clickable {
                        selected = selected + (question.id to option.id)
                        custom = custom - question.id
                    },
                    color = if (active) Lavender.copy(alpha = .18f) else Raised,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, if (active) Lavender else Border)
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(option.label, color = CreamRaised, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            if (question.recommendedOptionId == option.id) Text("Recommended", color = Green, fontSize = 9.sp)
                        }
                        Text(option.description, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                    }
                }
            }
            OutlinedTextField(
                value = custom[question.id].orEmpty(),
                onValueChange = { value ->
                    custom = custom + (question.id to value.take(300))
                    if (value.isNotBlank()) selected = selected - question.id
                },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                label = { Text("Something else") }, singleLine = true
            )
        }
        Button(
            onClick = {
                val answer = questions.joinToString("\n") { question ->
                    val text = custom[question.id]?.trim().takeUnless { it.isNullOrBlank() }
                        ?: question.options.first { it.id == selected[question.id] }.label
                    "${question.prompt}: $text"
                }
                submit(answer)
            },
            enabled = complete && !isProcessing,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        ) { Text("Send answers") }
    }
}

@Composable private fun FullscreenProjectPreview(project: MobileProject, close: () -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            CanonicalVideoPreview(project, Modifier.fillMaxSize())
            SmallAction("Close", Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(20.dp), close)
        }
    }
}

@OptIn(UnstableApi::class, ExperimentalApi::class)
@Composable private fun CanonicalVideoPreview(project: MobileProject, modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(project.revision, project.operations, project.export, project.orderedSources) {
        CompositionPlayer.Builder(context).build().apply {
            setComposition(ExportCoordinator.composition(context, project, isPreview = true))
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player; setBackgroundColor(android.graphics.Color.BLACK) } },
        update = { it.player = player },
        modifier = modifier
    )
}

@Composable private fun VideoPreview(uris: List<String>, modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(uris) { ExoPlayer.Builder(context).build().apply { setMediaItems(uris.map(MediaItem::fromUri)); prepare() } }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(factory = { PlayerView(it).apply { this.player = player; setBackgroundColor(android.graphics.Color.BLACK) } }, modifier = modifier)
}

private fun formatDuration(ms: Long): String = "%d:%02d".format(ms / 60_000, (ms / 1_000) % 60)
private fun operationTitle(op: EditInstruction) = when (op) { 
    is RemoveRanges -> "Range removal"
    is SetColorGrade -> "Frame-based color"
    is AutoAudioDucking -> "Audio ducking"
    is SetVolume -> "Volume adjustment"
    is SetSpeed -> "Playback speed"
    is AddCallout -> "Callout"
    is AddSpotlight -> "Spotlight"
    is SetAspectRatio -> "Aspect ratio"
    is AddMotionPreset -> "Motion preset"
    is AddCaptions -> "Captions"
    is AddTextOverlay -> "Text overlay"
    is AddBgm -> "Background music"
    is AddBgmEffect -> "Background music effect"
    is AddSfx -> "Sound effect"
    is SetRotation -> "Rotation"
    is ClearRotation -> "Clear rotation"
    is SetCrop -> "Crop"
    is ClearCrop -> "Clear crop"
    else -> "Operation"
}
private fun operationDetail(op: EditInstruction) = when (op) { 
    is RemoveRanges -> "${op.ranges.size} range cuts"
    is SetColorGrade -> "Natural, skin-safe grade"
    is AutoAudioDucking -> "${op.dialogueLufs.toInt()} LUFS dialogue"
    is SetVolume -> "Manual gain: ${op.volume}x"
    is SetSpeed -> "Playback speed: ${op.speed}x"
    is AddCallout -> "Text: ${op.text}"
    is AddSpotlight -> "Focused highlight"
    is SetAspectRatio -> op.ratio.name
    is AddMotionPreset -> op.preset
    is AddCaptions -> "${op.cues.size} timed caption cards"
    is AddTextOverlay -> "Generated Graphics"
    is AddBgm -> "AI-generated BGM"
    is AddBgmEffect -> "AI-generated BGM effect"
    is AddSfx -> "AI-generated SFX"
    is SetRotation -> "${op.rotation}°"
    is ClearRotation -> op.itemId
    is SetCrop -> op.itemId
    is ClearCrop -> op.itemId
    else -> "Detail unavailable"
}

@Composable private fun TrimmTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(primary = Coral, secondary = Lavender, background = Ink, surface = Workspace, onSurface = CreamRaised, outline = Border),
    typography = Typography(
        headlineMedium = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
        bodyLarge = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp),
    ),
    content = content,
)
