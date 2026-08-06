package ai.ctrlb.mobile.media

import ai.ctrlb.mobile.BuildConfig
import ai.ctrlb.mobile.domain.*
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Effect
import androidx.media3.common.audio.DefaultGainProvider
import androidx.media3.common.audio.GainProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.OverlayEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.pow

@OptIn(UnstableApi::class)
object ExportCoordinator {
    fun composition(context: Context, project: MobileProject): Composition {
        val cuts = (
            project.operations.filterIsInstance<RemoveRanges>().filter { it.enabled }.flatMap { it.ranges } +
                project.operations.filterIsInstance<RemoveFumbles>().filter { it.enabled }.flatMap { it.ranges } +
                project.operations.filterIsInstance<RemoveAwkwardSilences>().filter { it.enabled }.flatMap { it.ranges }
            ).distinct().sortedBy { it.startMs }
        val sourceOffsets = buildMap {
            var offset = 0L
            project.orderedSources.forEachIndexed { index, source -> 
                put("${source.fingerprint}-$index", offset)
                if (!containsKey(source.fingerprint)) put(source.fingerprint, offset)
                offset += source.durationMs 
            }
        }
        val fineTargets = project.operations.mapNotNull { operation ->
            val target = when (operation) {
                is SetAudioCleanup -> operation.target
                is SetAudioLevels -> operation.target
                is SetColorGrade -> operation.target
                else -> null
            } ?: return@mapNotNull null
            val offset = sourceOffsets[target.sourceId] ?: sourceOffsets[target.sourceId.substringBeforeLast("-")] ?: return@mapNotNull null
            TimeRange(offset + target.startMs, offset + target.endMs)
        }
        val retained = splitAtBoundaries(
            retainedRanges(project.durationMs, TimelineMapper(cuts).removals),
            fineTargets.flatMap { listOf(it.startMs, it.endMs) }
        )
        val timelineMapper = TimelineMapper(cuts)
        require(retained.isNotEmpty()) { "The edit removes the entire source." }
        var timelineStart = 0L
        val clips = project.orderedSources.flatMapIndexed { index, source ->
            val sourceStart = timelineStart
            val sourceEnd = sourceStart + source.durationMs
            timelineStart = sourceEnd
            retained.mapNotNull { range ->
                val start = maxOf(range.startMs, sourceStart)
                val end = minOf(range.endMs, sourceEnd)
                if (end <= start) null else {
                    val localStart = start - sourceStart
                    val localEnd = end - sourceStart
                    val item = MediaItem.Builder().setUri(source.uri).setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder().setStartPositionMs(localStart).setEndPositionMs(localEnd).build()
                    ).build()
                    val globalRange = TimeRange(start, end)
                    val cleanup = project.operations.filterIsInstance<SetAudioCleanup>().lastOrNull {
                        it.enabled && targets(it.target, "${source.fingerprint}-$index", source.fingerprint, localStart, localEnd)
                    }?.strength ?: 0f
                    val target = project.operations.filterIsInstance<SetAudioLevels>().lastOrNull {
                        it.enabled && it.parameter != "music_ducking" && targets(it.target, "${source.fingerprint}-$index", source.fingerprint, localStart, localEnd)
                    }?.dialogueLufs ?: -20f
                    val audioEffects = if (cleanup > 0f || target != -20f) listOf(DialogueAudioProcessor(cleanup, target)) else emptyList()
                    val itemVideoEffects = mutableListOf<Effect>()
                    val colorOperations = project.operations.filterIsInstance<SetColorGrade>().filter {
                        it.enabled && targets(it.target, "${source.fingerprint}-$index", source.fingerprint, localStart, localEnd)
                    }
                    FineColorEffect.create(colorOperations)?.let(itemVideoEffects::add)
                    EditedMediaItem.Builder(item).setDurationUs(maxOf(1L, source.durationMs * 1000L))
                        .setEffects(Effects(audioEffects, itemVideoEffects)).build()
                }
            }
        }
        val sequence = if (project.orderedSources.all { it.hasAudio }) EditedMediaItemSequence.withAudioAndVideoFrom(clips) else EditedMediaItemSequence.withVideoFrom(clips)
        val videoEffects = mutableListOf<Effect>(
        )
        val semanticOperations = project.operations.filterIsInstance<ApplySemanticVisual>().filter { it.enabled }.mapNotNull { operation ->
            val start = timelineMapper.sourceToOutput(operation.range.startMs)
            val end = timelineMapper.sourceToOutput(operation.range.endMs)
            if (end > start) operation.copy(range = TimeRange(start, end)) else null
        }
        if (semanticOperations.any { it.treatment == SemanticTreatment.REFRAME }) {
            videoEffects += SemanticReframeTransformation(semanticOperations)
        }
        videoEffects += Presentation.createForWidthAndHeight(
                project.export.width,
                project.export.height,
                Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
            )
        if (BuildConfig.FORCE_4K_EXPORT && maxOf(project.export.width, project.export.height) >= 3840) {
            // Prevent Media3 from remuxing a no-op presentation during the physical-device
            // experiment. This imperceptible adjustment requires a genuine decode/encode pass.
            videoEffects += RgbAdjustment.Builder()
                .setRedScale(.999f)
                .setGreenScale(.999f)
                .setBlueScale(.999f)
                .build()
        }
        project.operations.filterIsInstance<AddCaptions>().lastOrNull { it.enabled }?.let { captions ->
            val mapped = captions.cues.mapNotNull { cue ->
                val start = timelineMapper.sourceToOutput(cue.range.startMs)
                val end = timelineMapper.sourceToOutput(cue.range.endMs)
                if (end > start) CaptionCue(cue.text, TimeRange(start, end)) else null
            }
            if (mapped.isNotEmpty()) {
                videoEffects += OverlayEffect(listOf(CaptionOverlay(mapped, project.export.height > project.export.width)))
            }
        }
        val overlays = semanticOperations.filter { it.treatment != SemanticTreatment.REFRAME }
        if (overlays.isNotEmpty()) videoEffects += OverlayEffect(listOf(SemanticVisualOverlay(overlays)))
        val sequences = mutableListOf(sequence)

