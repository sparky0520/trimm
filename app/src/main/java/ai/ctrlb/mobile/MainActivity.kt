package ai.ctrlb.mobile

import ai.ctrlb.mobile.domain.*
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent { CtrlBTheme { Surface(Modifier.fillMaxSize()) { CtrlBApp() } } }
    }
}

@Composable private fun CtrlBApp(vm: EditorViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    when {
        state.source == null -> ImportScreen(vm::import)
        state.project != null -> EditorScreen(state, vm::toggle, vm::undo, vm::export, vm::moveClip)
        else -> WizardScreen(state, vm::preferences, vm::next, vm::back, vm::generateDraft, vm::moveClip)
    }
}

@Composable private fun ImportScreen(onImport: (List<Uri>) -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) onImport(it) }
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("Turn a rough take into a finished video", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(12.dp)); Text("Your footage stays on this device. Import any supported video to begin.")
        Spacer(Modifier.height(28.dp)); Button(onClick = { launcher.launch(arrayOf("video/*")) }) { Text("Choose clips") }
        Spacer(Modifier.height(8.dp)); Text("Select one or several clips. You can arrange them before creating the edit.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun WizardScreen(state: EditorState, update: (WizardPreferences) -> Unit, next: () -> Unit, back: () -> Unit, finish: () -> Unit, moveClip: (Int, Int) -> Unit) {
    val p = state.preferences
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text("Quick edit brief", style = MaterialTheme.typography.headlineMedium); Text("Step ${state.wizardStep} of 3")
            Spacer(Modifier.height(24.dp))
            if (state.wizardStep == 1) {
                Text("Preview clip order", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Box {
                    val first = state.sources.first()
                    VideoPreview(state.sources.map { it.uri }, Modifier.fillMaxWidth().aspectRatio(first.width.toFloat() / first.height))
                    FilledTonalButton(onClick = { fullscreen = true }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) { Text("Fullscreen") }
                }
                Spacer(Modifier.height(16.dp))
                ClipOrder(state.sources, moveClip)
            }
            when (state.wizardStep) {
                1 -> { Choice("Where will you publish?", Platform.entries, p.platform) { update(p.copy(platform = it, aspectRatio = if (it == Platform.YOUTUBE) AspectRatio.LANDSCAPE else AspectRatio.PORTRAIT)) }; Choice("Framing", AspectRatio.entries, p.aspectRatio) { update(p.copy(aspectRatio = it)) } }
                2 -> { Choice("Pacing", Pace.entries, p.pace) { update(p.copy(pace = it)) }; Choice("Visual style", VisualStyle.entries, p.style) { update(p.copy(style = it)) } }
                else -> { Choice("Music", MusicChoice.entries, p.music) { update(p.copy(music = it)) }; Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(false, null, enabled = false); Text("Captions temporarily unavailable") }; Text("Must-keep moments can be refined after the first edit.") }
            }
            Spacer(Modifier.height(16.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = back, enabled = state.wizardStep > 1) { Text("Back") }
            Button(onClick = if (state.wizardStep == 3) finish else next) { Text(if (state.wizardStep == 3) "Create edit" else "Continue") }
        }
    }
    if (fullscreen) FullscreenPreview(state.sources.map { it.uri }) { fullscreen = false }
}

@Composable private fun <T> Choice(label: String, values: List<T>, selected: T, select: (T) -> Unit) {
    Text(label, style = MaterialTheme.typography.titleMedium); values.forEach { value -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(value == selected, { select(value) }); Text(value.toString().lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }) } }; Spacer(Modifier.height(14.dp))
}

