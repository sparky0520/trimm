package ai.trimm.mobile.ui

import ai.trimm.mobile.R
import androidx.compose.material.icons.filled.Bolt
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


@Composable fun ImportScreen(activity: Activity, onImport: (List<Uri>) -> Unit, isLoadingPastChats: Boolean = false, onOpenDrawer: (() -> Unit)? = null) {
    var isImporting by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) {
            isImporting = true
            onImport(uris)
        }
    }
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

        val balance by ai.trimm.mobile.billing.BillingManager.balance.collectAsStateWithLifecycle()
        if (sessionStatus is SessionStatus.Authenticated) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 16.dp, top = 8.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Raised)
                    .border(1.dp, Border.copy(alpha = 0.6f), androidx.compose.foundation.shape.CircleShape)
                    .clickable { ai.trimm.mobile.billing.BillingManager.setStorePresented(true) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.Bolt,
                    contentDescription = "Credits",
                    tint = Color(0xFFFACC15),
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = "$balance",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Cream
                )
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
                Button(
                    onClick = { launcher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                    enabled = !isLoadingPastChats && !isImporting,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Coral,
                        contentColor = DarkText,
                        disabledContainerColor = Coral.copy(alpha = 0.5f),
                        disabledContentColor = DarkText.copy(alpha = 0.5f)
                    )
                ) {
                    if (isImporting) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = DarkText, strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Preparing clips...", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    } else if (isLoadingPastChats) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = DarkText, strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Loading...", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    } else {
                        Text("Select clips to edit", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    }
                }
            }
        }

        if (isImporting) {
            Dialog(
                onDismissRequest = {},
                properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Ink)
                        .border(1.dp, Border.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 28.dp, vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            color = Coral,
                            modifier = Modifier.size(36.dp),
                            strokeWidth = 3.dp
                        )
                        Spacer(Modifier.height(18.dp))
                        Text(
                            text = "Preparing Clips…",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = Cream
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Analyzing video metadata and setting up editor",
                            fontSize = 13.sp,
                            color = Muted,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }
        Text("YOUR VIDEO STAYS ON DEVICE", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp))
    }
}
