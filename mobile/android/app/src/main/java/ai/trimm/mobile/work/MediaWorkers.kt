package ai.trimm.mobile.work

import ai.trimm.mobile.media.DeviceCapability
import ai.trimm.mobile.media.ExportCoordinator
import ai.trimm.mobile.data.ProjectStore
import ai.trimm.mobile.domain.*
import ai.trimm.mobile.BuildConfig
import ai.trimm.mobile.auth.AuthTokenProvider
import ai.trimm.mobile.planner.*
import ai.trimm.mobile.evidence.*
import ai.trimm.mobile.planning.MediaIndexRequestPlanner
import ai.trimm.mobile.planning.RequestShapedMediaIndexBuilder
import ai.trimm.mobile.media.AndroidDerivativeWriter
import ai.trimm.mobile.media.DerivativeAuthorization
import ai.trimm.mobile.media.SelectiveDerivativePreparer
import ai.trimm.mobile.media.SelectiveDerivativeType
import android.net.Uri
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import android.content.ContentValues
import android.provider.MediaStore
import android.util.Log
import io.github.jan.supabase.storage.storage

abstract class ProgressWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    suspend fun progress(stage: String, percent: Int) = setProgress(Data.Builder().putString("stage", stage).putInt("percent", percent).build())
}

class AnalysisWorker(context: Context, params: WorkerParameters) : ProgressWorker(context, params) {
    override suspend fun doWork(): Result = runCatching {
        val projectId = requireNotNull(inputData.getString("projectId"))
        val projectJson = inputData.getString("projectJson")
        val initialPrompt = inputData.getString("prompt")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: "Create a polished, conservative first cut using the supplied evidence and preferences."
        val store = ProjectStore(applicationContext)
        
        val project = if (projectJson != null) {
            kotlinx.serialization.json.Json.decodeFromString(MobileProject.serializer(), projectJson)
        } else {
            store.load(projectId)
        }
        
        val words = mutableListOf<TranscriptWord>()
        val analysisWarnings = mutableListOf<String>()
        var transcriptionFailed = false
        val cloudConversationEnabled = BuildConfig.PLANNER_URL.isNotBlank()
        
        if (!cloudConversationEnabled) {
            return Result.failure(Data.Builder().putString("error", "Local analysis is no longer supported.").build())
        }

        val client = TranscriptionClient(BuildConfig.TRANSCRIPTION_URL, AuthTokenProvider::currentToken)
        var offset = 0L

        project.orderedSources.forEachIndexed { index, source ->
            if (isStopped) return Result.failure()
            progress("transcript-${index + 1}-of-${project.orderedSources.size}", 10 + index * 45 / project.orderedSources.size)
            
            val transcription = runCatching {
                val transcriptionRoot = java.io.File(applicationContext.cacheDir, "transcription").apply { mkdirs() }
                val audio = java.io.File(transcriptionRoot, "${source.fingerprint}.mp4")
                audio.delete()
                try {
                    AndroidDerivativeWriter(applicationContext).write(
                        SelectiveDerivativeType.AUDIO,
                        source,
                        TimeRange(0, source.durationMs),
                        null,
                        "audio/mp4",
                        audio
                    )
                    client.transcribe(audio) { uploaded, total ->
                        val sourceShare = ((uploaded * 45L / total) / project.orderedSources.size).toInt()
                        progress("upload-${index + 1}-of-${project.orderedSources.size}", 10 + index * 45 / project.orderedSources.size + sourceShare)
                    }
                } finally {
                    audio.delete()
                    transcriptionRoot.takeIf { it.listFiles().isNullOrEmpty() }?.delete()
                }
            }

            val localWords = transcription.getOrElse { error ->
                transcriptionFailed = true
                analysisWarnings += "Speech analyzer (${source.fingerprint.take(8)}): ${error.message ?: "failed"}"
                emptyList()
            }
            words += localWords.map { word ->
                word.copy(range = TimeRange(word.range.startMs + offset, word.range.endMs + offset))
            }
            offset += source.durationMs
        }

        progress("persist", 90)
        val newTitle = words.take(5).joinToString(" ") { it.text }.takeIf { it.isNotBlank() } ?: project.title
        var analyzed = project.copy(
            title = newTitle,
            analysis = Analysis(
                words = words,
                captions = emptyList(),
                speechHints = emptyList(),
                takeGroups = emptyList(),
                editorialProfile = null,
                storyGraph = null,
                mediaIndex = null,
                chatHistory = listOf(EditorialConversationTurn(
                    request = initialPrompt,
                    response = "I analyzed the clips and prepared an evidence-backed first cut."
                )),
                warnings = analysisWarnings,
                completedAtEpochMs = System.currentTimeMillis()
            ),
            operations = project.operations.filter(InstructionValidator::isRendered).toMutableList(),
            revision = project.revision + 1
        )
        store.save(analyzed)
        
        if (!transcriptionFailed) {
            progress("agent-run", 94)
            val deviceSessionId = "android-${project.id}"
            val coordinator = ChatCompletionCoordinator(
                AgentRunClient(BuildConfig.PLANNER_URL, AuthTokenProvider::currentToken)
            )
            runCatching<Unit> {
                val completedRun = coordinator.orchestrateStateless(
                    request = AgentRunRequest(
                        projectId = project.id,
                        deviceSessionId = deviceSessionId,
                        projectRevision = analyzed.revision,
                        intent = initialPrompt,
                        history = listOf(ChatMessage("user", initialPrompt)),
                        sources = project.orderedSources.mapIndexed { index, source ->
                            val display = source.displaySize()
                            AgentSourceInventory(
                                "${source.fingerprint}-$index", source.fingerprint, source.durationMs,
                                display.width, display.height, source.hasAudio,
                                visual = analyzed.analysis.visualProfiles[source.fingerprint]
                            )
                        },
                        transcript = sourceTranscript(analyzed.analysis.words, project.orderedSources),
                        uploadInitialVideoContext = false
                    ),
                    projectDurationMs = project.durationMs,
                    onProgress = { stage, percent ->
                        progress(stage, percent)
                    },
                    onPersist = { snapshot, plan ->
                        val proposal = AgentDecisionMapper.pendingProposal(snapshot)
                        analyzed = analyzed.copy(analysis = analyzed.analysis.copy(
                            agentRun = snapshot,
                            agentPlan = plan ?: analyzed.analysis.agentPlan,
                            pendingProposal = proposal ?: analyzed.analysis.pendingProposal
                        ))
                        store.save(analyzed)
                    }
                )
                AgentDecisionMapper.pendingProposal(completedRun)?.let { proposal ->
                    InstructionValidator.validate(analyzed, proposal.operations, proposal.expectedRevision)
                    analyzed = analyzed.copy(analysis = analyzed.analysis.copy(
                        agentRun = completedRun,
                        pendingProposal = proposal,
                        chatHistory = listOf(EditorialConversationTurn(
                            request = initialPrompt,
                            response = proposal.summary,
                            evidenceIds = proposal.evidenceIds
                        ))
                    ))
                    store.save(analyzed)
                }
            }.onFailure { error ->
                Log.w("AnalysisWorker", "Agent run could not continue", error)
                val detail = error.message?.takeIf { it.isNotBlank() } ?: "Retry when the connection is available."
                val failedRun = analyzed.analysis.agentRun?.copy(status = AgentRunStatus.FAILED)
                analyzed = analyzed.copy(analysis = analyzed.analysis.copy(
                    warnings = analyzed.analysis.warnings + "Agent run could not continue: $detail",
                    agentRun = failedRun,
                    chatHistory = analyzed.analysis.chatHistory.mapIndexed { index, turn -> 
                        if (index == analyzed.analysis.chatHistory.lastIndex) {
                            turn.copy(isError = true, isPending = false, response = detail)
                        } else turn
                    }
                ))
                store.save(analyzed)
                
                if (analyzed.analysis.pendingProposal != null) {
                    // We have partial results to show, so we treat the outer job as success
                    // to allow the UI to render the preview.
                    progress("complete", 100)
                    return Result.success()
                } else {
                    throw error
                }
            }
        }
        progress("complete", 100)
        Result.success()
    }.getOrElse { error ->
        Log.e("AnalysisWorker", "Analysis failed", error)
        Result.failure(Data.Builder().putString("error", error.message).build())
    }
}

