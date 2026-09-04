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


@Composable fun AgentUsageChatBubble(usage: AgentUsageSummary) {
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

fun formatUserFacingError(raw: String): String {
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
