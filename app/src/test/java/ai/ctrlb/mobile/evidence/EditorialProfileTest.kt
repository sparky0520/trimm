package ai.ctrlb.mobile.evidence

import ai.ctrlb.mobile.domain.Pace
import ai.ctrlb.mobile.domain.Platform
import ai.ctrlb.mobile.domain.TimeRange
import ai.ctrlb.mobile.domain.WizardPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorialProfileTest {
    @Test fun `derives deterministic robust distributions and source speaker segments`() {
        val graphs = listOf(
            graph("camera-a", "speaker-a", listOf(300, 650, 1_100, 9_000), .92f, 720, 1280),
            graph("camera-b", "speaker-b", listOf(900, 1_200, 1_500), .97f, 1920, 1080)
        )
        val preferences = WizardPreferences(platform = Platform.TIKTOK, pace = Pace.TIGHT)

        val first = EditorialProfileDeriver.derive(graphs, preferences)
        val second = EditorialProfileDeriver.derive(graphs.reversed(), preferences)

        assertEquals(first.evidenceFingerprint, second.evidenceFingerprint)
        assertEquals(4, first.segments.size)
        assertEquals(1_100f, first.distributions.getValue("pauseMs").median, 0f)
        assertTrue(first.distributions.getValue("pauseMs").p90 >= first.distributions.getValue("pauseMs").p75)
        assertEquals(setOf("portrait-camera", "landscape-camera"), first.segments.map { it.recordingSetup }.toSet())
        assertTrue(first.policy.reasons.all(String::isNotBlank))
        assertTrue(first.policy.rankingWeights.keys.containsAll(listOf("clarity", "technicalQuality", "editRisk")))
    }

    @Test fun `preferences and evidence invalidate the fingerprint while sparse evidence reports defaults`() {
        val sparse = listOf(graph("short", "speaker-a", listOf(500), .91f, 720, 1280))
        val natural = EditorialProfileDeriver.derive(sparse, WizardPreferences(pace = Pace.NATURAL))
        val tight = EditorialProfileDeriver.derive(sparse, WizardPreferences(pace = Pace.TIGHT))
        val changed = EditorialProfileDeriver.derive(
            listOf(graph("short", "speaker-a", listOf(700), .91f, 720, 1280)),
            WizardPreferences(pace = Pace.NATURAL)
        )

        assertNotEquals(natural.evidenceFingerprint, tight.evidenceFingerprint)
        assertNotEquals(natural.evidenceFingerprint, changed.evidenceFingerprint)
        assertTrue(natural.defaultsUsed.any { it.contains("fewer than 3 pauses") })
        assertTrue(natural.policy.automaticCutConfidence >= .93f)
        assertTrue("noiseFloor" in natural.unavailableSignals)
    }

    @Test fun `adaptive pause policy changes cutoff without cutting ordinary pauses`() {
        val graph = graph("paced", "speaker-a", listOf(300, 350, 400, 2_500), .98f, 720, 1280)
        val profile = EditorialProfileDeriver.derive(listOf(graph), WizardPreferences(pace = Pace.NATURAL))
        val distribution = profile.distributions.getValue("pauseMs")

        assertTrue(distribution.percentile(350f) < distribution.percentile(2_500f))
        assertTrue(profile.policy.pauseCutPercentile > .5f)
    }

    private fun graph(
        fingerprint: String,
        speaker: String,
        pauses: List<Int>,
        confidence: Float,
        width: Int,
        height: Int
    ): EvidenceGraph {
        val speechAnalyzer = AnalyzerIdentity("speech", "1", ExecutionLocation.EXTERNAL_SERVICE)
        val localAnalyzer = AnalyzerIdentity("local", "1", ExecutionLocation.LOCAL_DEVICE)
        val speechProvenance = EvidenceProvenance(speechAnalyzer, fingerprint)
        val localProvenance = EvidenceProvenance(localAnalyzer, fingerprint)
        val observations = mutableListOf<EvidenceObservation>(
            FrameEvidence("frame-$fingerprint", fingerprint, TimeRange(0, 1), .99f, localProvenance, "frame.jpg", width, height),
            SpeechEvidence("speaker-$fingerprint", fingerprint, TimeRange(0, 15_000), confidence, speechProvenance, SpeechKind.SPEAKER_REGION, speakerId = speaker)
        )
        var cursor = 0L
        pauses.forEachIndexed { index, pause ->
            observations += SpeechEvidence("word-$fingerprint-$index", fingerprint, TimeRange(cursor, cursor + 200), confidence, speechProvenance, SpeechKind.WORD, "word", speaker)
            cursor += 200
            observations += SpeechEvidence("pause-$fingerprint-$index", fingerprint, TimeRange(cursor, cursor + pause), confidence, speechProvenance, SpeechKind.PAUSE, speakerId = speaker)
            cursor += pause
        }
        return EvidenceGraph(
            sourceFingerprint = fingerprint,
            sourceDurationMs = 20_000,
            createdAtEpochMs = 1,
            observations = observations,
            analyzerRuns = emptyList(),
            disclosure = EvidenceDisclosure(emptyList(), emptyList())
        )
    }
}