@Composable private fun EditorScreen(state: EditorState, toggle: (String) -> Unit, undo: () -> Unit, export: () -> Unit, moveClip: (Int, Int) -> Unit) {
    val project = requireNotNull(state.project)
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
            item {
                Box {
                    val previewAspectRatio = when (project.preferences.aspectRatio) {
                        AspectRatio.LANDSCAPE -> 16f / 9f
                        AspectRatio.PORTRAIT -> 9f / 16f
                        AspectRatio.SQUARE -> 1f
                    }
                    VideoPreview(state.previewUri?.let(::listOf) ?: project.orderedSources.map { it.uri }, Modifier.fillMaxWidth().aspectRatio(previewAspectRatio))
                    FilledTonalButton(onClick = { fullscreen = true }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) { Text("Fullscreen") }
                }
            }
            state.capabilityMessage?.let { message -> item { Text("4K unavailable: $message. Export will use 1080p.", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) } }
            state.jobMessage?.let { message -> item { Text(message, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall) } }
            item { Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) { Text("Your edit", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f)); TextButton(onClick = undo, enabled = project.undo.isNotEmpty()) { Text("Undo") } } }
            item { Box(Modifier.padding(horizontal = 12.dp)) { ClipOrder(project.orderedSources, moveClip) } }
            items(project.operations, key = { it.id }) { op ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(operationTitle(op), style = MaterialTheme.typography.titleMedium); Text(operationDetail(op), style = MaterialTheme.typography.bodySmall) }; Switch(op.enabled, { toggle(op.id) }) } }
            }
        }
        Button(onClick = export, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("Export ${project.export.height}p") }
    }
    if (fullscreen) FullscreenPreview(state.previewUri?.let(::listOf) ?: project.orderedSources.map { it.uri }) { fullscreen = false }
}

@Composable private fun ClipOrder(clips: List<SourceMedia>, moveClip: (Int, Int) -> Unit) {
    if (clips.size <= 1) return
    Text("Clip order", style = MaterialTheme.typography.titleMedium)
    clips.forEachIndexed { index, clip ->
        Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}", style = MaterialTheme.typography.titleMedium)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text("Clip ${index + 1}")
                    Text(formatDuration(clip.durationMs), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { moveClip(index, -1) }, enabled = index > 0) { Text("Up") }
                TextButton(onClick = { moveClip(index, 1) }, enabled = index < clips.lastIndex) { Text("Down") }
            }
        }
    }
    Spacer(Modifier.height(14.dp))
}

@Composable private fun FullscreenPreview(uris: List<String>, close: () -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            VideoPreview(uris, Modifier.fillMaxSize())
            Button(onClick = close, modifier = Modifier.align(Alignment.TopEnd).padding(20.dp)) { Text("Close") }
        }
    }
}

@Composable private fun VideoPreview(uris: List<String>, modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(uris) { ExoPlayer.Builder(context).build().apply { setMediaItems(uris.map(MediaItem::fromUri)); prepare() } }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(factory = { PlayerView(it).apply { this.player = player } }, modifier = modifier)
}
private fun formatDuration(ms: Long): String = "%d:%02d".format(ms / 60_000, (ms / 1_000) % 60)
private fun operationTitle(op: EditOperation) = when (op) { is RemoveFumbles -> "Fumble removal"; is SetColorGrade -> "Automatic color"; is SetAudioCleanup -> "Background noise cleanup"; is SetAudioLevels -> "Dialogue leveling"; is AddTransition -> "Transition"; is AddMotionPreset -> "Motion preset"; is AddSoundEffect -> "Sound effect"; is SetBackgroundMusic -> "Background music"; is AddCaptions -> "Captions" }
private fun operationDetail(op: EditOperation) = when (op) { is RemoveFumbles -> "${op.ranges.size} high-confidence cuts"; is SetColorGrade -> "Natural, skin-safe grade"; is SetAudioCleanup -> "Local speech-safe processing"; is SetAudioLevels -> "${op.dialogueLufs.toInt()} LUFS dialogue"; is AddTransition -> op.preset; is AddMotionPreset -> op.preset; is AddSoundEffect -> op.catalogId; is SetBackgroundMusic -> op.catalogId; is AddCaptions -> "${op.cues.size} timed caption cards" }

@Composable private fun CtrlBTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF3157D5), background = Color(0xFFF5F2EA), surface = Color(0xFFFFFCF5)), content = content)
