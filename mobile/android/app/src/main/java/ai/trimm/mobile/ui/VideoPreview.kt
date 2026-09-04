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


@Composable fun VideoPreview(uris: List<String>, modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(uris) { ExoPlayer.Builder(context).build().apply { setMediaItems(uris.map(MediaItem::fromUri)); prepare() } }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(factory = { PlayerView(it).apply { this.player = player; setBackgroundColor(android.graphics.Color.BLACK) } }, modifier = modifier)
}

fun formatDuration(ms: Long): String = "%d:%02d".format(ms / 60_000, (ms / 1_000) % 60)
private fun operationTitle(op: EditInstruction) = when (op) { 
    is RemoveRanges -> "Range removal"
    is SetColorGrade -> "Frame-based color"
    is NormaliseAudio -> "Audio ducking"
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
    is NormaliseAudio -> "${op.dialogueLufs.toInt()} LUFS dialogue"
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
