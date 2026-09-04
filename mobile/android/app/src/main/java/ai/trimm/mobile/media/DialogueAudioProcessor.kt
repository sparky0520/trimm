package ai.trimm.mobile.media

import ai.trimm.mobile.domain.TimeRange
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.GainProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tanh

/**
 * Streaming dialogue leveler used by both preview and export.
 *
 * Loudness is estimated over a three-second gated RMS window. Gain is bounded
 * to avoid lifting low-SNR recordings aggressively, smoothed in decibels, and
 * followed by a soft limiter. This is intentionally conservative: it targets
 * a useful speech range rather than claiming offline EBU R128 conformance.
 */
internal class DialogueLeveler(
    targetLufs: Float,
    cleanupStrength: Float,
    private val sampleRate: Int,
) {
    private val target = targetLufs.coerceIn(-24f, -12f)
    private val cleanup = cleanupStrength.coerceIn(0f, 1f)
    private val windowSamples = (sampleRate * 3).coerceAtLeast(1)
    private val energy = FloatArray(windowSamples)
    private var energyIndex = 0
    private var energyCount = 0
    private var energySum = 0.0
    private var envelope = 0f
    private var gainDb = 0f

    fun process(sample: Float): Float {
        val magnitude = abs(sample)
        envelope = if (magnitude > envelope) envelope * .82f + magnitude * .18f
        else envelope * .995f + magnitude * .005f

        // Do not count near-silence toward the dialogue loudness estimate.
        val gateThreshold = .002f + cleanup * .004f
        if (magnitude >= gateThreshold) addEnergy(sample * sample)

        val measuredLufs = if (energyCount == 0) target else {
            (-.691 + 10.0 * log10((energySum / energyCount).coerceAtLeast(1e-9))).toFloat()
        }
        val desiredGainDb = (target - measuredLufs).coerceIn(-12f, 9f)
        val timeSeconds = 1f / sampleRate
        val timeConstant = if (desiredGainDb < gainDb) .08f else .75f
        val smoothing = 1f - exp(-timeSeconds / timeConstant)
        gainDb += (desiredGainDb - gainDb) * smoothing

        val gate = if (envelope >= gateThreshold) 1f
        else (envelope / gateThreshold).coerceIn(.35f, 1f)
        val gained = sample * gate * 10f.pow(gainDb / 20f)
        // tanh is continuous and prevents inter-sample gain changes from clipping.
        return (tanh(gained / .98f) * .98f).coerceIn(-.98f, .98f)
    }

    internal fun currentGainDb(): Float = gainDb

    private fun addEnergy(value: Float) {
        if (energyCount == windowSamples) energySum -= energy[energyIndex]
        else energyCount++
        energy[energyIndex] = value
        energySum += value
        energyIndex = (energyIndex + 1) % windowSamples
    }
}

@UnstableApi
class DialogueAudioProcessor(
    private val cleanupStrength: Float,
    private val targetLufs: Float,
) : BaseAudioProcessor() {
    private var levelers: Array<DialogueLeveler> = emptyArray()
    private var channel = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        levelers = Array(inputAudioFormat.channelCount) {
            DialogueLeveler(targetLufs, cleanupStrength, inputAudioFormat.sampleRate)
        }
        channel = 0
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val output = replaceOutputBuffer(inputBuffer.remaining())
        while (inputBuffer.hasRemaining()) {
            val input = if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) inputBuffer.float else inputBuffer.short / 32768f
            val processed = levelers[channel].process(input)
            channel = (channel + 1) % levelers.size
            if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) output.putFloat(processed)
            else output.putShort((processed * 32767f).toInt().coerceIn(-32768, 32767).toShort())
        }
        output.flip()
    }

    override fun onReset() {
        levelers = emptyArray()
        channel = 0
    }
}

@UnstableApi
class SpeechDuckingGainProvider(
    baseGain: Float,
    duckDb: Float,
    speechRanges: List<TimeRange>,
    private val fadeInMs: Long = 0,
    private val fadeOutStartMs: Long? = null,
    private val fadeOutMs: Long = 0,
    private val attackMs: Long = 150,
    private val releaseMs: Long = 350,
) : GainProcessor.GainProvider {
    private val normal = baseGain.coerceIn(0f, 1f)
    private val ducked = (baseGain * 10f.pow(duckDb.coerceIn(-18f, -3f) / 20f)).coerceIn(0f, 1f)
    private val ranges = mergeRanges(speechRanges.sortedBy { it.startMs })

    override fun getGainFactorAtSamplePosition(samplePosition: Long, sampleRate: Int): Float {
        val timeMs = samplePosition * 1_000 / sampleRate
        var gain = normal
        ranges.forEach { range ->
            val candidate = when {
                timeMs in (range.startMs - attackMs).coerceAtLeast(0)..<range.startMs -> {
                    val start = (range.startMs - attackMs).coerceAtLeast(0)
                    val duration = (range.startMs - start).coerceAtLeast(1)
                    normal + (ducked - normal) * ((timeMs - start).toFloat() / duration).coerceIn(0f, 1f)
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
        val entrance = if (fadeInMs > 0) (timeMs.toFloat() / fadeInMs).coerceIn(0f, 1f) else 1f
        val ending = fadeOutStartMs?.let { start ->
            if (fadeOutMs > 0 && timeMs >= start)
                (1f - (timeMs - start).toFloat() / fadeOutMs).coerceIn(0f, 1f)
            else 1f
        } ?: 1f
        return gain * minOf(entrance, ending)
    }

    override fun isUnityUntil(samplePosition: Long, sampleRate: Int): Long {
        if (normal < 1f) return samplePosition
        
        val timeMs = samplePosition * 1_000 / sampleRate
        var nextNonUnityMs = Long.MAX_VALUE
        
        for (range in ranges) {
            val attackStartMs = maxOf(0L, range.startMs - attackMs)
            if (timeMs <= attackStartMs) {
                nextNonUnityMs = minOf(nextNonUnityMs, attackStartMs)
            }
        }
        
        if (fadeOutStartMs != null && timeMs <= fadeOutStartMs) {
            nextNonUnityMs = minOf(nextNonUnityMs, fadeOutStartMs)
        }
        
        if (nextNonUnityMs == Long.MAX_VALUE) return C.TIME_END_OF_SOURCE
        return maxOf(samplePosition + 1, nextNonUnityMs * sampleRate / 1_000)
    }

    private fun mergeRanges(input: List<TimeRange>): List<TimeRange> {
        val merged = mutableListOf<TimeRange>()
        input.forEach { range ->
            val previous = merged.lastOrNull()
            if (previous != null && range.startMs <= previous.endMs + releaseMs) {
                merged[merged.lastIndex] = TimeRange(previous.startMs, maxOf(previous.endMs, range.endMs))
            } else merged += range
        }
        return merged
    }
}
