package ai.ctrlb.mobile

import ai.ctrlb.mobile.domain.*
import ai.ctrlb.mobile.data.ProjectStore
import ai.ctrlb.mobile.media.DeviceCapability
import ai.ctrlb.mobile.media.AndroidDerivativeWriter
import ai.ctrlb.mobile.media.SelectiveDerivativePreparer
import ai.ctrlb.mobile.media.DerivativeAuthorization
import ai.ctrlb.mobile.auth.AuthTokenProvider
import ai.ctrlb.mobile.planner.*
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

data class EditorState(
    val sources: List<SourceMedia> = emptyList(),
    val preferences: WizardPreferences = WizardPreferences(),
    val creatorPreferences: CreatorPreferenceProfile = CreatorPreferenceProfile(),
    val project: MobileProject? = null,
    val capabilityMessage: String? = null,
    val previewUri: String? = null,
    val jobMessage: String? = null,
    val initialRequest: String? = null,
    val isProcessing: Boolean = false,
) {
    val source: SourceMedia? get() = sources.firstOrNull()
}

class EditorViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(EditorState())
    val state = mutable.asStateFlow()
    private val store = ProjectStore(application)

    fun cancelAnalysis() {
        val project = mutable.value.project ?: return
        WorkManager.getInstance(getApplication()).cancelAllWorkByTag("analysis:${project.id}")
        val run = project.analysis.agentRun
        mutable.value = mutable.value.copy(isProcessing = false, jobMessage = "Cancelling video analysis…")
        if (run == null || BuildConfig.AGENT_RUNS_URL.isBlank()) return
        viewModelScope.launch {
            runCatching { AgentRunClient(BuildConfig.AGENT_RUNS_URL, AuthTokenProvider::currentToken).cancel(run.runId) }
                .onSuccess { cancelled ->
                    val latest = store.load(project.id)
                    val updated = latest.copy(analysis = latest.analysis.copy(agentRun = PersistedAgentRun(
                        cancelled.contractVersion, cancelled.runId, cancelled.projectId, cancelled.projectRevision,
                        cancelled.status, cancelled.events, cancelled.decision, run.fulfilledEvidenceJobIds, run.recovery, cancelled.usage
                    )))
                    store.save(updated)
                    mutable.value = mutable.value.copy(project = updated, jobMessage = "Video analysis cancelled")
                }
        }
    }

    init {
        viewModelScope.launch {
            mutable.value = mutable.value.copy(creatorPreferences = store.loadCreatorPreferences())
        }
    }

    fun import(uris: List<Uri>) = viewModelScope.launch {
        val sources = uris.distinct().map { uri -> readSource(uri) }
        require(sources.isNotEmpty()) { "Choose at least one video." }
        mutable.value = EditorState(sources = sources, creatorPreferences = mutable.value.creatorPreferences)
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

    fun generateDraft(prompt: String, words: List<TranscriptWord> = emptyList()) {
        val current = mutable.value; val sources = current.sources
        val brief = prompt.trim().take(2_000)
        require(brief.isNotEmpty()) { "Tell ctrlB what you want to make." }
        val source = requireNotNull(sources.firstOrNull())
        val preferences = preferencesFromBrief(brief, current.preferences)
            .copy(aspectRatio = source.detectedAspectRatio())
        val durationMs = sources.sumOf { it.durationMs }
        val candidates = FumbleDetector.detect(words, durationMs, preferences.pace)
        val operations = buildList<EditOperation> {
            if (source.hasAudio) { add(SetAudioCleanup()); add(SetAudioLevels()) }
            if (preferences.music == MusicChoice.AUTOMATIC) add(SetBackgroundMusic(catalogId = "automatic"))
        }
        // Export may preserve or reduce source resolution, never invent extra detail by upscaling.
        val sourceCeiling = sources.renderSizeWithin(3840, 2160)
        val capability = DeviceCapability.evaluate(getApplication(), sourceCeiling.longSide, sourceCeiling.shortSide)
        val capabilityReport = DeviceCapability.report(getApplication())
        val exportSize = sources.renderSizeWithin(
            maxOf(capability.recommendedWidth, capability.recommendedHeight),
            minOf(capability.recommendedWidth, capability.recommendedHeight)
        )
        val project = MobileProject(title = "Talking head edit", source = source, sources = sources, preferences = preferences, creatorPreferences = current.creatorPreferences, analysis = Analysis(words = words, speechHints = candidates), operations = operations,
            export = ExportSettings(exportSize.width, exportSize.height, if (capability.allow4k) "video/hevc" else "video/avc", if (capability.allow4k) 45_000_000 else 16_000_000))
        mutable.value = current.copy(
            project = project,
            preferences = preferences,
            capabilityMessage = capabilityReport.userFacingSummary(),
            jobMessage = "Reading your clips and preparing a proposal…",
            initialRequest = brief,
            isProcessing = true,
        )
        viewModelScope.launch { store.save(project) }
        val request = OneTimeWorkRequestBuilder<AnalysisWorker>().setInputData(
            Data.Builder().putString("projectId", project.id).putString("prompt", brief).build()
        ).addTag("analysis:${project.id}").build()
        val workManager = WorkManager.getInstance(getApplication())
        workManager.enqueue(request)
        viewModelScope.launch {
            val info = awaitWork(workManager, request.id) { running ->
                val stage = running.progress.getString("stage")
                val percent = running.progress.getInt("percent", 0)
                mutable.value = mutable.value.copy(
                    jobMessage = processingMessage(stage, percent),
                    isProcessing = true,
                )
                runCatching { store.load(project.id) }.getOrNull()?.let { latest ->
                    if (latest.analysis.agentRun?.events?.size != mutable.value.project?.analysis?.agentRun?.events?.size) {
                        mutable.value = mutable.value.copy(project = latest)
                    }
                }
            }
            if (info.state == WorkInfo.State.SUCCEEDED) {
                val analyzed = store.load(project.id)
                mutable.value = mutable.value.copy(project = analyzed, jobMessage = "Analysis complete", isProcessing = false)
                renderPreview(analyzed, simulatePendingProposal = analyzed.analysis.pendingProposal != null)
            }
            else mutable.value = mutable.value.copy(
                jobMessage = info.outputData.getString("error") ?: "Video analysis failed.",
                isProcessing = false,
            )
        }
    }

    private fun preferencesFromBrief(brief: String, fallback: WizardPreferences): WizardPreferences {
        val text = brief.lowercase()
        val platform = when {
            "tiktok" in text -> Platform.TIKTOK
            "instagram" in text || "reel" in text -> Platform.INSTAGRAM
            "linkedin" in text -> Platform.LINKEDIN
            "youtube" in text -> Platform.YOUTUBE
            else -> fallback.platform
        }
        val pace = when {
            listOf("tight", "fast", "snappy", "short").any(text::contains) -> Pace.TIGHT
            listOf("relaxed", "slow", "calm").any(text::contains) -> Pace.RELAXED
            else -> fallback.pace
        }
        val music = when {
            listOf("no music", "without music", "remove music").any(text::contains) -> MusicChoice.NONE
            "music" in text -> MusicChoice.AUTOMATIC
            else -> fallback.music
        }
        val captions = when {
            listOf("no captions", "without captions", "no subtitles").any(text::contains) -> false
            listOf("captions", "subtitles").any(text::contains) -> true
            else -> fallback.captions
        }
        return fallback.copy(platform = platform, pace = pace, music = music, captions = captions)
    }

    fun toggle(id: String) { mutable.value.project?.let { project -> updateProject(project.replaceOperations(project.operations.map { if (it.id == id) toggle(it) else it })) } }
    fun applyProposal() {
        mutable.value.project?.let { project ->
            val proposal = project.analysis.pendingProposal ?: return
            val validated = runCatching {
                OperationValidator.validate(project, proposal.operations, proposal.expectedRevision)
            }.getOrElse { error ->
                mutable.value = mutable.value.copy(jobMessage = error.message ?: "This proposal is no longer current.")
                return
            }
            val checkpoint = AppliedEditCheckpoint(
                summary = proposal.summary,
                changes = editResultLines(project.operations, validated),
                revisionBefore = project.revision,
                revisionAfter = project.revision + 1
            )
            val applied = project.replaceOperations(validated).copy(
                creatorPreferences = CreatorPreferenceLearner.confirmedProposal(project.creatorPreferences, project, proposal),
                analysis = project.analysis.copy(
                    editorialPlan = proposal.plan,
                    pendingProposal = null,
                    appliedEditCheckpoints = (project.analysis.appliedEditCheckpoints + checkpoint).takeLast(30)
                )
            )
            viewModelScope.launch { store.saveCreatorPreferences(applied.creatorPreferences) }
            updateProject(applied)
            mutable.value = mutable.value.copy(jobMessage = "Proposal applied to the interactive preview")
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
            if (project.analysis.agentRun?.status !in setOf(null, AgentRunStatus.FAILED, AgentRunStatus.CANCELLED)) {
                continueAgentConversation(project, request)
                return
            }
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
    fun recoverAgent(action: AgentRecoveryAction) {
        val current = mutable.value
        val project = current.project ?: return
        val previous = project.analysis.agentRun ?: return
        viewModelScope.launch {
            val deviceSessionId = "android-${project.id}"
            val uploadClient = DerivativeUploadClient(
                BuildConfig.AGENT_RUNS_URL, AuthTokenProvider::currentToken, project.id, deviceSessionId
            )
            val preparer = SelectiveDerivativePreparer(
                java.io.File(getApplication<Application>().cacheDir, "agent-derivatives"),
                AndroidDerivativeWriter(getApplication())
            )
            val coordinator = AgentRunCoordinator(
                AgentRunClient(BuildConfig.AGENT_RUNS_URL, AuthTokenProvider::currentToken),
                AgentEvidenceFulfiller { job ->
                    val derivative = preparer.prepare(DerivativeAuthorization(job), project.orderedSources)
                    try { uploadClient.upload(derivative) }
                    finally { java.io.File(derivative.localPath).delete() }
                }
            )
            val request = AgentRunRequest(
                projectId = project.id,
                deviceSessionId = deviceSessionId,
                projectRevision = previous.projectRevision,
                intent = project.analysis.revisionConversation.firstOrNull()?.request
                    ?: "Make this video publishable",
                sources = project.orderedSources.map { source ->
                    Layer2SourceInventory(
                        source.fingerprint, source.fingerprint, source.durationMs,
                        source.width, source.height, source.hasAudio
                    )
                },
                transcript = sourceTranscript(project.analysis.words, project.orderedSources)
            )
            runCatching {
                coordinator.recover(previous, request, action) { snapshot ->
                    val latest = store.load(project.id)
                    val updated = latest.copy(analysis = latest.analysis.copy(agentRun = snapshot))
                    store.save(updated)
                    mutable.value = mutable.value.copy(project = updated)
                }
            }.onSuccess { snapshot ->
                AgentDecisionMapper.pendingProposal(snapshot)?.let { proposal ->
                    val latest = store.load(project.id)
                    val updated = latest.copy(analysis = latest.analysis.copy(
                        agentRun = snapshot,
                        pendingProposal = proposal
                    ))
                    store.save(updated)
                    mutable.value = mutable.value.copy(project = updated)
                }
                mutable.value = mutable.value.copy(
                    jobMessage = when {
                        action == AgentRecoveryAction.REVIEW_ISSUE -> "The issue is shown below. Nothing was applied."
                        snapshot.status == AgentRunStatus.PROPOSAL_READY -> "Repaired proposal ready to review"
                        else -> "Response-format recovery updated"
                    }
                )
            }.onFailure { error ->
                mutable.value = mutable.value.copy(
                    jobMessage = "Recovery could not continue: ${error.message ?: "check the connection and retry"}"
                )
            }
        }
    }

    private fun continueAgentConversation(project: MobileProject, message: String) {
        if (message.isBlank()) return
        val previous = project.analysis.agentRun ?: return
        viewModelScope.launch {
            mutable.value = mutable.value.copy(isProcessing = true, jobMessage = "Continuing the conversation…")
            val deviceSessionId = "android-${project.id}"
            val client = AgentRunClient(BuildConfig.AGENT_RUNS_URL, AuthTokenProvider::currentToken)
            val uploadClient = DerivativeUploadClient(BuildConfig.AGENT_RUNS_URL, AuthTokenProvider::currentToken, project.id, deviceSessionId)
            val preparer = SelectiveDerivativePreparer(
                java.io.File(getApplication<Application>().cacheDir, "agent-derivatives"), AndroidDerivativeWriter(getApplication())
            )
            val coordinator = AgentRunCoordinator(client, AgentEvidenceFulfiller { job ->
                val derivative = preparer.prepare(DerivativeAuthorization(job), project.orderedSources)
                try { uploadClient.upload(derivative) } finally { java.io.File(derivative.localPath).delete() }
            })
            val request = AgentRunRequest(
                projectId = project.id, deviceSessionId = deviceSessionId, projectRevision = project.revision,
                intent = message,
                sources = project.orderedSources.map { source -> Layer2SourceInventory(
                    source.fingerprint, source.fingerprint, source.durationMs, source.width, source.height, source.hasAudio
                ) },
                transcript = sourceTranscript(project.analysis.words, project.orderedSources)
            )
            val json = Json { encodeDefaults = true }
            val existingOperations = project.operations.map { operation ->
                json.encodeToJsonElement(EditOperation.serializer(), operation).jsonObject
            }
            runCatching {
                coordinator.message(previous, request, message, existingOperations) { snapshot ->
                    val latest = store.load(project.id)
                    val updated = latest.copy(analysis = latest.analysis.copy(agentRun = snapshot))
                    store.save(updated)
                    mutable.value = mutable.value.copy(project = updated)
                }
            }.onSuccess { snapshot ->
                val latest = store.load(project.id)
                val proposal = AgentDecisionMapper.pendingProposal(snapshot)
                val response = when (snapshot.decision["type"]?.jsonPrimitive?.content) {
                    "clarification" -> AgentDecisionMapper.clarification(snapshot).firstOrNull()?.prompt ?: "I need one choice before continuing."
                    "edit_program" -> snapshot.decision["summary"]?.jsonPrimitive?.content ?: "Updated proposal ready to review."
                    else -> snapshot.decision["reason"]?.jsonPrimitive?.content ?: "Conversation updated."
                }
                val updated = latest.copy(analysis = latest.analysis.copy(
                    agentRun = snapshot,
                    pendingProposal = proposal ?: latest.analysis.pendingProposal,
                    revisionConversation = (latest.analysis.revisionConversation + EditorialConversationTurn(
                        request = message.trim(), response = response,
                        evidenceIds = proposal?.evidenceIds.orEmpty()
                    )).takeLast(30)
                ))
                store.save(updated)
                mutable.value = mutable.value.copy(project = updated, isProcessing = false, jobMessage = response)
                if (proposal != null) renderPreview(updated, simulatePendingProposal = true)
            }.onFailure { error ->
                mutable.value = mutable.value.copy(isProcessing = false, jobMessage = "Conversation could not continue: ${error.message ?: "try again"}")
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
    fun undo() { mutable.value.project?.let { updateProject(it.undoOnce()) } }
    fun undoCheckpoint(checkpointId: String) {
        val project = mutable.value.project ?: return
        if (project.analysis.appliedEditCheckpoints.lastOrNull()?.id != checkpointId) {
            mutable.value = mutable.value.copy(jobMessage = "Only the latest edit checkpoint can be undone.")
            return
        }
        updateProject(project.undoOnce(), preview = true)
        mutable.value = mutable.value.copy(jobMessage = "Edit checkpoint undone")
    }
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
        is RemoveFumbles -> op.copy(enabled = !op.enabled); is RemoveAwkwardSilences -> op.copy(enabled = !op.enabled); is SetColorGrade -> op.copy(enabled = !op.enabled); is SetAudioCleanup -> op.copy(enabled = !op.enabled); is SetAudioLevels -> op.copy(enabled = !op.enabled)
        is AddTransition -> op.copy(enabled = !op.enabled); is AddMotionPreset -> op.copy(enabled = !op.enabled); is AddSoundEffect -> op.copy(enabled = !op.enabled); is SetBackgroundMusic -> op.copy(enabled = !op.enabled); is AddCaptions -> op.copy(enabled = !op.enabled); is ApplySemanticVisual -> op.copy(enabled = !op.enabled)
    }
    private fun processingMessage(stage: String?, percent: Int): String = when {
        stage?.startsWith("transcript-") == true -> "Preparing speech analysis…"
        stage?.startsWith("upload-") == true -> "Transcribing speech… $percent%"
        stage == "speech-hints" -> "Reviewing speech and take structure…"
        stage == "agent-run" || stage == "planner" -> "Preparing your edit proposal…"
        else -> "Reading your clips and preparing a proposal…"
    }
    private fun editResultLines(before: List<EditOperation>, after: List<EditOperation>): List<String> {
        val beforeById = before.associateBy(EditOperation::id)
        val afterById = after.associateBy(EditOperation::id)
        val added = after.filter { it.id !in beforeById }.map { "+ ${it.editResultLabel()}" }
        val removed = before.filter { it.id !in afterById }.map { "− ${it.editResultLabel()}" }
        val changed = after.filter { operation ->
            beforeById[operation.id]?.let { it != operation } == true
        }.map { "~ ${it.editResultLabel()}" }
        return (added + removed + changed).ifEmpty { listOf("~ Updated the current edit") }.take(12)
    }
    private fun EditOperation.editResultLabel(): String = when (this) {
        is RemoveFumbles -> "Tightened ${ranges.size} speech range${if (ranges.size == 1) "" else "s"}"
        is RemoveAwkwardSilences -> "Removed ${ranges.size} awkward pause${if (ranges.size == 1) "" else "s"}"
        is SetColorGrade -> "Updated color grade"
        is SetAudioCleanup -> "Updated dialogue cleanup"
        is SetAudioLevels -> "Updated audio levels"
        is AddTransition -> "Updated transition"
        is AddMotionPreset -> "Updated motion"
        is AddSoundEffect -> "Updated sound effect"
        is SetBackgroundMusic -> "Updated background music"
        is AddCaptions -> "Updated ${cues.size} caption cue${if (cues.size == 1) "" else "s"}"
        is ApplySemanticVisual -> "Updated ${treatment.name.lowercase().replace('_', ' ')}"
    }

    private suspend fun awaitWork(
        manager: WorkManager,
        id: UUID,
        onUpdate: suspend (WorkInfo) -> Unit = {},
    ): WorkInfo {
        while (true) {
            val info = withContext(Dispatchers.IO) { manager.getWorkInfoById(id).get() }
            if (info != null && info.state.isFinished) return info
            if (info != null) onUpdate(info)
            delay(250)
        }
    }
}
