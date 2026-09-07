package ai.trimm.mobile.ui

import ai.trimm.mobile.R

import ai.trimm.mobile.*
import ai.trimm.mobile.domain.*
import ai.trimm.mobile.media.ExportCoordinator
import ai.trimm.mobile.media.DeviceCapability
import ai.trimm.mobile.work.ExportWorker
import ai.trimm.mobile.planner.AgentRecoveryAction
import ai.trimm.mobile.planner.AgentRecoveryStatus
import ai.trimm.mobile.planner.AgentUsageSummary
import ai.trimm.mobile.planner.AgentDecisionMapper
import ai.trimm.mobile.planner.ClarificationQuestion
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
import ai.trimm.mobile.network.supabase
import ai.trimm.mobile.auth.signInWithGoogle
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


@Composable fun ChatView(
    state: ChatState,
    undo: () -> Unit,
    export: () -> Unit,
    applyProposal: () -> Unit,
    rejectProposal: () -> Unit,
    undoCheckpoint: (String) -> Unit,
    undoTurn: (String) -> Unit,
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
    
    LaunchedEffect(state.pendingChatMessage) {
        if (state.pendingChatMessage != null) {
            request = state.pendingChatMessage
        }
    }

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
                    ?.takeIf { initial -> project.analysis.chatHistory.none { it.request == initial } }
                    ?.let { initial -> add(ChatElement.InitialRequest(0L, initial)) }

                project.analysis.completedAtEpochMs?.let { time ->
                    if (read.technicalProblems.isNotEmpty()) {
                        add(ChatElement.Findings(time, read))
                    }
                }

                project.analysis.activeChatPath.forEach { turn ->
                    add(ChatElement.Turn(turn.createdAtEpochMs, turn))
                }
                
                project.analysis.activeCheckpointPath.lastOrNull()?.let { checkpoint ->
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
                        val isLatestTurn = turn.id == project.analysis.activeChatPath.lastOrNull()?.id
                        val siblings = project.analysis.chatHistory.filter { it.parentId == turn.parentId }
                        val index = siblings.indexOf(turn)
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Box(modifier = Modifier.weight(1f)) {
                                    UserChatBubble(turn.request, onUndo = { undoTurn(turn.id) })
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
                            AssistantChatBubble(accent = if (turn.isError) Color(0xFFE4E4E7) else Lavender, isBubble = false) {
                                when {
                                    turn.isPending -> {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Lavender)
                                            AnimatedResponseText(turn.response, color = CreamRaised, lineHeight = 22.sp, fontSize = 15.sp)
                                        }
                                    }
                                    turn.isError -> {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            AnimatedResponseText(formatUserFacingError(turn.response), color = CreamRaised, lineHeight = 22.sp, fontSize = 15.sp)
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
                                        AnimatedResponseText(turn.response, color = CreamRaised, lineHeight = 22.sp, fontSize = 15.sp)
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
                // Removed AgentUsageChatBubble rendering per user request, but retained calculation logic for future admin dashboard
            }
            if (review != null) {
                item { ProposalChatBubble(review, applyProposal, rejectProposal) }
            }
            if (!state.isProcessing && project.analysis.agentPlan != null) {
                item(key = "agent-plan-${project.analysis.agentRun?.runId}") {
                    AgentPlanBubble(project.analysis.agentPlan, project.analysis.agentRun)
                }
            }
            if (state.isProcessing && project.analysis.chatHistory.none { it.isPending }) {
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
