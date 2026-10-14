package ai.trimm.mobile

import ai.trimm.mobile.domain.*
import ai.trimm.mobile.data.ProjectStore
import ai.trimm.mobile.media.DeviceCapability
import ai.trimm.mobile.media.ExportCoordinator
import ai.trimm.mobile.media.AndroidDerivativeWriter
import ai.trimm.mobile.media.SelectiveDerivativeType
import ai.trimm.mobile.planner.*
import ai.trimm.mobile.billing.BillingManager
import ai.trimm.mobile.auth.AuthTokenProvider
import ai.trimm.mobile.network.ChatRepository
import ai.trimm.mobile.network.ChatRepositoryException
import ai.trimm.mobile.network.ChatSummary
import ai.trimm.mobile.network.supabase
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(UnstableApi::class)
class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val defaultJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val mutable = MutableStateFlow(ChatState())
    val state = mutable.asStateFlow()
    private val store = ProjectStore(application)

    private var activeTransformer: Transformer? = null
    private var exportProgressJob: Job? = null
    private var fetchChatsJob: Job? = null

    init {
        viewModelScope.launch {
            val prefs = store.loadCreatorPreferences()
            mutable.update { it.copy(creatorPreferences = prefs) }
        }
        refreshChats()
        viewModelScope.launch {
            supabase.auth.sessionStatus.collect { status ->
                when (status) {
                    is SessionStatus.Authenticated -> {
                        val userId = supabase.auth.currentUserOrNull()?.id
                        BillingManager.updateUser(userId)
                        refreshChats()
                    }
                    is SessionStatus.NotAuthenticated -> {
                        BillingManager.updateUser(null)
                        refreshChats()
                    }
                    else -> {}
                }
            }
        }
    }

    fun refreshChats() {
        fetchChatsJob?.cancel()
        fetchChatsJob = viewModelScope.launch {
            mutable.update { it.copy(isLoadingPastChats = true) }
            val chats = runCatching {
                ChatRepository.shared.getProjects(getApplication())
            }.getOrElse {
                emptyList()
            }
            mutable.update { it.copy(pastChats = chats, isLoadingPastChats = false) }
        }
    }

    fun deleteChat(id: String) {
        viewModelScope.launch {
            runCatching { ChatRepository.shared.deleteProject(getApplication(), id) }
            runCatching { store.delete(id) }
            if (mutable.value.project?.id == id) {
                newChat()
            }
            refreshChats()
        }
    }

    fun newChat() {
        mutable.update {
            it.copy(
                project = null,
                sources = emptyList(),
                compositionSchema = null,
                capabilityMessage = null,
                previewUri = null,
                jobMessage = null,
                errorMessage = null,
                initialRequest = null,
                pendingChatMessage = null,
                pendingClarification = null,
                messages = emptyList(),
                isProcessing = false
            )
        }
    }

    fun loadProject(id: String) = viewModelScope.launch {
        mutable.update { it.copy(isProcessing = true, errorMessage = null) }
        try {
            val project = runCatching {
                ChatRepository.shared.getProject(getApplication(), id)
            }.getOrElse {
                store.load(id)
            }

            val loadedMessages = mutableListOf<UiChatMessage>()
            loadedMessages.add(
                UiChatMessage(
                    text = "Transcription complete. What would you like to edit?",
                    isUser = false
                )
            )
            for (turn in project.analysis.chatHistory) {
                loadedMessages.add(UiChatMessage(text = turn.request, isUser = true, turnId = turn.id))
                loadedMessages.add(UiChatMessage(text = turn.response, isUser = false, turnId = turn.id))
            }

            var loadedSchema: CompositionSchema? = null
            val schemaFile = File(getApplication<Application>().filesDir, "${id}_composition.json")
            if (schemaFile.exists()) {
                loadedSchema = runCatching {
                    defaultJson.decodeFromString<CompositionSchema>(schemaFile.readText())
                }.getOrNull()
            }

            mutable.update {
                it.copy(
                    project = project,
                    sources = project.orderedSources,
                    messages = loadedMessages,
                    compositionSchema = loadedSchema,
                    errorMessage = null,
                    jobMessage = null,
                    isProcessing = false
                )
            }
        } catch (e: Exception) {
            mutable.update {
                it.copy(isProcessing = false, errorMessage = "Failed to load chat: ${e.message}")
            }
        }
    }

    fun import(uris: List<Uri>) = viewModelScope.launch {
        val context = getApplication<Application>()
        val sources = uris.distinct().map { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            readSource(uri)
        }
        require(sources.isNotEmpty()) { "Choose at least one video." }
        mutable.update {
            it.copy(
                sources = sources,
                project = null
            )
        }
        initializeProject()
    }

    fun initializeProject() {
        val firstSource = mutable.value.sources.firstOrNull() ?: run {
            mutable.update { it.copy(errorMessage = "Please import a video first.") }
            return
        }

        val projectId = UUID.randomUUID().toString()
        val project = MobileProject(
            schemaVersion = 1,
            id = projectId,
            title = "New Edit",
            source = firstSource,
            sources = mutable.value.sources,
            preferences = mutable.value.preferences,
            analysis = Analysis(),
            operations = emptyList(),
            undo = emptyList(),
            export = ExportSettingsResolver.resolve(firstSource),
            revision = 1L
        )

        // Pre-flight Credit Check: Check before any processing starts
        val requiredCredits = BillingManager.costs.value.transcriptionCost
        if (BillingManager.balance.value < requiredCredits) {
            BillingManager.setStorePresented(true)
            val noCreditMessages = listOf(
                UiChatMessage(
                    text = "You need at least $requiredCredits credits to transcribe audio and start a new project. Please top up your credits to continue.",
                    isUser = false
                )
            )
            mutable.update {
                it.copy(
                    project = project,
                    messages = noCreditMessages,
                    isProcessing = false,
                    jobMessage = null,
                    errorMessage = null
                )
            }
            return
        }

        val initialMessages = listOf(
            UiChatMessage(
                text = "Extracting audio and transcribing video. This may take a few moments...",
                isUser = false
            )
        )

        mutable.update {
            it.copy(
                project = project,
                messages = initialMessages,
                isProcessing = true,
                jobMessage = "Extracting audio…",
                errorMessage = null
            )
        }

        viewModelScope.launch {
            val context = getApplication<Application>()
            var transcribedWords = emptyList<TranscriptWord>()
            var transcriptionError: String? = null

            try {
                if (BuildConfig.TRANSCRIPTION_URL.isNotBlank()) {
                    val client = TranscriptionClient(BuildConfig.TRANSCRIPTION_URL, AuthTokenProvider::currentToken)
                    val transcriptionRoot = File(context.cacheDir, "transcription").apply { mkdirs() }
                    val audioFile = File(transcriptionRoot, "${firstSource.fingerprint}.mp4")
                    try {
                        if (firstSource.hasAudio) {
                            mutable.update { it.copy(jobMessage = "Extracting audio…") }
                            runCatching {
                                AndroidDerivativeWriter(context).write(
                                    SelectiveDerivativeType.AUDIO,
                                    firstSource,
                                    TimeRange(0, firstSource.durationMs),
                                    null,
                                    "audio/mp4",
                                    audioFile
                                )
                            }
                        }
                        mutable.update { it.copy(jobMessage = "Transcribing audio…") }
                        if (audioFile.exists() && audioFile.length() > 0) {
                            transcribedWords = client.transcribe(audioFile)
                        } else {
                            transcribedWords = client.transcribe(context, Uri.parse(firstSource.uri))
                        }
                    } finally {
                        audioFile.delete()
                    }
                }
            } catch (e: Exception) {
                Log.w("ChatViewModel", "Transcription failed: ${e.message}", e)
                val is402 = e.message?.contains("402") == true || e.message?.contains("insufficient_credits") == true
                if (is402) {
                    BillingManager.setStorePresented(true)
                    BillingManager.fetchCredits()
                    transcriptionError = "You need more credits to complete transcription. Please top up your credits to continue."
                } else {
                    transcriptionError = "Transcription could not be completed (${e.localizedMessage ?: "Network error"}). You can still edit using chat."
                }
            }

            val transcripts = transcribedWords.map {
                TranscriptWordNode(word = it.text, startMs = it.range.startMs, endMs = it.range.endMs)
            }

            val silences = mutableListOf<TimeRangeNode>()
            var lastEnd = 0L
            for (word in transcribedWords) {
                if (word.range.startMs - lastEnd > 500L) {
                    silences.add(TimeRangeNode(startMs = lastEnd, endMs = word.range.startMs))
                }
                lastEnd = word.range.endMs
            }
            val durationMs = firstSource.durationMs
            if (durationMs - lastEnd > 500L) {
                silences.add(TimeRangeNode(startMs = lastEnd, endMs = durationMs))
            }

            val metadata = CompositionMetadata(transcripts = transcripts, silences = silences)
            val timeline = mutableListOf<TimelineNode>()
            timeline.add(
                TimelineNode.Video(
                    id = UUID.randomUUID().toString(),
                    src = firstSource.uri,
                    startMs = 0L,
                    endMs = durationMs,
                    sourceStartMs = 0L
                )
            )

            var currentCaptionText = ""
            var currentCaptionStart = 0L
            var currentCaptionEnd = 0L
            var wordCount = 0

            for (i in transcribedWords.indices) {
                val word = transcribedWords[i]
                if (wordCount == 0) {
                    currentCaptionStart = word.range.startMs
                }
                currentCaptionText += (if (wordCount == 0) "" else " ") + word.text
                currentCaptionEnd = word.range.endMs
                wordCount++

                val isPause = (i < transcribedWords.size - 1) && (transcribedWords[i + 1].range.startMs - currentCaptionEnd > 400L)
                if (wordCount >= 4 || isPause || i == transcribedWords.size - 1) {
                    val textNode = TimelineNode.Text(
                        id = UUID.randomUUID().toString(),
                        text = currentCaptionText,
                        startMs = currentCaptionStart,
                        endMs = currentCaptionEnd,
                        style = TextNodeStyle(color = "#FFFFFF", fontSize = 100f, posY = -0.35f),
                        visible = false
                    )
                    timeline.add(textNode)
                    currentCaptionText = ""
                    wordCount = 0
                }
            }

            val isVertical = (firstSource.rotation == 90 || firstSource.rotation == 270) || (firstSource.height > firstSource.width)
            val schema = CompositionSchema(
                settings = CompositionSettings(aspectRatio = if (isVertical) "9:16" else "16:9"),
                metadata = metadata,
                timeline = timeline
            )

            // Persist schema to local storage
            runCatching {
                val schemaJson = defaultJson.encodeToString(CompositionSchema.serializer(), schema)
                File(context.filesDir, "${project.id}_composition.json").writeText(schemaJson)
                File(context.filesDir, "${project.id}_initial_composition.json").writeText(schemaJson)
            }

            val newTitle = if (transcribedWords.isEmpty()) {
                "Untitled Chat"
            } else {
                val prefix = transcribedWords.take(4).joinToString(" ") { it.text }
                prefix.ifBlank { "Untitled Chat" }
            }

            val updatedAnalysis = Analysis(
                words = transcribedWords,
                completedAtEpochMs = System.currentTimeMillis()
            )

            val initialSnapshot = ProjectSnapshot(
                operations = project.operations,
                sources = project.sources,
                analysis = updatedAnalysis,
                schema = schema
            )

            val projectWithSnapshot = project.copy(
                title = newTitle,
                analysis = updatedAnalysis,
                undo = listOf(initialSnapshot)
            )

            runCatching {
                ChatRepository.shared.syncProject(context, projectWithSnapshot, schema)
                store.save(projectWithSnapshot)
            }

            mutable.update { state ->
                val updatedMsgs = state.messages.filter {
                    !it.text.startsWith("Extracting audio")
                } + if (transcriptionError != null) {
                    listOf(UiChatMessage(text = transcriptionError, isUser = false))
                } else {
                    listOf(UiChatMessage(text = "Transcription complete. What would you like to edit?", isUser = false))
                }
                state.copy(
                    project = projectWithSnapshot,
                    compositionSchema = schema,
                    messages = updatedMsgs,
                    isProcessing = false,
                    jobMessage = null
                )
            }

            refreshChats()
        }
    }

    fun reviseStory(request: String) {
        val text = request.trim()
        if (text.isBlank() || mutable.value.isProcessing) return
        val project = mutable.value.project ?: return

        // Pre-flight Credit Check: Check before processing edit turn
        val requiredCredits = BillingManager.costs.value.chatCost
        if (BillingManager.balance.value < requiredCredits) {
            BillingManager.setStorePresented(true)
            val turnId = UUID.randomUUID().toString()
            val userMsg = UiChatMessage(text = text, isUser = true, turnId = turnId)
            val noCreditMsg = UiChatMessage(
                text = "You need at least $requiredCredits credits to send an edit request. Please top up your credits to continue.",
                isUser = false,
                turnId = turnId
            )
            mutable.update {
                it.copy(
                    messages = it.messages + listOf(userMsg, noCreditMsg),
                    isProcessing = false,
                    jobMessage = null
                )
            }
            return
        }

        val turnId = UUID.randomUUID().toString()
        val userMsg = UiChatMessage(text = text, isUser = true, turnId = turnId)
        val thinkingMsg = UiChatMessage(text = "Thinking…", isUser = false, turnId = turnId)

        mutable.update {
            it.copy(
                isProcessing = true,
                jobMessage = "Thinking…",
                messages = it.messages + listOf(userMsg, thinkingMsg)
            )
        }

        viewModelScope.launch {
            val isAuth = ChatRepository.shared.isAuthenticated()
            if (!isAuth) {
                mutable.update { state ->
                    val filtered = state.messages.filter { it.text != "Thinking…" && it.text != "Editing…" }
                    state.copy(
                        isProcessing = false,
                        jobMessage = null,
                        messages = filtered + UiChatMessage(text = "Please sign in to continue chat.", isUser = false)
                    )
                }
                return@launch
            }

            val agentSources = mutable.value.sources.map {
                AgentSourceInventory(
                    id = it.uri,
                    fingerprint = it.fingerprint,
                    durationMs = it.durationMs,
                    width = it.width,
                    height = it.height,
                    hasAudio = it.hasAudio
                )
            }

            val transcriptWords = project.analysis.words.map {
                AgentTranscriptWord(
                    sourceId = project.source.uri,
                    text = it.text,
                    startMs = it.range.startMs,
                    endMs = it.range.endMs,
                    confidence = it.confidence,
                    speakerId = it.speakerId
                )
            }

            val currentHistory = mutable.value.messages
                .filter { it.text != "Thinking…" && it.text != "Editing…" && it.text != "Please sign in to continue chat." }
                .map { ChatMessage(role = if (it.isUser) "user" else "assistant", content = it.text) }

            val agentRequest = AgentRunRequest(
                projectId = project.id,
                deviceSessionId = UUID.randomUUID().toString(),
                projectRevision = project.revision,
                intent = text,
                sources = agentSources,
                transcript = transcriptWords,
                history = currentHistory,
                existingOperations = project.operations.map { operation ->
                    defaultJson.encodeToJsonElement(EditInstruction.serializer(), operation).jsonObject
                },
                schema = mutable.value.compositionSchema,
                platform = "android"
            )

            try {
                val response = ChatRepository.shared.executeAgentTask(agentRequest) { status ->
                    if (status == "editing") {
                        mutable.update { state ->
                            val updatedMessages = state.messages.map {
                                if (it.text == "Thinking…" && it.turnId == turnId) {
                                    it.copy(text = "Editing…")
                                } else it
                            }
                            state.copy(jobMessage = "Editing…", messages = updatedMessages)
                        }
                    }
                }

                if (response.type == "edit_program" || response.type == "schema_patch") {
                    val replyText = response.summary ?: "Revised proposal ready to review"
                    val previousSchema = mutable.value.compositionSchema

                    if (response.schema != null) {
                        mutable.update { it.copy(compositionSchema = response.schema) }
                        runCatching {
                            val jsonStr = defaultJson.encodeToString(CompositionSchema.serializer(), response.schema)
                            File(getApplication<Application>().filesDir, "${project.id}_composition.json").writeText(jsonStr)
                        }
                    }

                    val updatedProject = project.applyEditInstructions(
                        next = response.operations ?: emptyList(),
                        removedOperations = response.removedOperations ?: emptyList(),
                        activeTurnId = turnId,
                        request = text,
                        response = replyText,
                        currentSchema = previousSchema
                    )

                    runCatching {
                        ChatRepository.shared.syncProject(getApplication(), updatedProject, response.schema)
                        store.save(updatedProject)
                    }

                    mutable.update { state ->
                        val filtered = state.messages.filter { it.text != "Thinking…" && it.text != "Editing…" }
                        state.copy(
                            project = updatedProject,
                            jobMessage = replyText,
                            messages = filtered + UiChatMessage(text = replyText, isUser = false, turnId = turnId),
                            isProcessing = false
                        )
                    }
                    refreshChats()
                } else if (response.type == "clarification") {
                    val question = response.questions?.firstOrNull()
                    mutable.update { state ->
                        val filtered = state.messages.filter { it.text != "Thinking…" && it.text != "Editing…" }
                        state.copy(
                            pendingClarification = question,
                            messages = if (question != null) filtered + UiChatMessage(text = question.prompt, isUser = false) else filtered,
                            isProcessing = false
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "executeAgentTask error: ${e.message}", e)
                mutable.update { state ->
                    val filtered = state.messages.filter { it.text != "Thinking…" && it.text != "Editing…" }
                    val errorMsg = when (e) {
                        is ChatRepositoryException.Unauthorized -> "Please sign in to continue chat."
                        is ChatRepositoryException.InsufficientCredits -> "You need more credits to continue."
                        else -> "Error: ${e.localizedMessage ?: "Unknown error"}"
                    }
                    state.copy(
                        isProcessing = false,
                        jobMessage = null,
                        messages = filtered + UiChatMessage(text = errorMsg, isUser = false)
                    )
                }
            }
        }
    }

    fun answerClarification(label: String) {
        mutable.update { it.copy(pendingClarification = null) }
        reviseStory(label)
    }

    fun undoTarget(message: UiChatMessage): UndoTarget? {
        val project = mutable.value.project ?: return null
        val history = project.analysis.chatHistory

        if (message.turnId != null) {
            val index = history.indexOfFirst { it.id == message.turnId }
            if (index != -1) {
                return UndoTarget(id = history[index].id, userPrompt = history[index].request, turnIndex = index)
            }
        }
        if (message.isUser) {
            val index = history.indexOfLast { it.request == message.text }
            if (index != -1) {
                return UndoTarget(id = history[index].id, userPrompt = history[index].request, turnIndex = index)
            }
            return UndoTarget(id = message.id, userPrompt = message.text, turnIndex = -1)
        }
        return null
    }

    fun undoTurn(target: UndoTarget) {
        val project = mutable.value.project ?: return

        if (target.turnIndex == -1) {
            mutable.update { state ->
                state.copy(messages = state.messages.filterNot { it.text == target.userPrompt && it.isUser })
            }
            return
        }

        val history = project.analysis.chatHistory
        val turnIndex = target.turnIndex
        if (turnIndex !in history.indices) return

        mutable.update { it.copy(isProcessing = true, jobMessage = "Undoing edit...") }

        val remainingTurns = history.take(turnIndex)
        var targetSnapshot: ProjectSnapshot? = null
        var targetSnapshotIndex: Int? = null

        for (idx in project.undo.indices.reversed()) {
            val snap = project.undo[idx]
            val snapCount = snap.analysis?.chatHistory?.size ?: 0
            val snapHasTurn = snap.analysis?.chatHistory?.any { it.id == target.id } == true
            if (snapCount <= turnIndex && !snapHasTurn) {
                targetSnapshot = snap
                targetSnapshotIndex = idx
                break
            }
        }

        val newUndo: List<ProjectSnapshot>
        val newRedo: List<ProjectSnapshot>
        val restoredOperations: List<EditInstruction>
        val restoredSources: List<SourceMedia>
        var restoredSchema: CompositionSchema? = null
        val restoredAnalysis: Analysis

        if (targetSnapshot != null && targetSnapshotIndex != null) {
            newUndo = project.undo.take(targetSnapshotIndex)
            newRedo = (project.redo + project.snapshot(mutable.value.compositionSchema)).takeLast(30)
            restoredOperations = targetSnapshot.operations
            restoredSources = targetSnapshot.sources.ifEmpty { project.orderedSources }
            restoredSchema = targetSnapshot.schema

            val baseAnalysis = targetSnapshot.analysis ?: project.analysis
            restoredAnalysis = baseAnalysis.copy(
                activeTurnId = remainingTurns.lastOrNull()?.id,
                pendingProposal = null,
                chatHistory = remainingTurns
            )
        } else {
            newUndo = emptyList()
            newRedo = (project.redo + project.snapshot(mutable.value.compositionSchema)).takeLast(30)
            restoredOperations = emptyList()
            restoredSources = project.orderedSources

            val initialFile = File(getApplication<Application>().filesDir, "${project.id}_initial_composition.json")
            if (initialFile.exists()) {
                restoredSchema = runCatching { defaultJson.decodeFromString<CompositionSchema>(initialFile.readText()) }.getOrNull()
            }

            restoredAnalysis = project.analysis.copy(
                activeTurnId = null,
                editorialPlan = null,
                pendingProposal = null,
                chatHistory = emptyList()
            )
        }

        val restoredProject = project.copy(
            sources = restoredSources,
            analysis = restoredAnalysis,
            operations = restoredOperations,
            undo = newUndo,
            redo = newRedo,
            revision = project.revision + 1
        )

        if (restoredSchema != null) {
            runCatching {
                val jsonStr = defaultJson.encodeToString(CompositionSchema.serializer(), restoredSchema)
                File(getApplication<Application>().filesDir, "${project.id}_composition.json").writeText(jsonStr)
            }
        }

        val updatedMessages = mutableListOf<UiChatMessage>()
        updatedMessages.add(UiChatMessage(text = "Transcription complete. What would you like to edit?", isUser = false))
        for (turn in remainingTurns) {
            updatedMessages.add(UiChatMessage(text = turn.request, isUser = true, turnId = turn.id))
            updatedMessages.add(UiChatMessage(text = turn.response, isUser = false, turnId = turn.id))
        }

        mutable.update { state ->
            state.copy(
                project = restoredProject,
                compositionSchema = restoredSchema ?: state.compositionSchema,
                messages = updatedMessages,
                jobMessage = null,
                isProcessing = false
            )
        }

        viewModelScope.launch {
            runCatching {
                ChatRepository.shared.syncProject(getApplication(), restoredProject, restoredSchema)
                store.save(restoredProject)
            }
            refreshChats()
        }
    }

    fun undo() {
        val lastUserMsg = mutable.value.messages.lastOrNull { it.isUser }
        if (lastUserMsg != null) {
            val target = undoTarget(lastUserMsg)
            if (target != null) {
                undoTurn(target)
            }
        }
    }

    fun undoCheckpoint(checkpointId: String) {
        val project = mutable.value.project ?: return
        val currentProject = project.restoreCheckpoint()
        mutable.update { it.copy(project = currentProject) }
        viewModelScope.launch {
            store.save(currentProject)
        }
    }

    fun undoTurn(turnId: String) {
        val target = mutable.value.project?.analysis?.chatHistory?.firstOrNull { it.id == turnId }?.let {
            val index = mutable.value.project?.analysis?.chatHistory?.indexOf(it) ?: -1
            UndoTarget(id = it.id, userPrompt = it.request, turnIndex = index)
        }
        if (target != null) {
            undoTurn(target)
        }
    }

    fun cancelExport() {
        activeTransformer?.cancel()
        activeTransformer = null
        exportProgressJob?.cancel()
        exportProgressJob = null
        mutable.update { it.copy(isExporting = false, jobMessage = "Export cancelled.") }
    }

    fun export() {
        val project = mutable.value.project ?: return
        if (mutable.value.isExporting) return

        mutable.update { it.copy(isExporting = true, exportProgress = 0f) }

        viewModelScope.launch {
            val context = getApplication<Application>()
            val tempFile = File(context.cacheDir, "exported_${UUID.randomUUID()}.mp4")
            tempFile.delete()

            try {
                val composition = ExportCoordinator.composition(context, project, schema = mutable.value.compositionSchema)
                val progressHolder = ProgressHolder()

                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(
                        if (project.export.codec == "video/hevc") MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264
                    )
                    .build()
                activeTransformer = transformer

                exportProgressJob = launch {
                    while (activeTransformer != null) {
                        val progressState = transformer.getProgress(progressHolder)
                        if (progressState == Transformer.PROGRESS_STATE_AVAILABLE) {
                            val frac = progressHolder.progress / 100f
                            mutable.update { it.copy(exportProgress = frac) }
                        }
                        delay(100)
                    }
                }

                suspendCancellableCoroutine<File> { cont ->
                    transformer.addListener(object : Transformer.Listener {
                        override fun onCompleted(comp: Composition, result: ExportResult) {
                            cont.resume(tempFile)
                        }

                        override fun onError(comp: Composition, result: ExportResult, exception: ExportException) {
                            cont.resumeWithException(exception)
                        }
                    })
                    transformer.start(composition, tempFile.absolutePath)
                }

                exportProgressJob?.cancel()
                activeTransformer = null

                saveVideoToMediaStore(context, tempFile)
                mutable.update {
                    it.copy(
                        isExporting = false,
                        exportProgress = 1f,
                        jobMessage = "Export saved to Camera Roll / Movies/Trimm"
                    )
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Export failed: ${e.message}", e)
                exportProgressJob?.cancel()
                activeTransformer = null
                tempFile.delete()
                mutable.update {
                    it.copy(
                        isExporting = false,
                        errorMessage = "Export failed: ${e.localizedMessage ?: "Unknown error"}"
                    )
                }
            }
        }
    }

    private fun saveVideoToMediaStore(context: Context, videoFile: File): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "trimm_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Trimm")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val uri = resolver.insert(collection, values) ?: return null
        resolver.openOutputStream(uri)?.use { out ->
            videoFile.inputStream().use { input -> input.copyTo(out) }
        }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    fun clearErrorMessage() {
        mutable.update { it.copy(errorMessage = null) }
    }

    fun cancelAnalysis() {
        mutable.update { it.copy(isProcessing = false) }
    }

    fun applyProposal() {
        mutable.value.project?.let { project ->
            val proposal = project.analysis.pendingProposal ?: return
            val applied = project.applyEditInstructions(proposal.operations)
            mutable.update { it.copy(project = applied) }
            viewModelScope.launch {
                ChatRepository.shared.syncProject(getApplication(), applied)
                store.save(applied)
            }
        }
    }

    fun rejectProposal() {
        mutable.value.project?.let { project ->
            val cleared = project.copy(analysis = project.analysis.copy(pendingProposal = null))
            mutable.update { it.copy(project = cleared) }
        }
    }

    fun retryTurn(turnId: String) {
        val turn = mutable.value.project?.analysis?.chatHistory?.firstOrNull { it.id == turnId } ?: return
        reviseStory(turn.request)
    }

    fun switchBranch(turnId: String) {
        val project = mutable.value.project ?: return
        val updated = project.copy(analysis = project.analysis.copy(activeTurnId = turnId))
        mutable.update { it.copy(project = updated) }
    }

    fun recoverAgent(action: AgentRecoveryAction) {
        // Fallback agent recovery
    }

    fun generateDraft(prompt: String, words: List<TranscriptWord> = emptyList()) {
        reviseStory(prompt)
    }

    fun moveClip(index: Int, offset: Int) {
        val nextIndex = index + offset
        val current = mutable.value
        val clips = current.project?.orderedSources ?: current.sources
        if (index !in clips.indices || nextIndex !in clips.indices) return
        val reordered = clips.toMutableList().apply { add(nextIndex, removeAt(index)) }
        current.project?.let {
            val updated = it.replaceSources(reordered)
            mutable.update { state -> state.copy(project = updated, sources = reordered) }
            viewModelScope.launch {
                ChatRepository.shared.syncProject(getApplication(), updated)
                store.save(updated)
            }
        } ?: run {
            mutable.update { it.copy(sources = reordered) }
        }
    }

    fun relinkVideo(uri: Uri) = viewModelScope.launch {
        try {
            val context = getApplication<Application>()
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
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
            ChatRepository.shared.syncProject(context, updatedProject)
            store.save(updatedProject)
            mutable.update {
                it.copy(project = updatedProject, sources = updatedProject.sources)
            }
        } catch (e: Exception) {
            mutable.update { it.copy(errorMessage = "Relinking failed: ${e.message}") }
        }
    }

    private suspend fun readSource(uri: Uri): SourceMedia = withContext(Dispatchers.IO) {
        val context = getApplication<Application>()
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val retriever = MediaMetadataRetriever().apply { setDataSource(context, uri) }
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != null
        retriever.release()
        require(duration > 0 && width > 0 && height > 0) { "The selected file is not a supported video." }

        val digest = MessageDigest.getInstance("SHA-256")
        requireNotNull(context.contentResolver.openInputStream(uri)) { "The selected file cannot be opened." }.use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        val fingerprint = digest.digest().joinToString("") { "%02x".format(it) }
        SourceMedia(uri.toString(), fingerprint, duration, width, height, rotation, hasAudio)
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
        mutable.update { it.copy(creatorPreferences = profile, project = project) }
        viewModelScope.launch {
            store.saveCreatorPreferences(profile)
            if (project != null) {
                ChatRepository.shared.syncProject(getApplication(), project)
                store.save(project)
            }
        }
    }
}
