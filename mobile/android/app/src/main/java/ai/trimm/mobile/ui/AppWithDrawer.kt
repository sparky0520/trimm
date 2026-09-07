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


@Composable fun AppWithDrawer(vm: ChatViewModel = viewModel()) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            vm.refreshChats()
        }
    }
    
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Color(0xFF171717),
                drawerContentColor = Cream,
                modifier = Modifier.width(300.dp)
            ) {
                Column(Modifier.fillMaxSize()) {
                    val state by vm.state.collectAsStateWithLifecycle()
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
                        IconButton(
                            onClick = {
                                vm.newChat()
                                scope.launch { drawerState.close() }
                            },
                            enabled = !state.isLoadingPastChats
                        ) { Text("+", fontSize = 24.sp, color = if (state.isLoadingPastChats) Color.Gray else Cream) }
                    }
                    LazyColumn(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        item { 
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Past Chats", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(8.dp))
                                if (state.isLoadingPastChats) {
                                    Spacer(Modifier.width(8.dp))
                                    CircularProgressIndicator(modifier = Modifier.size(12.dp), color = Color.Gray, strokeWidth = 2.dp)
                                }
                            }
                        }
                        items(state.pastChats.size) { index ->
                            val pastChat = state.pastChats[index]
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                TextButton(
                                    onClick = { 
                                        vm.loadProject(pastChat.id)
                                        scope.launch { drawerState.close() }
                                    },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                                ) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                                        ChatTitleText(pastChat)
                                    }
                                }
                                IconButton(onClick = { vm.deleteChat(pastChat.id) }) {
                                    Text("🗑️", fontSize = 16.sp)
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
                        val activity = androidx.activity.compose.LocalActivity.current
                        if (activity != null) {
                            AccountControl(activity)
                        }
                    }
                }
            }
        }
    ) {
        TrimmApp(vm, onOpenDrawer = { scope.launch { drawerState.open() } })
    }
}
