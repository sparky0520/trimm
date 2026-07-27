package ai.ctrlb.mobile.evidence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class LocalSoundAnalyzerTest {
    @Test fun `waveform detector reports levels clipping silence and transients with stable ids`() {
        val rate = 1_000
        val samples = FloatArray(rate * 2) { index ->
            when {
                index < 750 -> 0f
                index == 750 -> 1f
                else -> (.5 * sin(index.toDouble() / 8)).toFloat()
            }
        }
        val identity = AnalyzerIdentity("waveform", "1", ExecutionLocation.LOCAL_DEVICE)
        val first = WaveformEvidence.detect("abc", 2_000, samples, rate, identity).filterIsInstance<SoundEvidence>()
        val second = WaveformEvidence.detect("abc", 2_000, samples, rate, identity)

        assertEquals(first.map { it.id }, second.map { it.id })
        assertTrue(first.map { it.kind }.containsAll(setOf(
            SoundKind.LOUDNESS, SoundKind.NOISE_FLOOR, SoundKind.CLIPPING,
            SoundKind.SILENCE, SoundKind.TRANSIENT
        )))
        assertEquals("dBFS", first.first { it.kind == SoundKind.LOUDNESS }.unit)
        assertTrue(first.first { it.kind == SoundKind.CLIPPING }.value!! > 0f)
    }

    @Test fun `waveform detector does not invent music evidence`() {
        val evidence = WaveformEvidence.detect(
            "abc", 1_000, FloatArray(1_000) { .1f }, 1_000,
            AnalyzerIdentity("waveform", "1", ExecutionLocation.LOCAL_DEVICE)
        )
        assertTrue(evidence.filterIsInstance<SoundEvidence>().none { it.kind == SoundKind.MUSIC })
    }
}
