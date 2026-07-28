package ai.ctrlb.mobile

import ai.ctrlb.mobile.domain.*
import android.net.Uri
import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.clerk.api.Clerk
import com.clerk.ui.auth.AuthView
import com.clerk.ui.userbutton.UserButton
import kotlinx.coroutines.launch

private val Ink = Color(0xFF17131F)
private val Workspace = Color(0xFF211A2B)
private val Raised = Color(0xFF292135)
private val Border = Color(0xFF4A3B5A)
private val Cream = Color(0xFFF7F2EA)
private val CreamRaised = Color(0xFFFFFAF3)
private val DarkText = Color(0xFF241D2C)
private val Muted = Color(0xFF9B8FA5)
private val Coral = Color(0xFFFF6F61)
private val Lavender = Color(0xFFBDA7FF)
private val Green = Color(0xFF6FD49A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.CLERK_PUBLISHABLE_KEY.isNotBlank()) {
            lifecycleScope.launch { Clerk.auth.handle(intent.data) }
        }
        enableEdgeToEdge()
        setContent { CtrlBTheme { Surface(Modifier.fillMaxSize(), color = Ink) { CtrlBApp() } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (BuildConfig.CLERK_PUBLISHABLE_KEY.isNotBlank()) {
            lifecycleScope.launch { Clerk.auth.handle(intent.data) }
        }
    }
}

@Composable private fun CtrlBApp(vm: EditorViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    when {
        state.source == null -> ImportScreen(vm::import)
        state.project != null -> EditorScreen(state, vm::undo, vm::export, vm::applyProposal, vm::rejectProposal, vm::reviseStory)
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
        Modifier.fillMaxWidth().background(Workspace).statusBarsPadding().height(58.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).background(Lavender, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
            Text("B", color = Ink, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(9.dp))
        Text("ctrlB", color = CreamRaised, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.width(8.dp))
        Text("STUDIO", color = Muted, fontWeight = FontWeight.Bold, fontSize = 9.sp, letterSpacing = 1.2.sp,
            modifier = Modifier.border(1.dp, Border, RoundedCornerShape(20.dp)).padding(horizontal = 7.dp, vertical = 4.dp))
        Spacer(Modifier.weight(1f))
        AccountControl()
        if (secondaryLabel != null && onSecondary != null) {
            TextButton(onClick = onSecondary) { Text(secondaryLabel, color = Cream) }
        }
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction, enabled = actionEnabled, shape = RoundedCornerShape(9.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = DarkText)) {
                Text(actionLabel, fontWeight = FontWeight.Bold)
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
                shape = RoundedCornerShape(18.dp),
                color = CreamRaised,
            ) {
                Box(Modifier.padding(12.dp)) {
                    AuthView()
                    TextButton(
                        onClick = { showSignIn = false },
                        modifier = Modifier.align(Alignment.TopEnd),
                    ) { Text("Close", color = DarkText) }
                }
            }
        }
    }
}