        return Composition.Builder(sequences).setEffects(Effects(emptyList(), videoEffects)).build()
    }

    suspend fun export(context: Context, project: MobileProject, output: File): File = withContext(Dispatchers.Main) {
        output.parentFile?.mkdirs(); output.delete()
        val composition = composition(context, project)
        suspendCancellableCoroutine { continuation ->
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(if (project.export.codec == "video/hevc") MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) { if (continuation.isActive) continuation.resume(output) }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { output.delete(); if (continuation.isActive) continuation.resumeWithException(exportException) }
                }).build()
            continuation.invokeOnCancellation {
                // WorkManager may cancel from a worker thread, while Transformer
                // requires all calls on the application looper it was created on.
                Handler(Looper.getMainLooper()).post {
                    transformer.cancel()
                    output.delete()
                }
            }
            transformer.start(composition, output.absolutePath)
        }
    }

    fun retainedRanges(durationMs: Long, removals: List<TimeRange>): List<TimeRange> {
        var cursor = 0L; val result = mutableListOf<TimeRange>()
        removals.forEach { cut -> if (cut.startMs > cursor) result += TimeRange(cursor, cut.startMs); cursor = maxOf(cursor, cut.endMs) }
        if (cursor < durationMs) result += TimeRange(cursor, durationMs)
        return result
    }

    private fun targets(target: OperationTarget?, exactId: String, legacyId: String, startMs: Long, endMs: Long): Boolean =
        target == null || ((target.sourceId == exactId || target.sourceId == legacyId) && target.startMs <= startMs && target.endMs >= endMs)

    private fun splitAtBoundaries(ranges: List<TimeRange>, boundaries: List<Long>): List<TimeRange> = ranges.flatMap { range ->
        val points = (listOf(range.startMs) + boundaries.filter { it > range.startMs && it < range.endMs } + range.endMs).sorted()
        points.zipWithNext { start, end -> TimeRange(start, end) }
    }
}
