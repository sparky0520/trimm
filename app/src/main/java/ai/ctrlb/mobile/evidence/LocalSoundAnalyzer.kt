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
    override val identity: AnalyzerIdentity =
        AnalyzerIdentity("ctrlB waveform analyzer", "1.0.0", ExecutionLocation.LOCAL_DEVICE)
) : EvidenceAnalyzer {
    constructor(context: Context) : this(AndroidAudioPcmDecoder(context))

    override suspend fun analyze(context: AnalyzerContext): List<EvidenceObservation> {
        val pcm = decoder.decode(context.source.uri)
        require(pcm.sampleRate > 0 && pcm.channelCount > 0)
        if (pcm.samples.isEmpty()) throw UnsupportedOperationException("The audio track decoded no PCM samples.")
        val mono = if (pcm.channelCount == 1) pcm.samples else FloatArray(pcm.samples.size / pcm.channelCount) { frame ->
            var sum = 0f
            repeat(pcm.channelCount) { channel -> sum += pcm.samples[frame * pcm.channelCount + channel] }
            sum / pcm.channelCount
        }
        return WaveformEvidence.detect(context.source.fingerprint, context.source.durationMs, mono, pcm.sampleRate, identity)
    }
}

object WaveformEvidence {
    fun detect(
        fingerprint: String,
        durationMs: Long,
        samples: FloatArray,
        sampleRate: Int,
        analyzer: AnalyzerIdentity
    ): List<EvidenceObservation> {
        require(durationMs > 0 && sampleRate > 0)
        val windowSamples = (sampleRate / 4).coerceAtLeast(1)
        val windows = samples.asList().chunked(windowSamples).mapIndexed { index, values ->
            val start = index * 250L
            val end = minOf(durationMs, start + 250L)
            if (start >= durationMs || end <= start) null else {
                val rms = sqrt(values.sumOf { (it * it).toDouble() } / values.size).toFloat()
                val peak = values.maxOf { abs(it) }
                Window(TimeRange(start, end), dbfs(rms), peak)
            }
        }.filterNotNull()
        if (windows.isEmpty()) return emptyList()

        val fullRange = TimeRange(0, durationMs)
        val overallRms = sqrt(samples.sumOf { (it * it).toDouble() } / samples.size).toFloat()
        val noiseFloor = windows.map(Window::dbfs).sorted()[((windows.size - 1) * .2f).toInt()]
        val clippedRatio = samples.count { abs(it) >= .999f }.toFloat() / samples.size
        val provenance = EvidenceProvenance(analyzer, fingerprint)
        val observations = mutableListOf<EvidenceObservation>(
            sound(fingerprint, analyzer, fullRange, SoundKind.LOUDNESS, dbfs(overallRms), "dBFS", .98f, provenance),
            sound(fingerprint, analyzer, fullRange, SoundKind.NOISE_FLOOR, noiseFloor, "dBFS", .75f, provenance),
            sound(fingerprint, analyzer, fullRange, SoundKind.CLIPPING, clippedRatio, "ratio", .99f, provenance)
        )

        contiguous(windows.filter { it.dbfs <= -48f }).forEach {
            observations += sound(fingerprint, analyzer, it, SoundKind.SILENCE, null, null, .9f, provenance)
        }
        windows.zipWithNext().filter { (before, after) ->
            after.dbfs - before.dbfs >= 14f && after.peak >= .35f
        }.forEach { (_, after) ->
            observations += sound(fingerprint, analyzer, after.range, SoundKind.TRANSIENT, after.peak, "linear_peak", .72f, provenance)
        }
        return observations
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
    private data class Window(val range: TimeRange, val dbfs: Float, val peak: Float)
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