@Composable private fun ImportScreen(onImport: (List<Uri>) -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) onImport(it) }
    Column(Modifier.fillMaxSize().background(Ink)) {
        BrandBar()
        Box(Modifier.fillMaxSize().padding(12.dp).background(Workspace, RoundedCornerShape(20.dp)).border(1.dp, Border, RoundedCornerShape(20.dp))) {
            Column(Modifier.align(Alignment.Center).padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(82.dp).background(Lavender, RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
                    Text("▶", color = Ink, fontSize = 27.sp)
                    Box(Modifier.size(10.dp).align(Alignment.TopEnd).offset(12.dp, (-10).dp).background(Coral))
                }
                Spacer(Modifier.height(30.dp))
                Text("YOUR NEXT STORY STARTS HERE", color = Coral, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Spacer(Modifier.height(10.dp))
                Text("Turn a rough recording\ninto something worth sharing.", color = CreamRaised, fontSize = 27.sp,
                    lineHeight = 32.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text("Bring in your talking-head and product clips, then shape them naturally with AI.", color = Muted, lineHeight = 21.sp)
                Spacer(Modifier.height(28.dp))
                Button(onClick = { launcher.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = DarkText)) {
                    Text("Import your clips  →", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 5.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text("MP4  •  MOV  •  WEBM    |    ON-DEVICE WORKSPACE", color = Muted, fontSize = 9.sp, letterSpacing = .8.sp)
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
                    VideoPreview(state.sources.map { it.uri }, Modifier.fillMaxWidth().aspectRatio(first.width.toFloat() / first.height))
                    SmallAction("Expand", Modifier.align(Alignment.TopEnd).padding(8.dp)) { fullscreen = true }
                }
            }
            item { ClipOrder(state.sources, moveClip) }
            item {
                Column(
                    Modifier.fillMaxWidth(.88f).background(Raised, RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp)).padding(14.dp)
                ) {
                    Text("ctrlB", color = Lavender, fontWeight = FontWeight.Bold, fontSize = 11.sp)
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

@Composable private fun EditorScreen(
    state: EditorState,
    undo: () -> Unit,
    export: () -> Unit,
    applyProposal: () -> Unit,
    rejectProposal: () -> Unit,
    reviseStory: (String) -> Unit,
) {
    val project = requireNotNull(state.project)
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var request by rememberSaveable { mutableStateOf("") }
    val read = EditorialReviewBuilder.editorialRead(project)
    val review = EditorialReviewBuilder.proposal(project)
    Column(Modifier.fillMaxSize().background(Ink)) {
        BrandBar(actionLabel = "Export ${project.export.height}p", onAction = export, secondaryLabel = "Undo", onSecondary = undo)
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(Modifier.fillMaxWidth().background(Workspace, RoundedCornerShape(18.dp)).border(1.dp, Border, RoundedCornerShape(18.dp))) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("YOUR VIDEO", color = Coral, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Text(if (state.previewUri == null) "Original clips" else "Current edit", color = CreamRaised, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                        SmallAction("Expand") { fullscreen = true }
                    }
                    val ratio = when (project.preferences.aspectRatio) { AspectRatio.LANDSCAPE -> 16f / 9f; AspectRatio.PORTRAIT -> 9f / 16f; AspectRatio.SQUARE -> 1f }
                    Box(Modifier.fillMaxWidth().padding(10.dp).background(Ink, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                        VideoPreview(state.previewUri?.let(::listOf) ?: project.orderedSources.map { it.uri }, Modifier.fillMaxWidth().aspectRatio(ratio))
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("00:00", color = Muted, fontSize = 10.sp)
                        Text("${project.orderedSources.size} CLIPS  •  ${project.preferences.aspectRatio}", color = Muted, fontSize = 9.sp, letterSpacing = .6.sp)
                    }
                }
            }
            items(project.analysis.revisionConversation, key = { it.id }) { turn ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    UserChatBubble(turn.request)
                    AssistantChatBubble { Text(turn.response, color = CreamRaised, lineHeight = 20.sp) }
                }
            }
            if (project.analysis.completedAtEpochMs != null) {
                item { FindingsChatBubble(read) }
            }
            if (review != null) {
                item { ProposalChatBubble(review, applyProposal, rejectProposal) }
            }
            state.jobMessage?.let { message -> item { AssistantChatBubble(accent = Lavender) { Text(message, color = CreamRaised) } } }
            state.capabilityMessage?.let { message -> item { AssistantChatBubble(accent = Coral) { Text("Export note: $message", color = CreamRaised) } } }
            if (project.analysis.revisionConversation.isEmpty() && project.analysis.completedAtEpochMs == null) {
                item { AssistantChatBubble(accent = Lavender) { Text("I’m reading your clips, transcribing the speech, and looking for the strongest cut.", color = CreamRaised) } }
            }
        }
        Surface(color = Workspace, tonalElevation = 4.dp) {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = request,
                    onValueChange = { request = it.take(2_000) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask for another change…") },
                    maxLines = 5,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = CreamRaised, unfocusedTextColor = CreamRaised,
                        focusedBorderColor = Lavender, unfocusedBorderColor = Border
                    )
                )
                Button(
                    onClick = { reviseStory(request); request = "" },
                    enabled = request.isNotBlank(),
                    modifier = Modifier.height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = DarkText)
                ) { Text("Send", fontWeight = FontWeight.Bold) }
            }
        }
    }
    if (fullscreen) FullscreenPreview(state.previewUri?.let(::listOf) ?: project.orderedSources.map { it.uri }) { fullscreen = false }
}

@Composable private fun UserChatBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            text,
            color = DarkText,
            modifier = Modifier.fillMaxWidth(.86f).background(Coral, RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp)).padding(13.dp),
            lineHeight = 20.sp
        )
    }
}

