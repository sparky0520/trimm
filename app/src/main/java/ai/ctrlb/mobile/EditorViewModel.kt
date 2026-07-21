package ai.ctrlb.mobile

import ai.ctrlb.mobile.domain.*
import ai.ctrlb.mobile.data.ProjectStore
import ai.ctrlb.mobile.media.DeviceCapability
import ai.ctrlb.mobile.work.AnalysisWorker
import ai.ctrlb.mobile.work.ExportWorker
import ai.ctrlb.mobile.work.PreviewWorker
import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.ExistingWorkPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest

data class EditorState(val sources: List<SourceMedia> = emptyList(), val preferences: WizardPreferences = WizardPreferences(), val wizardStep: Int = 0, val project: MobileProject? = null, val capabilityMessage: String? = null, val previewUri: String? = null, val jobMessage: String? = null) {
    val source: SourceMedia? get() = sources.firstOrNull()
}

class EditorViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(EditorState())
    val state = mutable.asStateFlow()
    private val store = ProjectStore(application)

    fun import(uris: List<Uri>) = viewModelScope.launch {
        val sources = uris.distinct().map { uri -> readSource(uri) }
        require(sources.isNotEmpty()) { "Choose at least one video." }
        mutable.value = EditorState(sources = sources, wizardStep = 1)
    }

    private fun readSource(uri: Uri): SourceMedia {
        runCatching { getApplication<Application>().contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val retriever = MediaMetadataRetriever().apply { setDataSource(getApplication(), uri) }
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        retriever.release()
        require(duration > 0 && width > 0 && height > 0) { "The selected file is not a supported video." }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest("$uri:$duration:$width:$height".toByteArray()).joinToString("") { "%02x".format(it) }
        return SourceMedia(uri.toString(), fingerprint, duration, width, height, rotation)
    }

    fun moveClip(index: Int, offset: Int) {
        val nextIndex = index + offset
        val current = mutable.value
        val clips = current.project?.orderedSources ?: current.sources
        if (index !in clips.indices || nextIndex !in clips.indices) return
        val reordered = clips.toMutableList().apply { add(nextIndex, removeAt(index)) }
        current.project?.let { updateProject(it.replaceSources(reordered), true) }
            ?: run { mutable.value = current.copy(sources = reordered) }
    }

    fun preferences(value: WizardPreferences) { mutable.value = mutable.value.copy(preferences = value) }
    fun next() { mutable.value = mutable.value.copy(wizardStep = (mutable.value.wizardStep + 1).coerceAtMost(3)) }
    fun back() { mutable.value = mutable.value.copy(wizardStep = (mutable.value.wizardStep - 1).coerceAtLeast(1)) }

    fun generateDraft(words: List<TranscriptWord> = emptyList()) {
        val current = mutable.value; val sources = current.sources
        val source = requireNotNull(sources.firstOrNull())
        val durationMs = sources.sumOf { it.durationMs }
        val candidates = FumbleDetector.detect(words, durationMs, current.preferences.pace)
        val automatic = FumbleDetector.automatic(candidates)
        val operations = buildList<EditOperation> {
            if (automatic.isNotEmpty()) add(RemoveFumbles(ranges = automatic.map { it.range }, confidence = automatic.minOf { it.confidence }, reasons = automatic.map { it.reason }.distinct()))
            add(SetColorGrade(grade = GradeEngine.natural(emptyList(), current.preferences.style)))
            if (source.hasAudio) { add(SetAudioCleanup()); add(SetAudioLevels()) }
            if (current.preferences.music == MusicChoice.AUTOMATIC) add(SetBackgroundMusic(catalogId = "automatic"))
            if (current.preferences.captions && words.isNotEmpty()) add(AddCaptions(cues = CaptionSegmenter.segment(words)))
            add(AddMotionPreset(range = TimeRange(0, minOf(2_400, durationMs)), preset = "talking-head-title", intensity = .45f))
            add(AddSoundEffect(atSourceMs = 0, catalogId = "bundled-soft-rise", gainDb = -16f))
            automatic.take(3).forEach { candidate -> add(AddTransition(atSourceMs = candidate.range.endMs, preset = "dip", durationMs = 220)) }
        }
        val capability = DeviceCapability.evaluate(getApplication())
        val project = MobileProject(title = "Talking head edit", source = source, sources = sources, preferences = current.preferences, analysis = Analysis(words = words, lowConfidenceFumbles = candidates.filter { it.confidence < .93f }), operations = operations,
            export = ExportSettings(capability.recommendedWidth, capability.recommendedHeight, if (capability.allow4k) "video/hevc" else "video/avc", if (capability.allow4k) 45_000_000 else 16_000_000))
        mutable.value = current.copy(project = project, wizardStep = 4, capabilityMessage = capability.reasons.takeIf { it.isNotEmpty() }?.joinToString(". "))
        viewModelScope.launch { store.save(project) }
        val request = OneTimeWorkRequestBuilder<AnalysisWorker>().setInputData(Data.Builder().putString("projectId", project.id).build()).addTag("analysis:${project.id}").build()
        val workManager = WorkManager.getInstance(getApplication())
        workManager.enqueue(request)
        viewModelScope.launch {
            val info = awaitWork(workManager, request.id)
            if (info.state == WorkInfo.State.SUCCEEDED) { val analyzed = store.load(project.id); mutable.value = mutable.value.copy(project = analyzed, jobMessage = "Analysis complete"); renderPreview(analyzed) }
            else mutable.value = mutable.value.copy(capabilityMessage = info.outputData.getString("error") ?: "Video analysis failed.")
        }
    }

    fun toggle(id: String) { mutable.value.project?.let { project -> updateProject(project.replaceOperations(project.operations.map { if (it.id == id) toggle(it) else it }), true) } }
    fun undo() { mutable.value.project?.let { updateProject(it.undoOnce(), true) } }
    fun export() { mutable.value.project?.let { project ->
        val estimated = (project.durationMs / 1_000.0 * project.export.bitrate / 8).toLong()
        val manager = WorkManager.getInstance(getApplication()); val request = OneTimeWorkRequestBuilder<ExportWorker>().setInputData(Data.Builder().putString("projectId", project.id).putLong("estimatedBytes", estimated).build()).addTag("export:${project.id}").build()
        mutable.value = mutable.value.copy(jobMessage = "Export queued")
        manager.enqueue(request); viewModelScope.launch { val info = awaitWork(manager, request.id); mutable.value = mutable.value.copy(jobMessage = if (info.state == WorkInfo.State.SUCCEEDED) "Export saved to Movies/ctrlB" else "Export failed: ${info.outputData.getString("error") ?: info.state}") }
    } }
    private fun updateProject(project: MobileProject, preview: Boolean = false) { mutable.value = mutable.value.copy(project = project); viewModelScope.launch { store.save(project); if (preview) renderPreview(project) } }
    private fun renderPreview(project: MobileProject) {
        val manager = WorkManager.getInstance(getApplication())
        val request = OneTimeWorkRequestBuilder<PreviewWorker>().setInputData(Data.Builder().putString("projectId", project.id).build()).addTag("preview:${project.id}").build()
        mutable.value = mutable.value.copy(jobMessage = "Rendering edited preview…")
        manager.enqueueUniqueWork("preview:${project.id}", ExistingWorkPolicy.REPLACE, request)
        viewModelScope.launch {
            val info = awaitWork(manager, request.id)
            mutable.value = if (info.state == WorkInfo.State.SUCCEEDED) mutable.value.copy(previewUri = info.outputData.getString("previewUri"), jobMessage = info.outputData.getString("warning") ?: "Edited preview ready")
            else mutable.value.copy(jobMessage = "Preview failed: ${info.outputData.getString("error") ?: info.state}")
        }
    }
    private fun toggle(op: EditOperation): EditOperation = when (op) {
        is RemoveFumbles -> op.copy(enabled = !op.enabled); is SetColorGrade -> op.copy(enabled = !op.enabled); is SetAudioCleanup -> op.copy(enabled = !op.enabled); is SetAudioLevels -> op.copy(enabled = !op.enabled)
        is AddTransition -> op.copy(enabled = !op.enabled); is AddMotionPreset -> op.copy(enabled = !op.enabled); is AddSoundEffect -> op.copy(enabled = !op.enabled); is SetBackgroundMusic -> op.copy(enabled = !op.enabled); is AddCaptions -> op.copy(enabled = !op.enabled)
    }
    private suspend fun awaitWork(manager: WorkManager, id: UUID): WorkInfo {
        while (true) {
            val info = withContext(Dispatchers.IO) { manager.getWorkInfoById(id).get() }
            if (info != null && info.state.isFinished) return info
            delay(250)
        }
    }
}
