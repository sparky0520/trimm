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
import androidx.compose.foundation.combinedClickable
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


@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable fun UserChatBubble(text: String, onUndo: (() -> Unit)? = null) {
    @Suppress("DEPRECATION")
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current // Fallback since LocalClipboard might require newer compose versions, but let's keep it safe or suppress
    var menuExpanded by remember { mutableStateOf(false) }
    var showUndoConfirmation by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box {
            Surface(
                color = UserBlue,
                shape = RoundedCornerShape(12.dp, 12.dp, 4.dp, 12.dp),
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { menuExpanded = true }
                    )
            ) {
                Text(
                    text,
                    color = CreamRaised,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.background(Raised)
            ) {
                DropdownMenuItem(
                    text = { Text("Copy", color = Cream) },
                    onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                        menuExpanded = false
                    }
                )
                if (onUndo != null) {
                    DropdownMenuItem(
                        text = { Text("Undo to this step", color = Coral) },
                        onClick = {
                            menuExpanded = false
                            showUndoConfirmation = true
                        }
                    )
                }
            }
        }
    }

    if (showUndoConfirmation && onUndo != null) {
        AlertDialog(
            onDismissRequest = { showUndoConfirmation = false },
            title = { Text("Undo Edits", color = Cream, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "“$text”\n\nAre you sure you want to undo? All video edits and chat messages from this point forward will be removed.",
                    color = CreamRaised,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showUndoConfirmation = false
                    onUndo()
                }) {
                    Text("Undo", color = Coral, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUndoConfirmation = false }) {
                    Text("Cancel", color = Muted)
                }
            },
            containerColor = Raised
        )
    }
}