class ExportWorker(context: Context, params: WorkerParameters) : ProgressWorker(context, params) {
    override suspend fun doWork(): Result = runCatching {
        val projectId = requireNotNull(inputData.getString("projectId"))
        val store = ProjectStore(applicationContext)
        val project = store.load(projectId)
        val capability = DeviceCapability.evaluate(
            applicationContext,
            project.export.width,
            project.export.height,
            inputData.getLong("estimatedBytes", 0)
        )
        val force4k = BuildConfig.FORCE_4K_EXPORT
        val maxLong = if (force4k) 3840 else maxOf(capability.recommendedWidth, capability.recommendedHeight)
        val maxShort = if (force4k) 2160 else minOf(capability.recommendedWidth, capability.recommendedHeight)
        val maxWidth = if (project.export.width >= project.export.height) maxLong else maxShort
        val maxHeight = if (project.export.height >= project.export.width) maxLong else maxShort
        val renderSize = DisplaySize(project.export.width, project.export.height).fitWithin(maxWidth, maxHeight)
        val renderProject = project.copy(export = project.export.copy(
            width = renderSize.width,
            height = renderSize.height,
            codec = if (force4k) "video/avc" else if (capability.allow4k) project.export.codec else "video/avc",
            bitrate = if (force4k) 45_000_000 else if (capability.allow4k) project.export.bitrate else 16_000_000
        ))
        if (force4k) {
            Log.w("ExportWorker", "Debug override forcing ${renderSize.width}x${renderSize.height} AVC export despite capability policy: ${capability.reasons}")
        }
        progress(if (force4k) "export-4k-forced" else if (capability.allow4k) "export-4k" else "export-${renderSize.shortSide}p", 1)
        val output = java.io.File(applicationContext.filesDir, "exports/${project.id}.mp4")
        ExportCoordinator.export(applicationContext, renderProject, output)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "trimm-${project.id.take(8)}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Trimm")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val published = requireNotNull(applicationContext.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values))
        try {
            applicationContext.contentResolver.openOutputStream(published)?.use { sink -> output.inputStream().use { it.copyTo(sink) } } ?: error("Could not open export destination")
            applicationContext.contentResolver.update(published, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        } catch (error: Throwable) { applicationContext.contentResolver.delete(published, null, null); throw error }
        progress("complete", 100)
        Result.success(Data.Builder().putString("outputUri", published.toString()).putBoolean("used4k", force4k || capability.allow4k).putStringArray("fallbackReasons", capability.reasons.toTypedArray()).build())
    }.getOrElse { error ->
        Log.e("ExportWorker", "Video export failed", error)
        Result.failure(Data.Builder().putString("error", error.detailedMessage("Video export failed")).build())
    }
}

