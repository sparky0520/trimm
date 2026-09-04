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


@Composable fun CreatorPreferencesCard(
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
