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
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.Crop
import ai.ctrlb.mobile.media.effects.MotionPresetEffect
import ai.ctrlb.mobile.media.effects.GraphicOverlayEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.pow

@OptIn(UnstableApi::class)
object ExportCoordinator {
    fun composition(context: Context, project: MobileProject, isPreview: Boolean = false): Composition {
        val cuts = (
            project.operations.filterIsInstance<RemoveRanges>().filter { it.enabled }.flatMap { it.ranges }
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
                    val clipping = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(localStart)
                    if (localEnd < source.durationMs - 50) {
                        clipping.setEndPositionMs(localEnd)
                    }
                    val speedOp = project.operations.filterIsInstance<SetSpeed>().lastOrNull { 
                        it.enabled && targets(it.itemId, "${source.fingerprint}-$index", source.fingerprint, localStart, localEnd) 
                    }
                    val speed = speedOp?.speed ?: 1f
                    
                    val item = MediaItem.Builder()
                        .setUri(source.uri)
                        .setClippingConfiguration(clipping.build())
                        .build()
                    val globalRange = TimeRange(start, end)
                    val dialogueTarget = project.operations.filterIsInstance<NormaliseAudio>().lastOrNull {
                        it.enabled && it.parameter != "music_ducking" && targets(it.target, "${source.fingerprint}-$index", source.fingerprint, localStart, localEnd)
                    }?.dialogueLufs ?: -20f
                    val volume = project.operations.filterIsInstance<SetVolume>().lastOrNull {
                        it.enabled && targets(it.itemId, "${source.fingerprint}-$index", source.fingerprint, localStart, localEnd)
                    }?.volume ?: 1f
                    val audioEffects = if (dialogueTarget != -20f) {
                        listOf(DialogueAudioProcessor(0f, dialogueTarget), GainProcessor(SpeechDuckingGainProvider(volume, 0f, emptyList())))
                    } else if (volume != 1f) {
                        listOf(GainProcessor(SpeechDuckingGainProvider(volume, 0f, emptyList())))
                    } else {
                        emptyList()
                    }
                    val itemVideoEffects = mutableListOf<Effect>()
                    val colorOperations = project.operations.filterIsInstance<SetColorGrade>().filter {
                        it.enabled && targets(it.target, "${source.fingerprint}-$index", source.fingerprint, localStart, localEnd)
                    }
                    FineColorEffect.create(colorOperations)?.let(itemVideoEffects::add)
                    
                    val motionOp = project.operations.filterIsInstance<AddMotionPreset>().lastOrNull { op ->
                        op.enabled && (op.range == null || (op.range.startMs < localEnd + sourceStart && op.range.endMs > localStart + sourceStart))
                    }
                    motionOp?.let { op ->
                        itemVideoEffects.add(MotionPresetEffect(op.preset, op.intensity, (localEnd - localStart)))
                    }
                    project.operations.filterIsInstance<SetCrop>().lastOrNull { 
                        it.enabled && targets(it.itemId, "${source.fingerprint}-$index", source.fingerprint, localStart, localEnd) 
                    }?.let { cropOp ->
                        val crop = cropOp.crop
                        itemVideoEffects.add(Crop(crop.x, crop.y, crop.width, crop.height))
                    }
                    project.operations.filterIsInstance<SetRotation>().lastOrNull { 
                        it.enabled && targets(it.itemId, "${source.fingerprint}-$index", source.fingerprint, localStart, localEnd) 
                    }?.let { rotateOp ->
                        itemVideoEffects.add(ScaleAndRotateTransformation.Builder().setRotationDegrees(rotateOp.rotation).build())
                    }

                    EditedMediaItem.Builder(item).setDurationUs(maxOf(1L, source.durationMs * 1000L))
                        .setEffects(Effects(audioEffects, itemVideoEffects)).build()
                }
            }
        }
        val sequence = if (project.orderedSources.all { it.hasAudio }) EditedMediaItemSequence.withAudioAndVideoFrom(clips) else EditedMediaItemSequence.withVideoFrom(clips)
        val videoEffects = mutableListOf<Effect>(
        )
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
                if (end > start) {
                    val mappedWords = cue.words.mapNotNull { word ->
                        val wStart = timelineMapper.sourceToOutput(word.range.startMs)
                        val wEnd = timelineMapper.sourceToOutput(word.range.endMs)
                        if (wEnd > wStart) word.copy(range = TimeRange(wStart, wEnd)) else null
                    }
                    CaptionCue(cue.text, TimeRange(start, end), mappedWords)
                } else null
            }
            if (mapped.isNotEmpty()) {
                videoEffects += OverlayEffect(listOf(CaptionOverlay(mapped, project.export.height > project.export.width)))
            }
        }
        

        // Add Native Text Overlays
        project.operations.filterIsInstance<AddTextOverlay>().filter { it.enabled }.forEach { textOverlay ->
            val range = textOverlay.target?.let { TimeRange(it.startMs, it.endMs) }
            if (range != null) {
                val outputStart = timelineMapper.sourceToOutput(range.startMs)
                val outputEnd = timelineMapper.sourceToOutput(range.endMs)
                if (outputEnd > outputStart) {
                    videoEffects += OverlayEffect(
                        listOf(
                            TextGraphicOverlay(
                                context = context,
                                text = textOverlay.text,
                                blueprintJson = textOverlay.designBlueprint,
                                startMs = outputStart,
                                endMs = outputEnd
                            )
                        )
                    )
                }
            }
        }

        // Add Callouts and Spotlights
        val callouts = project.operations.filterIsInstance<AddCallout>().filter { it.enabled }.map { 
            GraphicOverlayEffect.CalloutData(it.text, it.target, it.range) 
        }
        val spotlights = project.operations.filterIsInstance<AddSpotlight>().filter { it.enabled }.map { 
            GraphicOverlayEffect.SpotlightData(it.target, it.range) 
        }
        if (callouts.isNotEmpty() || spotlights.isNotEmpty()) {
            videoEffects += GraphicOverlayEffect(callouts, spotlights)
        }

        val sequences = mutableListOf(sequence)

        // BGM and SFX Generation
        val ducking = project.operations.filterIsInstance<NormaliseAudio>().lastOrNull { it.enabled && (it.parameter == "music_ducking" || it.parameter == null) }
        val duckDb = ducking?.musicDuckDb ?: -15f
        val bufferMs = 500L
        
        val speechRanges = project.analysis.words.mapNotNull { word ->
            val start = timelineMapper.sourceToOutput(word.range.startMs)
            val end = timelineMapper.sourceToOutput(word.range.endMs)
            if (end > start) TimeRange(maxOf(0L, start - bufferMs), end + bufferMs) else null
        }

        project.operations.filterIsInstance<AddBgm>().filter { it.enabled }.forEach { bgm ->
            val item = MediaItem.Builder().setUri(Uri.parse(bgm.url)).build()
            
            // Only duck BGM beds
            val effects = if (speechRanges.isNotEmpty()) listOf(GainProcessor(SpeechDuckingGainProvider(1f, duckDb, speechRanges))) else emptyList()
            val audioDurationMs = getRemoteMediaDurationMs(bgm.url, 15000L)
            
            val targetMs = 0L
            val items = mutableListOf<EditedMediaItem>()
            
            val edited = EditedMediaItem.Builder(item)
                .setDurationUs(audioDurationMs * 1000L)
                .setEffects(Effects(effects, emptyList()))
                .build()

            if (isPreview) {
                val totalVideoDurationMs = timelineMapper.sourceToOutput(project.orderedSources.sumOf { it.durationMs })
                val repeats = if (audioDurationMs > 0) {
                    Math.ceil(totalVideoDurationMs.toDouble() / audioDurationMs).toInt()
                } else 1
                for (i in 0 until repeats) {
                    if (i == repeats - 1) {
                        val remainingMs = totalVideoDurationMs - (i * audioDurationMs)
                        if (remainingMs > 0) {
                            val clipping = MediaItem.ClippingConfiguration.Builder().setEndPositionMs(remainingMs).build()
                            val lastItem = MediaItem.Builder().setUri(Uri.parse(bgm.url)).setClippingConfiguration(clipping).build()
                            items.add(EditedMediaItem.Builder(lastItem).setDurationUs(remainingMs * 1000L).setEffects(Effects(effects, emptyList())).build())
                        }
                    } else {
                        items.add(edited)
                    }
                }
                sequences += EditedMediaItemSequence.withAudioAndVideoFrom(items)
            } else {
                items.add(edited)
                sequences += EditedMediaItemSequence.withAudioAndVideoFrom(items).buildUpon().setIsLooping(true).build()
            }
        }

        project.operations.filterIsInstance<AddBgmEffect>().filter { it.enabled }.forEach { effect ->
            val targetMs = timelineMapper.sourceToOutput(effect.target?.startMs ?: 0L)
            val items = mutableListOf<EditedMediaItem>()
            if (targetMs > 0) {
                val silenceUri = getSilenceUri(context, targetMs)
                val silenceItem = MediaItem.Builder().setUri(silenceUri).build()
                items += EditedMediaItem.Builder(silenceItem)
                    .setDurationUs(targetMs * 1000L)
                    .setEffects(Effects(emptyList(), emptyList()))
                    .build()
            }
            val effectItem = MediaItem.Builder().setUri(Uri.parse(effect.url)).build()
            val effectDurationMs = getRemoteMediaDurationMs(effect.url, 12000L)
            items += EditedMediaItem.Builder(effectItem)
                .setDurationUs(effectDurationMs * 1000L)
                .setEffects(Effects(emptyList(), emptyList()))
                .build()
            sequences += EditedMediaItemSequence.withAudioFrom(items)
        }

        project.operations.filterIsInstance<AddSfx>().filter { it.enabled }.forEach { sfx ->
            val targetMs = timelineMapper.sourceToOutput(sfx.target?.startMs ?: 0L)
            val items = mutableListOf<EditedMediaItem>()
            if (targetMs > 0) {
                val silenceUri = getSilenceUri(context, targetMs)
                val silenceItem = MediaItem.Builder().setUri(silenceUri).build()
                items += EditedMediaItem.Builder(silenceItem)
                    .setDurationUs(targetMs * 1000L)
                    .setEffects(Effects(emptyList(), emptyList()))
                    .build()
            }
            val sfxItem = MediaItem.Builder().setUri(Uri.parse(sfx.url)).build()
            val sfxDurationMs = getRemoteMediaDurationMs(sfx.url, 3000L)
            items += EditedMediaItem.Builder(sfxItem)
                .setDurationUs(sfxDurationMs * 1000L)
                .setEffects(Effects(emptyList(), emptyList()))
                .build()
            sequences += EditedMediaItemSequence.withAudioFrom(items)
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

private fun targets(target: OperationTarget?, exactId: String, legacyId: String, startMs: Long, endMs: Long): Boolean =
    target == null || ((target.sourceId == exactId || target.sourceId == legacyId) && target.startMs <= startMs && target.endMs >= endMs)

private fun targets(itemId: String, exactId: String, legacyId: String, startMs: Long, endMs: Long): Boolean =
    itemId == exactId || itemId == legacyId


    private fun splitAtBoundaries(ranges: List<TimeRange>, boundaries: List<Long>): List<TimeRange> = ranges.flatMap { range ->
        val points = (listOf(range.startMs) + boundaries.filter { it > range.startMs && it < range.endMs } + range.endMs).sorted()
        points.zipWithNext { start, end -> TimeRange(start, end) }
    }

    private fun getRemoteMediaDurationMs(url: String, defaultMs: Long = 15000L): Long {
        return kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val mmr = android.media.MediaMetadataRetriever()
                mmr.setDataSource(url, HashMap<String, String>())
                val duration = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: defaultMs
                mmr.release()
                duration
            } catch (e: Exception) {
                defaultMs
            }
        }
    }

    private fun getSilenceUri(context: Context, durationMs: Long): Uri {
        val sampleRate = 44100
        val numChannels = 2
        val bitsPerSample = 16
        val numSamples = (durationMs * sampleRate / 1000).toInt()
        val dataSize = numSamples * numChannels * bitsPerSample / 8
        val totalSize = 36 + dataSize

        val file = File(context.cacheDir, "silence_${durationMs}ms.wav")
        if (file.exists() && file.length() >= totalSize + 8) return Uri.fromFile(file)

        file.outputStream().use { out ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(totalSize)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16) // Subchunk1Size
            header.putShort(1) // AudioFormat (PCM)
            header.putShort(numChannels.toShort())
            header.putInt(sampleRate)
            header.putInt(sampleRate * numChannels * bitsPerSample / 8) // byteRate
            header.putShort((numChannels * bitsPerSample / 8).toShort()) // blockAlign
            header.putShort(bitsPerSample.toShort())
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(dataSize)
            out.write(header.array())
            val empty = ByteArray(minOf(dataSize, 8192))
            var written = 0
            while (written < dataSize) {
                val chunk = minOf(dataSize - written, empty.size)
                out.write(empty, 0, chunk)
                written += chunk
            }
        }
        return Uri.fromFile(file)
    }
}
