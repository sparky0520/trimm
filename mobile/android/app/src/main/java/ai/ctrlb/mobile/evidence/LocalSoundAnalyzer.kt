package ai.ctrlb.mobile.evidence

import ai.ctrlb.mobile.domain.TimeRange
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

data class DecodedPcm(val samples: FloatArray, val sampleRate: Int, val channelCount: Int)

fun interface AudioPcmDecoder {
    suspend fun decode(sourceUri: String): DecodedPcm
}

class LocalSoundAnalyzer(
    private val decoder: AudioPcmDecoder,
    private val speechRanges: List<TimeRange> = emptyList(),
    override val identity: AnalyzerIdentity =
        AnalyzerIdentity("trimm scene-aware audio analyzer", "2.0.0", ExecutionLocation.LOCAL_DEVICE)
) : EvidenceAnalyzer {
    constructor(context: Context, speechRanges: List<TimeRange> = emptyList()) :
        this(AndroidAudioPcmDecoder(context), speechRanges)

    override suspend fun analyze(context: AnalyzerContext): List<EvidenceObservation> {
        val pcm = decoder.decode(context.source.uri)
        require(pcm.sampleRate > 0 && pcm.channelCount > 0)
        if (pcm.samples.isEmpty()) throw UnsupportedOperationException("The audio track decoded no PCM samples.")
        val mono = if (pcm.channelCount == 1) pcm.samples else FloatArray(pcm.samples.size / pcm.channelCount) { frame ->
            var sum = 0f
            repeat(pcm.channelCount) { channel -> sum += pcm.samples[frame * pcm.channelCount + channel] }
            sum / pcm.channelCount
        }
        return WaveformEvidence.detect(
            context.source.fingerprint, context.source.durationMs, mono, pcm.sampleRate, identity, speechRanges
        )
    }
}

object WaveformEvidence {
    fun detect(
        fingerprint: String,
        durationMs: Long,
        samples: FloatArray,
        sampleRate: Int,
        analyzer: AnalyzerIdentity,
        speechRanges: List<TimeRange> = emptyList()
    ): List<EvidenceObservation> {
        require(durationMs > 0 && sampleRate > 0)
        val windowSamples = (sampleRate / 4).coerceAtLeast(1)
        val windows = samples.asList().chunked(windowSamples).mapIndexed { index, values ->
            val start = index * 250L
            val end = minOf(durationMs, start + 250L)
            if (start >= durationMs || end <= start) null else {
                val rms = sqrt(values.sumOf { (it * it).toDouble() } / values.size).toFloat()
                val peak = values.maxOf { abs(it) }
                val crossings = values.zipWithNext().count { (left, right) -> (left < 0) != (right < 0) }
                val zeroCrossingRate = crossings.toFloat() / values.size.coerceAtLeast(1)
                val crestDb = dbfs(peak) - dbfs(rms)
                Window(TimeRange(start, end), dbfs(rms), peak, zeroCrossingRate, crestDb)
            }
        }.filterNotNull()
        if (windows.isEmpty()) return emptyList()

        val fullRange = TimeRange(0, durationMs)
        val overallRms = sqrt(samples.sumOf { (it * it).toDouble() } / samples.size).toFloat()
        val clippedRatio = samples.count { abs(it) >= .999f }.toFloat() / samples.size
        val provenance = EvidenceProvenance(analyzer, fingerprint)
        val observations = mutableListOf<EvidenceObservation>(
            sound(fingerprint, analyzer, fullRange, SoundKind.LOUDNESS, dbfs(overallRms), "dBFS", .98f, provenance),
            sound(fingerprint, analyzer, fullRange, SoundKind.CLIPPING, clippedRatio, "ratio", .99f, provenance)
        )

        scenes(windows, durationMs, speechRanges).forEachIndexed { sceneIndex, scene ->
            val sceneWindows = windows.filter { it.range.overlaps(scene) }
            val suppliedSpeech = speechRanges.isNotEmpty()
            val energyMedian = median(sceneWindows.map(Window::dbfs))
            val speech = sceneWindows.filter { window ->
                if (suppliedSpeech) speechRanges.any { speechRange -> window.range.overlaps(speechRange) }
                else window.dbfs >= energyMedian + 6f && window.zeroCrossingRate in .025f..55f
            }
            val nonSpeech = sceneWindows - speech.toSet()
            val transient = nonSpeech.filter { it.crestDb >= 14f || it.peak >= .98f }
            val tonal = nonSpeech.filter { it.dbfs > -50f && it.zeroCrossingRate in .01f..25f && it.crestDb < 5f }
            val clean = nonSpeech.filterNot { it in transient || it in tonal }
            val sceneProvenance = EvidenceProvenance(
                analyzer, fingerprint,
                note = "scene=${sceneIndex + 1}; speechMask=${if (suppliedSpeech) "transcript" else "energy"}; " +
                    "cleanNoiseWindows=${clean.size}; rejectedTransient=${transient.size}; rejectedTonal=${tonal.size}"
            )
            val sceneClipping = sceneWindows.sumOf { window ->
                val from = (window.range.startMs * sampleRate / 1_000).toInt().coerceIn(0, samples.size)
                val to = (window.range.endMs * sampleRate / 1_000).toInt().coerceIn(from, samples.size)
                samples.sliceArray(from until to).count { abs(it) >= .999f }
            }.toFloat() / ((scene.endMs - scene.startMs) * sampleRate / 1_000).coerceAtLeast(1)
            observations += sound(fingerprint, analyzer, scene, SoundKind.CLIPPING, sceneClipping, "ratio", .99f, sceneProvenance)
            if (speech.isNotEmpty()) {
                observations += sound(
                    fingerprint, analyzer, scene, SoundKind.SPEECH_LEVEL,
                    median(speech.map(Window::dbfs)), "dBFS", if (suppliedSpeech) .95f else .65f, sceneProvenance
                )
            }
            if (clean.size >= 2) {
                val noiseFloor = median(clean.map(Window::dbfs))
                val confidence = (0.65f + clean.size.coerceAtMost(7) * .05f).coerceAtMost(.95f)
                observations += sound(fingerprint, analyzer, scene, SoundKind.NOISE_FLOOR, noiseFloor, "dBFS", confidence, sceneProvenance)
                if (speech.isNotEmpty()) {
                    observations += sound(
                        fingerprint, analyzer, scene, SoundKind.SNR,
                        median(speech.map(Window::dbfs)) - noiseFloor, "dB", minOf(confidence, if (suppliedSpeech) .95f else .65f), sceneProvenance
                    )
                }
                val silenceThreshold = minOf(noiseFloor + 4f, (speech.minOfOrNull(Window::dbfs) ?: -24f) - 14f)
                contiguous(clean.filter { it.dbfs <= silenceThreshold }).forEach {
                    observations += sound(fingerprint, analyzer, it, SoundKind.SILENCE, null, null, confidence, sceneProvenance)
                }
            } else {
                val rejectedRatio = (transient.size + tonal.size).toFloat() / nonSpeech.size.coerceAtLeast(1)
                observations += sound(
                    fingerprint, analyzer, scene, SoundKind.MEASUREMENT_UNCERTAINTY,
                    maxOf(rejectedRatio, if (nonSpeech.size < 2) 1f else 0f), "ratio", .99f, sceneProvenance
                )
            }
            transient.forEach { window ->
                observations += sound(fingerprint, analyzer, window.range, SoundKind.TRANSIENT, window.peak, "linear_peak", .8f, sceneProvenance)
            }
        }
        return observations
    }

