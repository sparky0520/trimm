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


@Composable fun AgentClarificationCard(
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