class PreviewWorker(context: Context, params: WorkerParameters) : ProgressWorker(context, params) {
    override suspend fun doWork(): Result = runCatching {
        val projectId = requireNotNull(inputData.getString("projectId"))
        val persisted = ProjectStore(applicationContext).load(projectId)
        val stored = if (inputData.getBoolean("simulatePendingProposal", false)) {
            persisted.analysis.pendingProposal?.let { proposal ->
                persisted.copy(operations = InstructionValidator.validate(
                    persisted, proposal.operations, proposal.expectedRevision
                ))
            } ?: persisted
        } else persisted
        var warning: String? = null
        val prepared = stored
        progress("preview", 5)
        val output = java.io.File(applicationContext.cacheDir, "previews/${prepared.id}-${prepared.revision}.mp4")
        val maxLong = minOf(1280, maxOf(prepared.export.width, prepared.export.height))
        val maxShort = minOf(720, minOf(prepared.export.width, prepared.export.height))
        val maxWidth = if (prepared.export.width >= prepared.export.height) maxLong else maxShort
        val maxHeight = if (prepared.export.height >= prepared.export.width) maxLong else maxShort
        val previewSize = DisplaySize(prepared.export.width, prepared.export.height).fitWithin(maxWidth, maxHeight)
        ExportCoordinator.export(applicationContext, prepared.copy(export = prepared.export.copy(width = previewSize.width, height = previewSize.height, codec = "video/avc", bitrate = 5_000_000)), output)
        progress("complete", 100)
        Result.success(Data.Builder().putString("previewUri", Uri.fromFile(output).toString()).putString("warning", warning).build())
    }.getOrElse { error ->
        Log.e("PreviewWorker", "Preview rendering failed", error)
        Result.failure(Data.Builder().putString("error", error.detailedMessage("Preview render failed")).build())
    }
}

private fun Throwable.detailedMessage(fallback: String): String =
    generateSequence(this) { it.cause }
        .mapNotNull { it.message?.takeIf(String::isNotBlank) }
        .distinct()
        .joinToString(" → ")
        .ifBlank { fallback }


