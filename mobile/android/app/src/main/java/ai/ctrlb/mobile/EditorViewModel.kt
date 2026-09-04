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
import ai.ctrlb.mobile.network.ChatApiService
import ai.ctrlb.mobile.network.ChatRepository
import ai.ctrlb.mobile.network.ChatEditRequest
import ai.ctrlb.mobile.network.supabase
import io.github.jan.supabase.storage.storage
import java.security.MessageDigest

data class EditorState(
    val sources: List<SourceMedia> = emptyList(),
    val preferences: WizardPreferences = WizardPreferences(),
    val creatorPreferences: CreatorPreferenceProfile = CreatorPreferenceProfile(),
    val project: MobileProject? = null,
    val capabilityMessage: String? = null,
    val previewUri: String? = null,
    val jobMessage: String? = null,
    val errorMessage: String? = null,
    val initialRequest: String? = null,
    val pendingChatMessage: String? = null,
    val isProcessing: Boolean = false,
    val pastChats: List<ai.ctrlb.mobile.data.ChatSummary> = emptyList(),
    val isLoadingPastChats: Boolean = false
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
        if (run == null || BuildConfig.PLANNER_URL.isBlank()) return
        val cancelled = run.copy(status = AgentRunStatus.CANCELLED)
        viewModelScope.launch {
            val latest = store.load(project.id)
            val updated = latest.copy(analysis = latest.analysis.copy(agentRun = cancelled))
            store.save(updated)
            mutable.value = mutable.value.copy(project = updated, jobMessage = "Video analysis cancelled")
        }
    }

    fun refreshChats() {
        viewModelScope.launch {
            mutable.value = mutable.value.copy(isLoadingPastChats = true)
            val chats = store.listAll()
            mutable.value = mutable.value.copy(pastChats = chats, isLoadingPastChats = false)
        }
    }

    fun deleteChat(id: String) {
        viewModelScope.launch {
            store.delete(id)
            if (mutable.value.project?.id == id) {
                newChat()
            }
            refreshChats()
        }
    }

    init {
        viewModelScope.launch {
            mutable.value = mutable.value.copy(creatorPreferences = store.loadCreatorPreferences())
        }
        refreshChats()
    }

    fun newChat() {
        mutable.value = EditorState(
            creatorPreferences = mutable.value.creatorPreferences,
            pastChats = mutable.value.pastChats
        )
    }

    fun loadProject(id: String) = viewModelScope.launch {
        try {
            val project = store.load(id)
            val updatedSources = project.orderedSources.map { source ->
                val localUri = Uri.parse(source.uri)
                val exists = runCatching { 
                    getApplication<Application>().contentResolver.openInputStream(localUri)?.close() 
                    true 
                }.getOrDefault(false)
                
                if (exists) {
                    source
                } else {
                    source.copy(uri = "") // Or mark as disconnected
                }
            }
            val reconnected = project.replaceSources(updatedSources)
            mutable.value = mutable.value.copy(
                project = reconnected,
                sources = reconnected.sources,
                errorMessage = null,
                jobMessage = null,
                isProcessing = false
            )

            // Recover missed checkpoints from the background
            runCatching {
                val token = ai.ctrlb.mobile.auth.AuthTokenProvider.currentToken()
                val apiService = ai.ctrlb.mobile.network.ChatApiService.create()
                val response = apiService.getCheckpoints("Bearer $token", id)
                val checkpoints = response.getAsJsonArray("checkpoints")
                
                if (checkpoints.size() > 0) {
                    val currentProject = mutable.value.project ?: return@runCatching
                    
                    // Simple recovery: if we had a pending conversation turn, and we have completed checkpoints, mark it finished
                    val finished = currentProject.copy(analysis = currentProject.analysis.copy(
                        revisionConversation = currentProject.analysis.revisionConversation.map { turn ->
                            if (turn.isPending) turn.copy(response = "Revised proposal ready to review (recovered)", isPending = false, isError = false) else turn
                        }
                    ))
                    store.save(finished)
                    mutable.value = mutable.value.copy(project = finished)
                }
            }
        } catch (e: Exception) {
            mutable.value = mutable.value.copy(errorMessage = "Failed to load chat: ${e.message}")
        }
    }

    fun import(uris: List<Uri>) = viewModelScope.launch {
        val sources = uris.distinct().map { uri -> readSource(uri) }
        require(sources.isNotEmpty()) { "Choose at least one video." }
        mutable.value = EditorState(sources = sources, creatorPreferences = mutable.value.creatorPreferences)
    }

    fun relinkVideo(uri: Uri) = viewModelScope.launch {
        try {
            val project = mutable.value.project ?: return@launch
            val newSource = readSource(uri)
            val updatedSources = project.orderedSources.mapIndexed { index, source ->
                if (index == 0) source.copy(
                    uri = uri.toString(),
                    fingerprint = newSource.fingerprint,
                    durationMs = newSource.durationMs,
                    width = newSource.width,
                    height = newSource.height
                ) else source
            }
            val updatedProject = project.replaceSources(updatedSources)
            store.save(updatedProject)
            mutable.value = mutable.value.copy(
                project = updatedProject,
                sources = updatedProject.sources
            )
        } catch (e: Exception) {
            mutable.value = mutable.value.copy(errorMessage = "Relinking failed: ${e.message}")
        }
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
        require(brief.isNotEmpty()) { "Tell Trimm what you want to make." }
        val source = requireNotNull(sources.firstOrNull())
        val preferences = preferencesFromBrief(brief, current.preferences)
            .copy(aspectRatio = source.detectedAspectRatio())
        val durationMs = sources.sumOf { it.durationMs }
        val candidates = FumbleDetector.detect(words, durationMs, preferences.pace)
        val operations = buildList<EditInstruction> {
            if (source.hasAudio) { add(NormaliseAudio()) }
        }
        // Export may preserve or reduce source resolution, never invent extra detail by upscaling.
        val sourceCeiling = sources.renderSizeWithin(3840, 2160)
        val capability = DeviceCapability.evaluate(getApplication(), sourceCeiling.longSide, sourceCeiling.shortSide)
        val capabilityReport = DeviceCapability.report(getApplication())
        val exportSize = sources.renderSizeWithin(
            maxOf(capability.recommendedWidth, capability.recommendedHeight),
            minOf(capability.recommendedWidth, capability.recommendedHeight)
        )
        val project = MobileProject(title = "New Edit", source = source, sources = sources, preferences = preferences, creatorPreferences = current.creatorPreferences, analysis = Analysis(words = words, speechHints = candidates), operations = operations,
            export = ExportSettings(exportSize.width, exportSize.height, if (capability.allow4k) "video/hevc" else "video/avc", if (capability.allow4k) 45_000_000 else 16_000_000))
        mutable.value = current.copy(
            project = project,
            preferences = preferences,
            capabilityMessage = capabilityReport.userFacingSummary(),
            jobMessage = "Reading your clips and preparing a proposal…",
            errorMessage = null,
            initialRequest = brief,
            isProcessing = true,
        )
        viewModelScope.launch { 
            store.save(project) 
        }
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
                errorMessage = info.outputData.getString("error") ?: "Video analysis failed.",
                jobMessage = null,
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

    fun toggle(id: String) { mutable.value.project?.let { project -> updateProject(project.replaceEditInstructions(project.operations.map { if (it.id == id) toggle(it) else it })) } }
    fun applyProposal() {
        mutable.value.project?.let { project ->
            val proposal = project.analysis.pendingProposal ?: return
            val validated = runCatching {
                InstructionValidator.validate(project, proposal.operations, proposal.expectedRevision)
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
               val applied = project.replaceEditInstructions(validated).copy(

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
        val text = request.trim()
        if (text.isBlank() || mutable.value.isProcessing) return
        val currentProject = mutable.value.project ?: return
        val turnId = java.util.UUID.randomUUID().toString()
        val parentId = currentProject.analysis.activeTurnId ?: currentProject.analysis.revisionConversation.lastOrNull()?.id
        val pendingTurn = EditorialConversationTurn(
            id = turnId,
            parentId = parentId,
            request = text,
            response = "Trimm is thinking…",
            isPending = true,
            isError = false
        )
        val updatedProject = currentProject.copy(
            analysis = currentProject.analysis.copy(
                revisionConversation = (currentProject.analysis.revisionConversation + pendingTurn).takeLast(50),
                activeTurnId = turnId
            )
        )
        mutable.value = mutable.value.copy(
            project = updatedProject,
            isProcessing = true,
            jobMessage = "Continuing the conversation…"
        )
        viewModelScope.launch {
            store.save(updatedProject)
            if (BuildConfig.PLANNER_URL.isNotBlank()) {
                continueChatConversation(updatedProject, turnId, text)
            } else {
                when (val result = ConversationalRevisionEngine.revise(updatedProject, text)) {
                    is ConversationalRevisionResult.Answer -> {
                        val finished = updatedProject.copy(analysis = updatedProject.analysis.copy(
                            revisionConversation = updatedProject.analysis.revisionConversation.map {
                                if (it.id == turnId) it.copy(response = result.text, isPending = false, isError = false) else it
                            }
                        ))
                        store.save(finished)
                        mutable.value = mutable.value.copy(project = finished, jobMessage = result.text, isProcessing = false)
                    }
                    is ConversationalRevisionResult.Clarification -> {
                        val finished = updatedProject.copy(analysis = updatedProject.analysis.copy(
                            revisionConversation = updatedProject.analysis.revisionConversation.map {
                                if (it.id == turnId) it.copy(response = result.question, isPending = false, isError = false) else it
                            }
                        ))
                        store.save(finished)
                        mutable.value = mutable.value.copy(project = finished, jobMessage = result.question, isProcessing = false)
                    }
                    is ConversationalRevisionResult.Revision -> {
                        val finished = result.project.copy(analysis = result.project.analysis.copy(
                            revisionConversation = result.project.analysis.revisionConversation.map {
                                if (it.id == turnId) it.copy(response = "Revised proposal ready to review", isPending = false, isError = false) else it
                            }
                        ))
                        store.save(finished)
                        mutable.value = mutable.value.copy(project = finished, jobMessage = "Revised proposal ready to review", isProcessing = false)
                        renderPreview(finished, simulatePendingProposal = true)
                    }
                }
            }
        }
    }

    fun switchBranch(turnId: String) {
        val project = mutable.value.project ?: return
        val updatedProject = project.copy(
            analysis = project.analysis.copy(
                activeTurnId = turnId
            )
        )
        viewModelScope.launch { store.save(updatedProject) }
        mutable.value = mutable.value.copy(project = updatedProject)
    }

    fun retryTurn(turnId: String) {
        val project = mutable.value.project ?: return
        val turn = project.analysis.revisionConversation.firstOrNull { it.id == turnId } ?: return
        val updatedProject = project.copy(
            analysis = project.analysis.copy(
                revisionConversation = project.analysis.revisionConversation.map {
                    if (it.id == turnId) it.copy(response = "Trimm is thinking…", isPending = true, isError = false) else it
                }
            )
        )
        mutable.value = mutable.value.copy(
            project = updatedProject,
            isProcessing = true,
            errorMessage = null,
            jobMessage = "Retrying conversation…"
        )
        viewModelScope.launch {
            store.save(updatedProject)
            if (BuildConfig.PLANNER_URL.isNotBlank()) {
                continueChatConversation(updatedProject, turnId, turn.request)
            } else {
                reviseStory(turn.request)
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
                BuildConfig.PLANNER_URL, AuthTokenProvider::currentToken, project.id, deviceSessionId
            )
            val preparer = SelectiveDerivativePreparer(
                java.io.File(getApplication<Application>().cacheDir, "agent-derivatives"),
                AndroidDerivativeWriter(getApplication())
            )
            val coordinator = AgentRunCoordinator(
                AgentRunClient(BuildConfig.PLANNER_URL, AuthTokenProvider::currentToken),
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
                sources = project.orderedSources.mapIndexed { index, source ->
                    Layer2SourceInventory(
                        "${source.fingerprint}-$index", source.fingerprint, source.durationMs,
                        source.width, source.height, source.hasAudio
                    )
                },
                transcript = sourceTranscript(project.analysis.words, project.orderedSources),
                existingOperations = project.operations.map { operation ->
                    val json = Json { encodeDefaults = true }
                           json.encodeToJsonElement(EditInstruction.serializer(), operation).jsonObject
                },
                uploadInitialVideoContext = false
            )
            runCatching {
                coordinator.orchestrateStateless(
                    request = request,
                    projectDurationMs = project.durationMs,
                    onProgress = { stage, percent ->
                        mutable.value = mutable.value.copy(jobMessage = processingMessage(stage, percent))
                    },
                    onPersist = { snapshot, plan ->
                        val latest = store.load(project.id)
                        val updated = latest.copy(analysis = latest.analysis.copy(
                            agentRun = snapshot,
                            agentPlan = plan ?: latest.analysis.agentPlan
                        ))
                        store.save(updated)
                        mutable.value = mutable.value.copy(project = updated)
                    }
                )
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

    private var chatListenJob: kotlinx.coroutines.Job? = null
    private val uploadVideoContext = true // Boolean flag to control video upload

    private fun continueChatConversation(project: MobileProject, turnId: String, message: String) {
        val workManager = androidx.work.WorkManager.getInstance(getApplication())
        val data = androidx.work.Data.Builder()
            .putString("projectId", project.id)
            .putString("message", message)
            .putString("turnId", turnId)
            .putBoolean("uploadVideoContext", uploadVideoContext)
            .build()
        val request = androidx.work.OneTimeWorkRequestBuilder<ai.ctrlb.mobile.work.ChatEditWorker>()
            .setInputData(data)
            .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
            .build()
        workManager.enqueue(request)

        mutable.value = mutable.value.copy(jobMessage = "Uploading video context (background)...")

        val repository = ChatRepository()
        viewModelScope.launch {
            // Start listening to the realtime updates for this checkpoint in case the app stays open
            chatListenJob?.cancel()
            chatListenJob = launch {
                repository.listenForCheckpoints(project.id).collect { checkpointData ->
                    val currentProject = mutable.value.project ?: return@collect
                    val finished = currentProject.copy(analysis = currentProject.analysis.copy(
                        revisionConversation = currentProject.analysis.revisionConversation.map {
                            if (it.id == turnId) it.copy(response = "Revised proposal ready to review", isPending = false, isError = false) else it
                        }
                    ))
                    store.save(finished)
                    mutable.value = mutable.value.copy(project = finished, jobMessage = "Applying edits...", isProcessing = false)
                    // TODO: Apply edits
                }
            }
        }
    }


    fun answerClarification(turnId: String, optionId: String, label: String) {
        val project = mutable.value.project ?: return
        val turn = project.analysis.revisionConversation.firstOrNull { it.id == turnId } ?: return
        val updatedProject = project.copy(
            analysis = project.analysis.copy(
                revisionConversation = project.analysis.revisionConversation.map {
                    if (it.id == turnId) it.copy(response = "Choice: $label", isPending = false, isError = false) else it
                }
            )
        )
        viewModelScope.launch { store.save(updatedProject) }
        mutable.value = mutable.value.copy(project = updatedProject)
        reviseStory("${turn.request}\n\nDirection chosen: $label")
    }

    fun recordCreatorPreference(kind: CreatorPreferenceKind, value: String) {
        val current = mutable.value
        val profile = (current.project?.creatorPreferences ?: current.creatorPreferences)
            .record(kind, value, "explicit:${java.util.UUID.randomUUID()}", explicit = true)
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
    fun undo() { mutable.value.project?.let { updateProject(it.restoreCheckpoint()) } }
    fun undoCheckpoint(checkpointId: String) {
        val project = mutable.value.project ?: return
        
        // Find the checkpoint to determine which turn it belonged to
        // If we can't find it, just return
        val targetCheckpointIndex = project.analysis.appliedEditCheckpoints.indexOfFirst { it.id == checkpointId }
        if (targetCheckpointIndex == -1) {
            mutable.value = mutable.value.copy(jobMessage = "Checkpoint not found.")
            return
        }

        // We want to undo this checkpoint, meaning we want to restore to the state BEFORE it was applied.
        var currentProject = project
        while (currentProject.analysis.appliedEditCheckpoints.any { it.id == checkpointId }) {
            val nextProject = currentProject.restoreCheckpoint()
            if (nextProject === currentProject) break // Reached the beginning
            currentProject = nextProject
        }
        
        // Find the turn that corresponds to the checkpoint to get its request text
        // We can look at the active turn of the project right before the checkpoint was applied
        // Wait, currentProject is the state BEFORE the checkpoint. The prompt that caused the checkpoint
        // might be the last one in currentProject's activeConversationPath or we can just look at the 
        // original project's activeConversationPath up to the turn that corresponds to it.
        // Let's just find the first turn that happened after the restored state's revision.
        val undoneTurn = project.analysis.revisionConversation.find { it.id == project.analysis.activeTurnId }
        val promptText = undoneTurn?.request ?: ""

        updateProject(currentProject, preview = true)
        mutable.value = mutable.value.copy(
            jobMessage = "Restored to previous state",
            pendingChatMessage = promptText,
            initialRequest = if (currentProject.analysis.appliedEditCheckpoints.isEmpty()) promptText else mutable.value.initialRequest
        )
    }
    fun export() { mutable.value.project?.let { project ->
        val manager = WorkManager.getInstance(getApplication())
        viewModelScope.launch { 
            val existing = withContext(Dispatchers.IO) { manager.getWorkInfosForUniqueWork("export:${project.id}").get() }
            if (existing != null && existing.any { !it.state.isFinished }) {
                return@launch
            }
            val estimated = (project.durationMs / 1_000.0 * project.export.bitrate / 8).toLong()
            val request = OneTimeWorkRequestBuilder<ExportWorker>().setInputData(Data.Builder().putString("projectId", project.id).putLong("estimatedBytes", estimated).build()).addTag("export:${project.id}").build()
            android.widget.Toast.makeText(getApplication(), "Export queued", android.widget.Toast.LENGTH_SHORT).show()
            manager.enqueueUniqueWork("export:${project.id}", ExistingWorkPolicy.KEEP, request)
            
            val info = awaitWork(manager, request.id)
            if (info.state == WorkInfo.State.CANCELLED) return@launch
            android.widget.Toast.makeText(getApplication(), if (info.state == WorkInfo.State.SUCCEEDED) "Export saved to Movies/Trimm" else "Export failed: ${info.outputData.getString("error") ?: info.state}", android.widget.Toast.LENGTH_LONG).show()
        }
    } }
    private fun updateProject(project: MobileProject, preview: Boolean = false) { mutable.value = mutable.value.copy(project = project, creatorPreferences = project.creatorPreferences); viewModelScope.launch { store.save(project); if (preview) renderPreview(project) } }
    private fun renderPreview(project: MobileProject, simulatePendingProposal: Boolean = false) {
        val manager = WorkManager.getInstance(getApplication())
        val request = OneTimeWorkRequestBuilder<PreviewWorker>().setInputData(Data.Builder()
            .putString("projectId", project.id)
            .putBoolean("simulatePendingProposal", simulatePendingProposal)
            .build()).addTag("preview:${project.id}").build()
        mutable.value = mutable.value.copy(
            jobMessage = if (simulatePendingProposal) "Simulating revised preview…" else "Rendering edited preview…",
            errorMessage = null
        )
        manager.enqueueUniqueWork("preview:${project.id}", ExistingWorkPolicy.REPLACE, request)
        viewModelScope.launch {
            val info = awaitWork(manager, request.id)
            mutable.value = if (info.state == WorkInfo.State.SUCCEEDED) mutable.value.copy(previewUri = info.outputData.getString("previewUri"), jobMessage = info.outputData.getString("warning") ?: "Edited preview ready")
            else mutable.value.copy(errorMessage = "Preview failed: ${info.outputData.getString("error") ?: info.state}", jobMessage = null)
        }
    }
    private fun toggle(op: EditInstruction): EditInstruction = when (op) {
        is RemoveRanges -> op.copy(enabled = !op.enabled)
        is SetColorGrade -> op.copy(enabled = !op.enabled)
        is NormaliseAudio -> op.copy(enabled = !op.enabled)
        is SetVolume -> op.copy(enabled = !op.enabled)
        is SetSpeed -> op.copy(enabled = !op.enabled)
        is AddCallout -> op.copy(enabled = !op.enabled)
        is AddSpotlight -> op.copy(enabled = !op.enabled)
        is AddMotionPreset -> op.copy(enabled = !op.enabled)
        is AddCaptions -> op.copy(enabled = !op.enabled)
        is AddTextOverlay -> op.copy(enabled = !op.enabled)
        is SetRotation -> op.copy(enabled = !op.enabled)
        is ClearRotation -> op.copy(enabled = !op.enabled)
        is SetCrop -> op.copy(enabled = !op.enabled)
        is ClearCrop -> op.copy(enabled = !op.enabled)
        else -> op
    }
    private fun processingMessage(stage: String?, percent: Int): String = when {
        stage?.startsWith("transcript-") == true -> "Preparing speech analysis…"
        stage?.startsWith("upload-") == true -> "Transcribing speech… $percent%"
        stage == "speech-hints" -> "Reviewing speech and take structure…"
        stage == "agent-run" || stage == "planner" -> "Preparing your edit proposal…"
        stage?.startsWith("agent-task:") == true -> "Working on step ${stage.removePrefix("agent-task:").trim()}…"
        else -> "Reading your clips and preparing a proposal…"
    }
    private fun editResultLines(before: List<EditInstruction>, after: List<EditInstruction>): List<String> {
               val beforeById = before.associateBy(EditInstruction::id)
               val afterById = after.associateBy(EditInstruction::id)
        val added = after.filter { it.id !in beforeById }.map { "+ ${it.editResultLabel()}" }
        val removed = before.filter { it.id !in afterById }.map { "− ${it.editResultLabel()}" }
        val changed = after.filter { operation ->
            beforeById[operation.id]?.let { it != operation } == true
        }.map { "~ ${it.editResultLabel()}" }
        return (added + removed + changed).ifEmpty { listOf("~ Updated the current edit") }.take(12)
    }
    private fun EditInstruction.editResultLabel(): String = when (this) {
        is RemoveRanges -> "Cut ${ranges.size} range${if (ranges.size == 1) "" else "s"}"
        is SetColorGrade -> "Updated color grade"
        is NormaliseAudio -> "Updated audio levels"
        is SetVolume -> "Updated volume"
        is SetSpeed -> "Updated speed"
        is AddCallout -> "Added callout"
        is AddSpotlight -> "Added spotlight"
        is SetAspectRatio -> "Updated aspect ratio"
        is AddMotionPreset -> "Updated motion"
        is AddCaptions -> "Updated ${cues.size} caption cue${if (cues.size == 1) "" else "s"}"
        is AddTextOverlay -> "Updated text overlay"
        is AddBgm -> "Added background music"
        is AddBgmEffect -> "Added background music effect"
        is AddSfx -> "Added sound effect"
        is SetRotation -> "Updated rotation"
        is ClearRotation -> "Cleared rotation"
        is SetCrop -> "Updated crop"
        is ClearCrop -> "Cleared crop"
        else -> "Updated edit"
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