    private fun scenes(windows: List<Window>, durationMs: Long, speechRanges: List<TimeRange>): List<TimeRange> {
        if (windows.size < 8) return listOf(TimeRange(0, durationMs))
        val boundaries = mutableListOf<Long>()
        var lastBoundary = 0L
        for (index in 4 until windows.size - 4) {
            val beforeWindows = windows.subList(index - 4, index)
            val afterWindows = windows.subList(index, index + 4)
            if (speechRanges.any { speech -> (beforeWindows + afterWindows).any { it.range.overlaps(speech) } }) continue
            val before = beforeWindows.map(Window::dbfs).average()
            val after = afterWindows.map(Window::dbfs).average()
            val boundary = windows[index].range.startMs
            if (abs(after - before) >= 8f && boundary - lastBoundary >= 2_000 && durationMs - boundary >= 2_000) {
                boundaries += boundary
                lastBoundary = boundary
            }
        }
        return (listOf(0L) + boundaries + durationMs).zipWithNext().map { (start, end) -> TimeRange(start, end) }
    }

    private fun contiguous(windows: List<Window>): List<TimeRange> {
        if (windows.isEmpty()) return emptyList()
        val result = mutableListOf<TimeRange>()
        var start = windows.first().range.startMs
        var end = windows.first().range.endMs
        windows.drop(1).forEach { window ->
            if (window.range.startMs == end) end = window.range.endMs
            else {
                if (end - start >= 500) result += TimeRange(start, end)
                start = window.range.startMs
                end = window.range.endMs
            }
        }
        if (end - start >= 500) result += TimeRange(start, end)
        return result
    }

    private fun sound(
        fingerprint: String, analyzer: AnalyzerIdentity, range: TimeRange, kind: SoundKind,
        value: Float?, unit: String?, confidence: Float, provenance: EvidenceProvenance
    ) = SoundEvidence(
        stableEvidenceId(fingerprint, analyzer, kind.name, range), fingerprint, range,
        confidence, provenance, kind, value, unit
    )

    private fun dbfs(value: Float): Float = if (value <= 0.000001f) -120f else (20f * log10(value)).coerceAtLeast(-120f)
    private fun median(values: List<Float>): Float = values.sorted().let { sorted ->
        if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f
    }
    private fun TimeRange.overlaps(other: TimeRange) = startMs < other.endMs && other.startMs < endMs
    private data class Window(
        val range: TimeRange, val dbfs: Float, val peak: Float,
        val zeroCrossingRate: Float, val crestDb: Float
    )
}

private class AndroidAudioPcmDecoder(private val context: Context) : AudioPcmDecoder {
    override suspend fun decode(sourceUri: String): DecodedPcm = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, Uri.parse(sourceUri), null)
        var codec: MediaCodec? = null
        try {
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw UnsupportedOperationException("Source has no decodable audio track.")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            codec = MediaCodec.createDecoderByType(mime).apply { configure(format, null, null, 0); start() }
            val values = ArrayList<Float>()
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val buffer = requireNotNull(codec.getInputBuffer(inputIndex))
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outputIndex >= 0) {
                    val output = requireNotNull(codec.getOutputBuffer(outputIndex)).order(ByteOrder.LITTLE_ENDIAN)
                    output.position(info.offset)
                    output.limit(info.offset + info.size)
                    val shorts = output.asShortBuffer()
                    while (shorts.hasRemaining()) values += shorts.get() / 32768f
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }
            DecodedPcm(values.toFloatArray(), sampleRate, channels)
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }
}