@Composable private fun AssistantChatBubble(accent: Color = Lavender, content: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(
            Modifier.fillMaxWidth(.92f).background(Raised, RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                .border(1.dp, Border, RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp)).padding(14.dp)
        ) {
            Text("ctrlB", color = accent, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            Spacer(Modifier.height(5.dp))
            content()
        }
    }
}

@Composable private fun FindingsChatBubble(read: EditorialRead) {
    AssistantChatBubble {
        Text("Here’s what I found in your footage:", color = CreamRaised, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        ChatFinding("Story", read.likelyStory.ifEmpty { listOf("I couldn’t confidently identify a complete spoken story.") })
        if (read.repeatedTakes.isNotEmpty()) ChatFinding("Repeated takes", read.repeatedTakes.map {
            "${it.sentence} — ${it.alternatives.size + 1} versions, ${reviewPercent(it.confidence)} confidence"
        })
        if (read.technicalProblems.isNotEmpty()) ChatFinding("Things to fix", read.technicalProblems)
        if (read.usefulProductShots.isNotEmpty()) ChatFinding("Useful shots", read.usefulProductShots)
        if (read.uncertainty.isNotEmpty()) ChatFinding("I’m less certain about", read.uncertainty)
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
    ReviewSurface("WHAT CTRLB FOUND", "Editorial read") {
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

@Composable private fun VideoPreview(uris: List<String>, modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(uris) { ExoPlayer.Builder(context).build().apply { setMediaItems(uris.map(MediaItem::fromUri)); prepare() } }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(factory = { PlayerView(it).apply { this.player = player; setBackgroundColor(android.graphics.Color.BLACK) } }, modifier = modifier)
}

private fun formatDuration(ms: Long): String = "%d:%02d".format(ms / 60_000, (ms / 1_000) % 60)
private fun operationTitle(op: EditOperation) = when (op) { is RemoveFumbles -> "Fumble removal"; is SetColorGrade -> "Frame-based color"; is SetAudioCleanup -> "Background noise cleanup"; is SetAudioLevels -> "Dialogue leveling"; is AddTransition -> "Transition"; is AddMotionPreset -> "Motion preset"; is AddSoundEffect -> "Sound effect"; is SetBackgroundMusic -> "Background music"; is AddCaptions -> "Captions"; is ApplySemanticVisual -> op.intent.name.lowercase().replaceFirstChar(Char::uppercase) }
private fun operationDetail(op: EditOperation) = when (op) { is RemoveFumbles -> "${op.ranges.size} high-confidence cuts"; is SetColorGrade -> "Natural, skin-safe grade"; is SetAudioCleanup -> "Local speech-safe processing"; is SetAudioLevels -> "${op.dialogueLufs.toInt()} LUFS dialogue"; is AddTransition -> op.preset; is AddMotionPreset -> op.preset; is AddSoundEffect -> op.catalogId; is SetBackgroundMusic -> op.structure?.let { "${op.catalogId} • ${it.decisions.size} reported mix decisions" } ?: op.catalogId; is AddCaptions -> "${op.cues.size} timed caption cards"; is ApplySemanticVisual -> "${op.treatment.name.lowercase()} • ${op.subject.kind.name.lowercase()}" }

@Composable private fun CtrlBTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(primary = Coral, secondary = Lavender, background = Ink, surface = Workspace, onSurface = CreamRaised, outline = Border),
    typography = Typography(
        headlineMedium = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
        bodyLarge = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp),
    ),
    content = content,
)
