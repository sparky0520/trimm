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
import android.net.Uri
import android.os.Bundle
import android.content.Intent
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.media3.common.MediaItem
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.ui.PlayerView
import com.clerk.api.Clerk
import com.clerk.ui.auth.AuthView
import com.clerk.ui.userbutton.UserButton
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
        checkForAppUpdates()
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
        if (BuildConfig.CLERK_PUBLISHABLE_KEY.isNotBlank()) {
            lifecycleScope.launch { Clerk.auth.handle(intent.data) }
        }
        enableEdgeToEdge()
        setContent { TrimmTheme { Surface(Modifier.fillMaxSize(), color = Ink) { TrimmApp() } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (BuildConfig.CLERK_PUBLISHABLE_KEY.isNotBlank()) {
            lifecycleScope.launch { Clerk.auth.handle(intent.data) }
        }
    }
}

@Composable private fun TrimmApp(vm: EditorViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    when {
        state.source == null -> ImportScreen(vm::import)
        state.project != null -> EditorScreen(
            state, vm::undo, vm::export, vm::applyProposal, vm::rejectProposal,
            vm::undoCheckpoint,
            vm::reviseStory, vm::retryTurn, vm::recoverAgent, vm::cancelAnalysis
        )
        else -> NewEditChatScreen(state, vm::generateDraft, vm::moveClip)
    }
}

@Composable private fun BrandBar(
    actionLabel: String? = null,
    actionEnabled: Boolean = true,
    onAction: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Workspace)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Trimm",
            color = Cream,
            fontFamily = FontFamily.SansSerif,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-1).sp,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        AccountControl()
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

@Composable private fun AccountControl() {
    if (BuildConfig.CLERK_PUBLISHABLE_KEY.isBlank()) return
    val initialized by Clerk.isInitialized.collectAsStateWithLifecycle()
    val user by Clerk.userFlow.collectAsStateWithLifecycle()
    var showSignIn by rememberSaveable { mutableStateOf(false) }

    when {
        !initialized -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Lavender)
        user != null -> UserButton()
        else -> TextButton(onClick = { showSignIn = true }) { Text("Sign in", color = Cream) }
    }

    if (showSignIn && user == null) {
        Dialog(onDismissRequest = { showSignIn = false }) {
            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = Workspace,
            ) {
                Box(Modifier.padding(12.dp)) {
                    AuthView()
                    TextButton(
                        onClick = { showSignIn = false },
                        modifier = Modifier.align(Alignment.TopEnd),
                    ) { Text("Close", color = Cream) }
                }
            }
        }
    }
}

