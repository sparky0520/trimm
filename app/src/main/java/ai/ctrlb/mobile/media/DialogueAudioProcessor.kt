package ai.ctrlb.mobile.media

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.GainProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign
import ai.ctrlb.mobile.domain.TimeRange

/** A conservative speech gate, compressor and make-up gain for local dialogue processing. */
@UnstableApi
class DialogueAudioProcessor(
    private val cleanupStrength: Float,
    targetLufs: Float
) : BaseAudioProcessor() {
    private val makeupGain = 10f.pow(((targetLufs + 20f) / 20f).coerceIn(-.2f, .4f))
    private var envelope = 0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val output = replaceOutputBuffer(inputBuffer.remaining())
        while (inputBuffer.hasRemaining()) {
            if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) output.putFloat(process(inputBuffer.float))
            else output.putShort((process(inputBuffer.short / 32768f) * 32767f).toInt().coerceIn(-32768, 32767).toShort())
        }
        output.flip()
    }

    private fun process(sample: Float): Float {
        val magnitude = abs(sample)
        envelope = if (magnitude > envelope) envelope * .82f + magnitude * .18f else envelope * .995f + magnitude * .005f
        val gateThreshold = .003f + cleanupStrength.coerceIn(0f, 1f) * .009f
        val gate = if (envelope >= gateThreshold) 1f else (envelope / gateThreshold).coerceIn(.15f, 1f)
        var value = sample * gate * makeupGain
        val threshold = .32f
        if (abs(value) > threshold) value = sign(value) * (threshold + (abs(value) - threshold) / 3.2f)
        return value.coerceIn(-.98f, .98f)
    }

    override fun onReset() { envelope = 0f }
}

@UnstableApi
class SpeechDuckingGainProvider(
    baseGain: Float,
    duckDb: Float,
    speechRanges: List<TimeRange>
) : GainProcessor.GainProvider {
    private val normal = baseGain.coerceIn(0f, 1f)
    private val ducked = (baseGain * 10f.pow(duckDb / 20f)).coerceIn(0f, 1f)
    private val ranges = speechRanges.sortedBy { it.startMs }
    private val attackMs = 150L
    private val releaseMs = 350L

    override fun getGainFactorAtSamplePosition(samplePosition: Long, sampleRate: Int): Float {
        val timeMs = samplePosition * 1_000 / sampleRate
        var gain = normal
        ranges.forEach { range ->
            val candidate = when {
                timeMs in (range.startMs - attackMs).coerceAtLeast(0)..<range.startMs -> {
                    val progress = (timeMs - (range.startMs - attackMs).coerceAtLeast(0)).toFloat() / attackMs
                    normal + (ducked - normal) * progress.coerceIn(0f, 1f)
                }
                timeMs in range.startMs..<range.endMs -> ducked
                timeMs in range.endMs..<(range.endMs + releaseMs) -> {
                    val progress = (timeMs - range.endMs).toFloat() / releaseMs
                    ducked + (normal - ducked) * progress.coerceIn(0f, 1f)
                }
                else -> normal
            }
            gain = minOf(gain, candidate)
        }
        return gain
    }

    override fun isUnityUntil(samplePosition: Long, sampleRate: Int): Long = C.TIME_UNSET
}
