package ai.ctrlb.mobile.media

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
import androidx.media3.effect.HslAdjustment
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
        val cuts = project.operations.filterIsInstance<RemoveFumbles>().filter { it.enabled }.flatMap { it.ranges }
        val retained = retainedRanges(project.durationMs, TimelineMapper(cuts).removals)
        val timelineMapper = TimelineMapper(cuts)
        require(retained.isNotEmpty()) { "The edit removes the entire source." }
        var timelineStart = 0L
        val clips = project.orderedSources.flatMap { source ->
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
                    val cleanup = project.operations.filterIsInstance<SetAudioCleanup>().lastOrNull { it.enabled }?.strength ?: 0f
                    val target = project.operations.filterIsInstance<SetAudioLevels>().lastOrNull { it.enabled }?.dialogueLufs ?: -20f
                    val audioEffects = if (cleanup > 0f || target != -20f) listOf(DialogueAudioProcessor(cleanup, target)) else emptyList()
                    EditedMediaItem.Builder(item).setDurationUs(source.durationMs * 1_000).setEffects(Effects(audioEffects, emptyList())).build()
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
        project.operations.filterIsInstance<SetColorGrade>().lastOrNull { it.enabled }?.grade?.let { grade ->
            videoEffects += HslAdjustment.Builder().adjustSaturation((grade.saturation - 1f) * 100f).adjustLightness(grade.exposure * 22f).build()
            videoEffects += RgbAdjustment.Builder()
                .setRedScale((1f + grade.temperature * .08f).coerceAtLeast(.8f))
                .setGreenScale(1f)
                .setBlueScale((1f - grade.temperature * .08f).coerceAtLeast(.8f)).build()
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
        val audioRepository = AudioAssetRepository(context)
        project.operations.filterIsInstance<SetBackgroundMusic>().lastOrNull { it.enabled }?.let { music ->
            val gain = 10f.pow(music.gainDb / 20f)
            val duckDb = project.operations.filterIsInstance<SetAudioLevels>().lastOrNull { it.enabled }?.musicDuckDb ?: -9f
            val speechRanges = project.analysis.words.mapNotNull { word ->
                val start = timelineMapper.sourceToOutput(word.range.startMs)
                val end = timelineMapper.sourceToOutput(word.range.endMs)
                if (end > start) TimeRange(start, end) else null
            }
            val audioUri = Uri.fromFile(audioRepository.localFile(music.catalogId))
            val structure = music.structure
            if (structure == null || structure.segments.isEmpty()) {
                val item = EditedMediaItem.Builder(MediaItem.fromUri(audioUri))
                    .setEffects(Effects(listOf(GainProcessor(SpeechDuckingGainProvider(gain, duckDb, speechRanges))), emptyList())).build()
                sequences += EditedMediaItemSequence.withAudioFrom(listOf(item)).buildUpon().setIsLooping(music.loop).build()
            } else {
                val builder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
                var cursorMs = 0L
                structure.segments.sortedBy { it.timelineStartMs }.forEach { segment ->
                    if (segment.timelineStartMs > cursorMs) builder.addGap((segment.timelineStartMs - cursorMs) * 1_000)
                    val localSpeech = speechRanges.mapNotNull { range ->
                        val start = maxOf(range.startMs, segment.timelineStartMs)
                        val end = minOf(range.endMs, segment.timelineEndMs)
                        if (end > start) TimeRange(start - segment.timelineStartMs, end - segment.timelineStartMs) else null
                    }
                    val mediaItem = MediaItem.Builder().setUri(audioUri).setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(segment.sourceRange.startMs)
                            .setEndPositionMs(segment.sourceRange.endMs).build()
                    ).build()
                    val provider = SpeechDuckingGainProvider(
                        gain, duckDb, localSpeech,
                        fadeInMs = segment.fadeInMs,
                        fadeOutStartMs = segment.sourceRange.durationMs - segment.fadeOutMs,
                        fadeOutMs = segment.fadeOutMs,
                        attackMs = structure.duckAttackMs,
                        releaseMs = structure.duckReleaseMs
                    )
                    builder.addItem(EditedMediaItem.Builder(mediaItem)
                        .setDurationUs(segment.sourceRange.durationMs * 1_000)
                        .setEffects(Effects(listOf(GainProcessor(provider)), emptyList())).build())
                    cursorMs = segment.timelineEndMs
                }
                sequences += builder.build()
            }
        }
        project.operations.filterIsInstance<AddSoundEffect>().filter { it.enabled }.forEach { effect ->
            val gain = 10f.pow(effect.gainDb / 20f)
            val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(audioRepository.localFile(effect.catalogId))))
                .setEffects(Effects(listOf(GainProcessor(DefaultGainProvider.Builder(gain).build())), emptyList())).build()
            val builder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
            val outputAt = timelineMapper.sourceToOutput(effect.atSourceMs)
            if (outputAt > 0) builder.addGap(outputAt * 1_000)
            sequences += builder.addItem(item).build()
        }
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
}
