package ai.trimm.mobile.media

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

class DialogueAudioProcessorTest {
    @Test fun `quiet dialogue receives bounded smoothed gain`() {
        val leveler = DialogueLeveler(-16f, .5f, 1_000)
        val output = FloatArray(5_000) { leveler.process(.02f) }

        assertTrue(leveler.currentGainDb() in 0f..9.01f)
        assertTrue(rms(output.takeLast(1_000)) > rms(output.take(100)))
        assertTrue(output.all { abs(it) <= .98f })
    }

    @Test fun `hot dialogue is attenuated without clipping`() {
        val leveler = DialogueLeveler(-18f, 0f, 1_000)
        val output = FloatArray(5_000) { leveler.process(.8f) }

        assertTrue(leveler.currentGainDb() < 0f)
        assertTrue(output.all { abs(it) <= .98f })
        assertTrue(rms(output.takeLast(1_000)) < rms(output.take(100)))
    }

    @Test fun `silence does not trigger maximum makeup gain`() {
        val leveler = DialogueLeveler(-16f, 1f, 48_000)
        repeat(48_000) { leveler.process(0f) }
        assertTrue(abs(leveler.currentGainDb()) < .01f)
    }

    private fun rms(samples: List<Float>) = sqrt(samples.sumOf { it.toDouble().pow(2) } / samples.size)
}
