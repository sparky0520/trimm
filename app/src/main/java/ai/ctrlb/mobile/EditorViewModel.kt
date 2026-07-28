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

data class EditorState(val sources: List<SourceMedia> = emptyList(), val preferences: WizardPreferences = WizardPreferences(), val creatorPreferences: CreatorPreferenceProfile = CreatorPreferenceProfile(), val wizardStep: Int = 0, val project: MobileProject? = null, val capabilityMessage: String? = null, val previewUri: String? = null, val jobMessage: String? = null) {
    val source: SourceMedia? get() = sources.firstOrNull()
}

class EditorViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(EditorState())
    val state = mutable.asStateFlow()
    private val store = ProjectStore(application)

    init {
        viewModelScope.launch {
            mutable.value = mutable.value.copy(creatorPreferences = store.loadCreatorPreferences())
        }
    }

    fun import(uris: List<Uri>) = viewModelScope.launch {
        val sources = uris.distinct().map { uri -> readSource(uri) }
        require(sources.isNotEmpty()) { "Choose at least one video." }
        mutable.value = EditorState(sources = sources, creatorPreferences = mutable.value.creatorPreferences, wizardStep = 1)
    }

    private suspend fun readSource(uri: Uri): SourceMedia = withContext(Dispatchers.IO) {
        runCatching { getApplication<Application>().contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val retriever = MediaMetadataRetriever().apply { setDataSource(getApplication(), uri) }
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        retriever.release()
        require(duration > 0 && width > 0 && height > 0) { "The selected file is not a supported video." }
        val digest = MessageDigest.getInstance("SHA-256")
        requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri)) { "The selected file cannot be opened." }.use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        val fingerprint = digest.digest().joinToString("") { "%02x".format(it) }
        SourceMedia(uri.toString(), fingerprint, duration, width, height, rotation)
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
        val preferences = current.preferences.copy(captions = false)
        val source = requireNotNull(sources.firstOrNull())
        val durationMs = sources.sumOf { it.durationMs }
        val candidates = FumbleDetector.detect(words, durationMs, preferences.pace)
        val automatic = FumbleDetector.automatic(candidates)
        val operations = buildList<EditOperation> {
            if (automatic.isNotEmpty()) add(RemoveFumbles(ranges = automatic.map { it.range }, confidence = automatic.minOf { it.confidence }, reasons = automatic.map { it.reason }.distinct()))
            if (source.hasAudio) { add(SetAudioCleanup()); add(SetAudioLevels()) }
            if (preferences.music == MusicChoice.AUTOMATIC) add(SetBackgroundMusic(catalogId = "automatic"))
        }
        val capability = DeviceCapability.evaluate(getApplication())
        val (exportWidth, exportHeight) = when (preferences.aspectRatio) {
            AspectRatio.LANDSCAPE -> capability.recommendedWidth to capability.recommendedHeight
            AspectRatio.PORTRAIT -> capability.recommendedHeight to capability.recommendedWidth
            AspectRatio.SQUARE -> capability.recommendedHeight to capability.recommendedHeight
        }
        val project = MobileProject(title = "Talking head edit", source = source, sources = sources, preferences = preferences, creatorPreferences = current.creatorPreferences, analysis = Analysis(words = words, lowConfidenceFumbles = candidates.filter { it.confidence < .93f }), operations = operations,
            export = ExportSettings(exportWidth, exportHeight, if (capability.allow4k) "video/hevc" else "video/avc", if (capability.allow4k) 45_000_000 else 16_000_000))
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
    fun applyProposal() {
        mutable.value.project?.let { project ->
            val proposal = project.analysis.pendingProposal ?: return
            val validated = runCatching {
                OperationValidator.validate(project, proposal.operations, proposal.expectedRevision)
            }.getOrElse { error ->
                mutable.value = mutable.value.copy(jobMessage = error.message ?: "This proposal is no longer current.")
                return
            }
            val applied = project.replaceOperations(validated).copy(
                creatorPreferences = CreatorPreferenceLearner.confirmedProposal(project.creatorPreferences, project, proposal),
                analysis = project.analysis.copy(editorialPlan = proposal.plan, pendingProposal = null)
            )
            viewModelScope.launch { store.saveCreatorPreferences(applied.creatorPreferences) }
            updateProject(applied, true)
        }
    }
    fun rejectProposal() {
        mutable.value.project?.let { project ->
            if (project.analysis.pendingProposal == null) return
            updateProject(project.copy(analysis = project.analysis.copy(pendingProposal = null)))
        }
    }
    fun reviseStory(request: String) {
        mutable.value.project?.let { project ->
            when (val result = ConversationalRevisionEngine.revise(project, request)) {
                is ConversationalRevisionResult.Answer ->
                    updateProject(result.project).also {
                        mutable.value = mutable.value.copy(jobMessage = result.text)
                    }
                is ConversationalRevisionResult.Clarification ->
                    updateProject(result.project).also {
                        mutable.value = mutable.value.copy(jobMessage = result.question)
                    }
                is ConversationalRevisionResult.Revision ->
                    run {
                        mutable.value = mutable.value.copy(project = result.project, jobMessage = "Revised proposal ready to review")
                        viewModelScope.launch {
                            store.save(result.project)
                            renderPreview(result.project, simulatePendingProposal = true)
                        }
                    }
            }
        }
    }
    fun recordCreatorPreference(kind: CreatorPreferenceKind, value: String) {
        val current = mutable.value
        val profile = (current.project?.creatorPreferences ?: current.creatorPreferences)
            .record(kind, value, "explicit:${UUID.randomUUID()}", explicit = true)
        persistCreatorPreferences(profile)
    }
    fun setCreatorPreferenceEnabled(kind: CreatorPreferenceKind, value: String, enabled: Boolean) {
        val current = mutable.value
        val profile = (current.project?.creatorPreferences ?: current.creatorPreferences)
            .setEnabled(kind, value, enabled)
        persistCreatorPreferences(profile)
    }
    fun resetCreatorPreferences(kind: CreatorPreferenceKind? = null, value: String? = null) {
        val current = mutable.value
        val profile = (current.project?.creatorPreferences ?: current.creatorPreferences).reset(kind, value)
        persistCreatorPreferences(profile)
    }
    private fun persistCreatorPreferences(profile: CreatorPreferenceProfile) {
        val current = mutable.value
        val project = current.project?.copy(creatorPreferences = profile)
        mutable.value = current.copy(creatorPreferences = profile, project = project)
        viewModelScope.launch {
            store.saveCreatorPreferences(profile)
            if (project != null) store.save(project)
        }
    }
    fun undo() { mutable.value.project?.let { updateProject(it.undoOnce(), true) } }
    fun export() { mutable.value.project?.let { project ->
        val estimated = (project.durationMs / 1_000.0 * project.export.bitrate / 8).toLong()
        val manager = WorkManager.getInstance(getApplication()); val request = OneTimeWorkRequestBuilder<ExportWorker>().setInputData(Data.Builder().putString("projectId", project.id).putLong("estimatedBytes", estimated).build()).addTag("export:${project.id}").build()
        mutable.value = mutable.value.copy(jobMessage = "Export queued")
        manager.enqueue(request); viewModelScope.launch { val info = awaitWork(manager, request.id); mutable.value = mutable.value.copy(jobMessage = if (info.state == WorkInfo.State.SUCCEEDED) "Export saved to Movies/ctrlB" else "Export failed: ${info.outputData.getString("error") ?: info.state}") }
    } }
    private fun updateProject(project: MobileProject, preview: Boolean = false) { mutable.value = mutable.value.copy(project = project, creatorPreferences = project.creatorPreferences); viewModelScope.launch { store.save(project); if (preview) renderPreview(project) } }
    private fun renderPreview(project: MobileProject, simulatePendingProposal: Boolean = false) {
        val manager = WorkManager.getInstance(getApplication())
        val request = OneTimeWorkRequestBuilder<PreviewWorker>().setInputData(Data.Builder()
            .putString("projectId", project.id)
            .putBoolean("simulatePendingProposal", simulatePendingProposal)
            .build()).addTag("preview:${project.id}").build()
        mutable.value = mutable.value.copy(jobMessage = if (simulatePendingProposal) "Simulating revised preview…" else "Rendering edited preview…")
        manager.enqueueUniqueWork("preview:${project.id}", ExistingWorkPolicy.REPLACE, request)
        viewModelScope.launch {
            val info = awaitWork(manager, request.id)
            mutable.value = if (info.state == WorkInfo.State.SUCCEEDED) mutable.value.copy(previewUri = info.outputData.getString("previewUri"), jobMessage = info.outputData.getString("warning") ?: "Edited preview ready")
            else mutable.value.copy(jobMessage = "Preview failed: ${info.outputData.getString("error") ?: info.state}")
        }
    }
    private fun toggle(op: EditOperation): EditOperation = when (op) {
        is RemoveFumbles -> op.copy(enabled = !op.enabled); is SetColorGrade -> op.copy(enabled = !op.enabled); is SetAudioCleanup -> op.copy(enabled = !op.enabled); is SetAudioLevels -> op.copy(enabled = !op.enabled)
        is AddTransition -> op.copy(enabled = !op.enabled); is AddMotionPreset -> op.copy(enabled = !op.enabled); is AddSoundEffect -> op.copy(enabled = !op.enabled); is SetBackgroundMusic -> op.copy(enabled = !op.enabled); is AddCaptions -> op.copy(enabled = !op.enabled); is ApplySemanticVisual -> op.copy(enabled = !op.enabled)
    }
    private suspend fun awaitWork(manager: WorkManager, id: UUID): WorkInfo {
        while (true) {
            val info = withContext(Dispatchers.IO) { manager.getWorkInfoById(id).get() }
            if (info != null && info.state.isFinished) return info
            delay(250)
        }
    }
}
