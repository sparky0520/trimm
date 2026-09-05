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


@Composable fun NewEditChatScreen(state: ChatState, createDraft: (String) -> Unit, moveClip: (Int, Int) -> Unit, onRelink: (Uri) -> Unit, isOnline: Boolean, onOpenDrawer: () -> Unit) {
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
                        VideoPreview(state.sources.map { it.uri }, Modifier.fillMaxWidth().aspectRatio(first.displaySize().aspectRatio))
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
