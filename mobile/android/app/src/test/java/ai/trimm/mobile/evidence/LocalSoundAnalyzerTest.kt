package ai.trimm.mobile.evidence

import ai.trimm.mobile.domain.TimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random
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

    @Test fun `steady room tone yields speech level noise floor and snr from speech-free windows`() {
        val rate = 1_000
        val random = Random(7)
        val samples = FloatArray(rate * 6) { index ->
            val roomTone = (random.nextFloat() * 2f - 1f) * .012f
            if (index in 2 * rate until 4 * rate) {
                roomTone + (.2 * sin(index * 2.0 * Math.PI * 90 / rate)).toFloat()
            } else roomTone
        }
        val sounds = detect(samples, rate, listOf(TimeRange(2_000, 4_000)))
        val noise = sounds.single { it.kind == SoundKind.NOISE_FLOOR }
        val speech = sounds.single { it.kind == SoundKind.SPEECH_LEVEL }
        val snr = sounds.single { it.kind == SoundKind.SNR }
        assertTrue(noise.value!! in -48f..-35f)
        assertTrue(speech.value!! > noise.value)
        assertTrue(snr.value!! > 15f)
        assertTrue(noise.provenance.note!!.contains("cleanNoiseWindows="))
    }

    @Test fun `changing rooms produce separate scene floors while traffic and wind remain measured`() {
        val rate = 1_000
        val random = Random(11)
        val samples = FloatArray(rate * 8) { index ->
            val amplitude = if (index < rate * 4) .004f else .04f
            (random.nextFloat() * 2f - 1f) * amplitude
        }
        val floors = detect(samples, rate).filter { it.kind == SoundKind.NOISE_FLOOR }
        assertTrue(floors.size >= 2)
        assertTrue(floors.maxOf { it.value!! } - floors.minOf { it.value!! } > 12f)
        assertTrue(floors.all { it.range.endMs - it.range.startMs >= 2_000 })
    }

    @Test fun `music beds and continuous speech report uncertainty instead of invented noise`() {
        val rate = 2_000
        val music = FloatArray(rate * 4) { index -> (.12 * sin(index * 2.0 * Math.PI * 220 / rate)).toFloat() }
        val musicEvidence = detect(music, rate)
        assertTrue(musicEvidence.any { it.kind == SoundKind.MEASUREMENT_UNCERTAINTY })
        assertFalse(musicEvidence.any { it.kind == SoundKind.NOISE_FLOOR })

        val speech = FloatArray(rate * 4) { index ->
            (.18 * sin(index * 2.0 * Math.PI * (110 + (index / 300) % 40) / rate)).toFloat()
        }
        val speechEvidence = detect(speech, rate, listOf(TimeRange(0, 4_000)))
        assertTrue(speechEvidence.any { it.kind == SoundKind.SPEECH_LEVEL })
        assertTrue(speechEvidence.any { it.kind == SoundKind.MEASUREMENT_UNCERTAINTY })
        assertFalse(speechEvidence.any { it.kind == SoundKind.SNR })
    }

    @Test fun `long intentional silence and clipped recordings remain reviewable measurements`() {
        val rate = 1_000
        val samples = FloatArray(rate * 5) { index ->
            when {
                index < rate * 2 -> (.2 * sin(index * 2.0 * Math.PI * 100 / rate)).toFloat()
                index == rate * 4 -> 1f
                else -> 0f
            }
        }
        val sounds = detect(samples, rate, listOf(TimeRange(0, 2_000)))
        assertTrue(sounds.any { it.kind == SoundKind.SILENCE && it.range.endMs - it.range.startMs >= 1_500 })
        assertTrue(sounds.filter { it.kind == SoundKind.CLIPPING }.any { it.value!! > 0f })
        assertTrue(sounds.any { it.kind == SoundKind.TRANSIENT })
    }

    private fun detect(samples: FloatArray, rate: Int, speech: List<TimeRange> = emptyList()) =
        WaveformEvidence.detect(
            "abc", samples.size * 1_000L / rate, samples, rate,
            AnalyzerIdentity("waveform", "2", ExecutionLocation.LOCAL_DEVICE), speech
        ).filterIsInstance<SoundEvidence>()
}
