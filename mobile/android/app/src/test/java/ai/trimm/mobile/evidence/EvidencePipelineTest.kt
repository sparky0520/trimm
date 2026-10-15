package ai.trimm.mobile.evidence

import ai.trimm.mobile.domain.SourceMedia
import ai.trimm.mobile.domain.TimeRange
import ai.trimm.mobile.domain.TranscriptWord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class EvidencePipelineTest {
    private val source = SourceMedia("content://private/video", "abc123", 2_000, 1080, 1920)

    @Test fun `analyzers fail independently and errors redact private media`() = runTest {
        val root = Files.createTempDirectory("evidence-test").toFile()
        val good = analyzer("visual", "1") { context ->
            val range = TimeRange(0, 1_000)
            listOf(VisualEvidence(stableEvidenceId(context.source.fingerprint, identity, "EXPOSURE", range), context.source.fingerprint, range, .8f, EvidenceProvenance(identity, context.source.fingerprint), VisualKind.EXPOSURE, .5f))
        }
        val bad = analyzer("ocr", "1") { error("could not read content://private/video") }

        val graph = EvidencePipeline(EvidenceCache(root), listOf(good, bad), clock = { 42 }).analyze(source)

        assertEquals(1, graph.observations.size)
        assertEquals(AnalyzerStatus.COMPLETE, graph.analyzerRuns.first { it.analyzer.name == "visual" }.status)
        val failed = graph.analyzerRuns.first { it.analyzer.name == "ocr" }
        assertEquals(AnalyzerStatus.FAILED, failed.status)
        assertFalse(failed.safeError!!.contains("content://"))
        assertTrue(EvidenceCache(root).readGraph(source.fingerprint)!!.analyzerRuns.any { it.status == AnalyzerStatus.FAILED })
    }

    @Test fun `cache keys include source fingerprint and analyzer version`() = runTest {
        val cache = EvidenceCache(Files.createTempDirectory("evidence-cache-test").toFile())
        var calls = 0
        fun counting(version: String) = analyzer("frames", version) { context ->
            calls++
            val range = TimeRange(0, 1)
            listOf(FrameEvidence(stableEvidenceId(context.source.fingerprint, identity, "frame", range), context.source.fingerprint, range, 1f, EvidenceProvenance(identity, context.source.fingerprint), "frame.jpg", 10, 10))
        }
        EvidencePipeline(cache, listOf(counting("1"))).analyze(source)
        val cached = EvidencePipeline(cache, listOf(counting("1"))).analyze(source)
        EvidencePipeline(cache, listOf(counting("2"))).analyze(source)

        assertEquals(2, calls)
        assertEquals(AnalyzerStatus.CACHED, cached.analyzerRuns.single().status)
    }

    @Test fun `duplicate provider observations are collapsed before graph validation`() = runTest {
        val root = Files.createTempDirectory("evidence-duplicate-test").toFile()
        val duplicate = analyzer("transcript", "1") { context ->
            val range = TimeRange(0, 100)
            val observation = SpeechEvidence(
                stableEvidenceId(context.source.fingerprint, identity, "WORD", range, "hello"),
                context.source.fingerprint, range, .9f,
                EvidenceProvenance(identity, context.source.fingerprint),
                SpeechKind.WORD, "hello"
            )
            listOf(observation, observation)
        }

        val graph = EvidencePipeline(EvidenceCache(root), listOf(duplicate)).analyze(source)

        assertEquals(1, graph.observations.size)
        assertEquals(1, graph.analyzerRuns.single().observationCount)
    }

    @Test fun `speech evidence derives sentences pauses disfluencies and stable ids`() = runTest {
        val words = listOf(
            TranscriptWord("Um", TimeRange(0, 100), .9f, "speaker-A"),
            TranscriptWord("hello.", TimeRange(150, 400), .98f, "speaker-A"),
            TranscriptWord("Again", TimeRange(900, 1_200), .95f, "speaker-B")
        )
        val analyzer = SpeechEvidenceAnalyzer(words)
        val first = analyzer.analyze(AnalyzerContext(source, Files.createTempDirectory("speech-test").toFile()))
        val second = analyzer.analyze(AnalyzerContext(source, Files.createTempDirectory("speech-test").toFile()))

        assertEquals(first.map { it.id }, second.map { it.id })
        val kinds = first.filterIsInstance<SpeechEvidence>().map { it.kind }.toSet()
        assertTrue(kinds.containsAll(setOf(SpeechKind.WORD, SpeechKind.SENTENCE, SpeechKind.PAUSE, SpeechKind.SPEAKER_REGION)))
        assertTrue(first.all { it.provenance.analyzer.version == "1.0.0" })
        assertEquals(setOf("speaker-A", "speaker-B"), first.filterIsInstance<SpeechEvidence>().mapNotNull { it.speakerId }.toSet())
    }

    @Test fun `speech analyzer does not invent a speaker when diarization is absent`() = runTest {
        val observations = SpeechEvidenceAnalyzer(listOf(
            TranscriptWord("Hello", TimeRange(0, 200), .9f)
        )).analyze(AnalyzerContext(source, Files.createTempDirectory("speech-no-speaker").toFile()))
        assertTrue(observations.filterIsInstance<SpeechEvidence>().none { it.kind == SpeechKind.SPEAKER_REGION })
    }

    private fun analyzer(name: String, version: String, block: suspend TestAnalyzer.(AnalyzerContext) -> List<EvidenceObservation>) =
        TestAnalyzer(AnalyzerIdentity(name, version, ExecutionLocation.LOCAL_DEVICE), block)

    private class TestAnalyzer(
        override val identity: AnalyzerIdentity,
        private val block: suspend TestAnalyzer.(AnalyzerContext) -> List<EvidenceObservation>
    ) : EvidenceAnalyzer {
        override suspend fun analyze(context: AnalyzerContext) = block(context)
    }
}