@Composable private fun ImportScreen(onImport: (List<Uri>) -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) onImport(it) }
    Column(Modifier.fillMaxSize().background(Ink)) {
        BrandBar()
        Box(Modifier.fillMaxSize().padding(12.dp).background(Workspace, RoundedCornerShape(12.dp)).border(1.dp, Border, RoundedCornerShape(12.dp))) {
            Column(Modifier.align(Alignment.Center).padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(72.dp).background(Raised, RoundedCornerShape(12.dp)).border(1.dp, Border, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Text("T", color = Cream, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(30.dp))
                Text("START A NEW EDIT", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Spacer(Modifier.height(10.dp))
                Text("Turn a rough recording\ninto something worth sharing.", color = CreamRaised, fontSize = 27.sp,
                    lineHeight = 32.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text("Bring in your talking-head and product clips, then shape them naturally with AI.", color = Muted, lineHeight = 21.sp)
                Spacer(Modifier.height(28.dp))
                Button(onClick = { launcher.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = DarkText)) {
                    Text("Import clips", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 5.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text("MP4 · MOV · WEBM  |  ON-DEVICE WORKSPACE", color = Muted, fontSize = 9.sp, letterSpacing = .8.sp)
            }
        }
    }
}

@Composable private fun NewEditChatScreen(state: EditorState, createDraft: (String) -> Unit, moveClip: (Int, Int) -> Unit) {
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf("") }
    val suggestions = listOf(
        "Make a clean, natural first cut",
        "Turn this into a tight vertical short with captions",
        "Keep the best takes and remove mistakes",
    )
    Column(Modifier.fillMaxSize().background(Ink)) {
        BrandBar()
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Box(Modifier.fillMaxWidth().background(Workspace, RoundedCornerShape(18.dp)).border(1.dp, Border, RoundedCornerShape(18.dp)).padding(10.dp)) {
                    val first = state.sources.first()
                    VideoPreview(state.sources.map { it.uri }, Modifier.fillMaxWidth().aspectRatio(first.displaySize().aspectRatio))
                    SmallAction("Expand", Modifier.align(Alignment.TopEnd).padding(8.dp)) { fullscreen = true }
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
                        SuggestionChip(onClick = { message = suggestion }, label = { Text(suggestion, fontSize = 11.sp) })
                    }
                }
            }
        }
        Surface(color = Workspace, tonalElevation = 4.dp) {
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
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = CreamRaised, unfocusedTextColor = CreamRaised,
                        focusedBorderColor = Lavender, unfocusedBorderColor = Border
                    )
                )
                Button(
                    onClick = { createDraft(message) },
                    enabled = message.isNotBlank(),
                    modifier = Modifier.height(56.dp),
                    shape = RoundedCornerShape(16.dp),
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
    recoverAgent: (AgentRecoveryAction) -> Unit,
    cancelAnalysis: () -> Unit,
) {
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
            onSecondary = { showVideo = !showVideo }
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
                    CanonicalVideoPreview(project, Modifier.fillMaxWidth().aspectRatio(ratio))
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

                project.analysis.revisionConversation.forEach { turn ->
                    add(ChatElement.Turn(turn.createdAtEpochMs, turn))
                }
                
                project.analysis.appliedEditCheckpoints.forEach { checkpoint ->
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
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            UserChatBubble(turn.request)
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

        }
        Surface(color = Workspace, tonalElevation = 4.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    followUpSuggestions.forEach { suggestion ->
                        SuggestionChip(
                            onClick = { request = suggestion },
                            label = { Text(suggestion, fontSize = 11.sp, color = Cream) }
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
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = CreamRaised, unfocusedTextColor = CreamRaised,
                            focusedBorderColor = Lavender, unfocusedBorderColor = Border
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
                        shape = RoundedCornerShape(16.dp),
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
            color = Raised,
            shape = RoundedCornerShape(12.dp, 12.dp, 4.dp, 12.dp),
            border = BorderStroke(1.dp, Border),
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

@Composable private fun ConversationalRevisionCard(project: MobileProject, revise: (String) -> Unit) {
    var request by rememberSaveable { mutableStateOf("") }
    ReviewSurface("REVISE", "Keep editing conversationally") {
        project.analysis.revisionConversation.takeLast(3).forEach { turn ->
            Text(turn.request, color = CreamRaised, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
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
            setComposition(ExportCoordinator.composition(context, project))
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
private fun operationTitle(op: EditOperation) = when (op) { is RemoveRanges -> "Range removal"; is SetColorGrade -> "Frame-based color"; is SetAudioLevels -> "Dialogue leveling"; is SetAspectRatio -> "Aspect ratio"; is AddMotionPreset -> "Motion preset"; is AddCaptions -> "Captions"; is AddTextOverlay -> "Text overlay" }
private fun operationDetail(op: EditOperation) = when (op) { is RemoveRanges -> "${op.ranges.size} range cuts"; is SetColorGrade -> "Natural, skin-safe grade"; is SetAudioLevels -> "${op.dialogueLufs.toInt()} LUFS dialogue"; is SetAspectRatio -> op.ratio.name; is AddMotionPreset -> op.preset; is AddCaptions -> "${op.cues.size} timed caption cards"; is AddTextOverlay -> "Generated Graphics" }

@Composable private fun TrimmTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(primary = Coral, secondary = Lavender, background = Ink, surface = Workspace, onSurface = CreamRaised, outline = Border),
    typography = Typography(
        headlineMedium = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
        bodyLarge = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp),
    ),
    content = content,
)
