package ai.ctrlb.mobile.work

import ai.ctrlb.mobile.media.DeviceCapability
import ai.ctrlb.mobile.media.ExportCoordinator
import ai.ctrlb.mobile.data.ProjectStore
import ai.ctrlb.mobile.domain.*
import ai.ctrlb.mobile.media.AudioAssetRepository
import ai.ctrlb.mobile.BuildConfig
import ai.ctrlb.mobile.auth.AuthTokenProvider
import ai.ctrlb.mobile.planner.*
import ai.ctrlb.mobile.evidence.*
import android.net.Uri
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import android.content.ContentValues
import android.provider.MediaStore
import android.util.Log

abstract class ProgressWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    suspend fun progress(stage: String, percent: Int) = setProgress(Data.Builder().putString("stage", stage).putInt("percent", percent).build())
}

class AnalysisWorker(context: Context, params: WorkerParameters) : ProgressWorker(context, params) {
    override suspend fun doWork(): Result = runCatching {
        val projectId = requireNotNull(inputData.getString("projectId"))
        val store = ProjectStore(applicationContext)
        val project = store.load(projectId)
        val transcriber = BuildConfig.TRANSCRIPTION_URL.takeIf(String::isNotBlank)
            ?.let { TranscriptionClient(it, AuthTokenProvider::currentToken) }
        val words = mutableListOf<TranscriptWord>()
        val evidenceReferences = mutableListOf<EvidenceGraphReference>()
        val analysisWarnings = mutableListOf<String>()
        val evidenceCache = EvidenceCache(java.io.File(applicationContext.filesDir, "evidence"))
        var offset = 0L
        project.orderedSources.forEachIndexed { index, source ->
            if (isStopped) return Result.failure()
            progress("transcript-${index + 1}-of-${project.orderedSources.size}", 10 + index * 45 / project.orderedSources.size)
            if (source.hasAudio) {
                val transcription: kotlin.Result<List<TranscriptWord>> = transcriber?.let { client -> runCatching { client.transcribe(applicationContext, Uri.parse(source.uri)) { uploaded, total ->
                    val sourceShare = ((uploaded * 45L / total) / project.orderedSources.size).toInt()
                    progress("upload-${index + 1}-of-${project.orderedSources.size}", 10 + index * 45 / project.orderedSources.size + sourceShare)
                } } } ?: runCatching { throw IllegalStateException("Transcription service is not configured.") }
                val localWords = transcription.getOrElse { error ->
                    analysisWarnings += "Speech analyzer (${source.fingerprint.take(8)}): ${error.message ?: "failed"}"
                    emptyList()
                }
                words += localWords.map { word ->
                    word.copy(range = TimeRange(word.range.startMs + offset, word.range.endMs + offset))
                }
                val speechAnalyzer: EvidenceAnalyzer = transcription.fold(
                    onSuccess = { SpeechEvidenceAnalyzer(it) },
                    onFailure = { failure -> object : EvidenceAnalyzer {
                        override val identity = AnalyzerIdentity("ctrlB speech structure", "1.0.0", ExecutionLocation.EXTERNAL_SERVICE)
                        override suspend fun analyze(context: AnalyzerContext): List<EvidenceObservation> = throw failure
                    } }
                )
                val graph = EvidencePipeline(evidenceCache, listOf(LocalVisualAnalyzer(applicationContext), LocalSoundAnalyzer(applicationContext), speechAnalyzer) + unsupportedAnalyzers()).analyze(source)
                evidenceReferences += graph.reference()
            } else {
                val graph = EvidencePipeline(evidenceCache, listOf(LocalVisualAnalyzer(applicationContext), object : EvidenceAnalyzer {
                    override val identity = AnalyzerIdentity("ctrlB speech structure", "1.0.0", ExecutionLocation.EXTERNAL_SERVICE)
                    override suspend fun analyze(context: AnalyzerContext): List<EvidenceObservation> = throw UnsupportedOperationException("Source has no audio track.")
                }) + unsupportedAnalyzers()).analyze(source)
                evidenceReferences += graph.reference()
            }
            offset += source.durationMs
        }
        progress("fumbles", 65)
        val candidates = FumbleDetector.detect(words, project.durationMs, project.preferences.pace)
        val automatic = FumbleDetector.automatic(candidates)
        val retained = project.operations.filter {
            it !is RemoveFumbles && it !is AddCaptions && OperationValidator.isPlannerAvailable(it)
        }.toMutableList()
        if (automatic.isNotEmpty()) retained += RemoveFumbles(ranges = automatic.map { it.range }, confidence = automatic.minOf { it.confidence }, reasons = automatic.map { it.reason }.distinct())
        val captions = CaptionSegmenter.segment(words)
        if (project.preferences.captions && captions.isNotEmpty()) retained += AddCaptions(cues = captions)
        progress("persist", 90)
        var analyzed = project.copy(
            analysis = Analysis(words, captions, candidates.filter { it.confidence < .93f }, warnings = analysisWarnings, completedAtEpochMs = System.currentTimeMillis()),
            evidenceGraphs = evidenceReferences,
            operations = retained,
            revision = project.revision + 1
        )
        store.save(analyzed)
        if (BuildConfig.PLANNER_URL.isNotBlank()) {
            progress("planner", 94)
            val planning = runCatching {
                PlannerClient(BuildConfig.PLANNER_URL, AuthTokenProvider::currentToken).plan(
                    PlannerPrivacyBoundary.create("Create a polished, conservative first cut using the supplied transcript and preferences.", words, analyzed.source, analyzed.preferences, analyzed.operations, analyzed.revision)
                )
            }
            planning.onSuccess { response -> if (response is PlannerProposal) {
                analyzed = analyzed.replaceOperations(OperationValidator.validate(analyzed, response.operations, response.expectedRevision))
                store.save(analyzed)
            } }.onFailure { error ->
                analyzed = analyzed.copy(analysis = analyzed.analysis.copy(warnings = analyzed.analysis.warnings + "AI planner: ${error.message ?: "failed"}"))
                store.save(analyzed)
            }
        }
        progress("complete", 100)
        Result.success()
    }.getOrElse { Result.failure(Data.Builder().putString("error", it.message).build()) }
}

private fun EvidenceGraph.reference() = EvidenceGraphReference(
    sourceFingerprint = sourceFingerprint,
    schemaVersion = schemaVersion,
    relativeCachePath = "$sourceFingerprint/graph-v$schemaVersion.json",
    analyzerVersions = analyzerRuns.associate { it.analyzer.name to it.analyzer.version },
    partial = analyzerRuns.any { it.status == AnalyzerStatus.FAILED || it.status == AnalyzerStatus.UNSUPPORTED }
)

private fun unsupportedAnalyzers(): List<EvidenceAnalyzer> = listOf(
    UnsupportedEvidenceAnalyzer(AnalyzerIdentity("on-device face detector", "not-installed", ExecutionLocation.LOCAL_DEVICE), "Face detector is not bundled in this build."),
    UnsupportedEvidenceAnalyzer(AnalyzerIdentity("on-device OCR and screen detector", "not-installed", ExecutionLocation.LOCAL_DEVICE), "OCR and screen/UI detector is not bundled in this build."),
    UnsupportedEvidenceAnalyzer(AnalyzerIdentity("on-device music classifier", "not-installed", ExecutionLocation.LOCAL_DEVICE), "A truthful music classifier is not bundled in this build.")
)

class ExportWorker(context: Context, params: WorkerParameters) : ProgressWorker(context, params) {
    override suspend fun doWork(): Result = runCatching {
        val projectId = requireNotNull(inputData.getString("projectId"))
        val store = ProjectStore(applicationContext)
        val project = AudioAssetRepository(applicationContext).prepare(store.load(projectId))
        store.save(project)
        val capability = DeviceCapability.evaluate(applicationContext, inputData.getLong("estimatedBytes", 0))
        val (width, height) = project.preferences.aspectRatio.outputSize(
            if (capability.allow4k) 3840 else 1920,
            if (capability.allow4k) 2160 else 1080
        )
        val renderProject = project.copy(export = project.export.copy(
            width = width,
            height = height,
            codec = if (capability.allow4k) project.export.codec else "video/avc",
            bitrate = if (capability.allow4k) project.export.bitrate else 16_000_000
        ))
        progress(if (capability.allow4k) "export-4k" else "export-1080p-fallback", 1)
        val output = java.io.File(applicationContext.filesDir, "exports/${project.id}.mp4")
        ExportCoordinator.export(applicationContext, renderProject, output)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "ctrlb-${project.id.take(8)}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ctrlB")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val published = requireNotNull(applicationContext.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values))
        try {
            applicationContext.contentResolver.openOutputStream(published)?.use { sink -> output.inputStream().use { it.copyTo(sink) } } ?: error("Could not open export destination")
            applicationContext.contentResolver.update(published, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        } catch (error: Throwable) { applicationContext.contentResolver.delete(published, null, null); throw error }
        progress("complete", 100)
        Result.success(Data.Builder().putString("outputUri", published.toString()).putBoolean("used4k", capability.allow4k).putStringArray("fallbackReasons", capability.reasons.toTypedArray()).build())
    }.getOrElse { error ->
        Log.e("ExportWorker", "Video export failed", error)
        Result.failure(Data.Builder().putString("error", error.detailedMessage("Video export failed")).build())
    }
}

class PreviewWorker(context: Context, params: WorkerParameters) : ProgressWorker(context, params) {
    override suspend fun doWork(): Result = runCatching {
        val projectId = requireNotNull(inputData.getString("projectId"))
        val stored = ProjectStore(applicationContext).load(projectId)
        var warning: String? = null
        val prepared = runCatching { AudioAssetRepository(applicationContext).prepare(stored) }.getOrElse { error ->
            warning = "Audio preview unavailable: ${error.message}"
            stored.copy(operations = stored.operations.filterNot { it is SetBackgroundMusic || it is AddSoundEffect })
        }
        progress("preview", 5)
        val output = java.io.File(applicationContext.cacheDir, "previews/${prepared.id}-${prepared.revision}.mp4")
        val (previewWidth, previewHeight) = prepared.preferences.aspectRatio.outputSize(1280, 720)
        ExportCoordinator.export(applicationContext, prepared.copy(export = prepared.export.copy(width = previewWidth, height = previewHeight, codec = "video/avc", bitrate = 5_000_000)), output)
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

private fun AspectRatio.outputSize(longSide: Int, shortSide: Int): Pair<Int, Int> = when (this) {
    AspectRatio.LANDSCAPE -> longSide to shortSide
    AspectRatio.PORTRAIT -> shortSide to longSide
    AspectRatio.SQUARE -> shortSide to shortSide
}
