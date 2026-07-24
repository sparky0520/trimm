package ai.ctrlb.mobile.evidence

import ai.ctrlb.mobile.domain.SourceMedia
import ai.ctrlb.mobile.domain.TimeRange
import ai.ctrlb.mobile.domain.TranscriptWord

class SpeechEvidenceAnalyzer(
    private val words: List<TranscriptWord>,
    override val identity: AnalyzerIdentity = AnalyzerIdentity("ctrlB speech structure", "1.0.0", ExecutionLocation.EXTERNAL_SERVICE)
) : EvidenceAnalyzer {
    override suspend fun analyze(context: AnalyzerContext): List<EvidenceObservation> {
        val source = context.source
        val valid = words.filter { it.range.startMs < source.durationMs }.map {
            it.copy(range = TimeRange(it.range.startMs, minOf(it.range.endMs, source.durationMs)))
        }
        val result = valid.map { word ->
            speech(source, word.range, SpeechKind.WORD, word.confidence, word.text)
        }.toMutableList()
        sentenceRanges(valid).forEach { (range, sentenceWords) ->
            result += speech(source, range, SpeechKind.SENTENCE, sentenceWords.minOf { it.confidence }, sentenceWords.joinToString(" ") { it.text })
        }
        valid.zipWithNext().filter { (a, b) -> b.range.startMs - a.range.endMs >= 350 }.forEach { (a, b) ->
            val range = TimeRange(a.range.endMs, b.range.startMs)
            result += speech(source, range, SpeechKind.PAUSE, .98f)
        }
        valid.filter { it.text.lowercase().trim(',', '.', '!', '?') in DISFLUENCIES }.forEach {
            result += speech(source, it.range, SpeechKind.DISFLUENCY, it.confidence, it.text)
        }
        if (valid.isNotEmpty()) {
            val range = TimeRange(valid.first().range.startMs, valid.last().range.endMs)
            result += speech(source, range, SpeechKind.SPEAKER_REGION, .5f, speakerId = "speaker-unknown")
        }
        return result
    }

    private fun speech(source: SourceMedia, range: TimeRange, kind: SpeechKind, confidence: Float, text: String? = null, speakerId: String? = null) =
        SpeechEvidence(
            stableEvidenceId(source.fingerprint, identity, kind.name, range, text.orEmpty()),
            source.fingerprint, range, confidence.coerceIn(0f, 1f),
            EvidenceProvenance(identity, source.fingerprint, note = if (kind == SpeechKind.SPEAKER_REGION) "Diarization was unavailable; region is intentionally unassigned." else null),
            kind, text, speakerId
        )

    private fun sentenceRanges(words: List<TranscriptWord>): List<Pair<TimeRange, List<TranscriptWord>>> {
        val groups = mutableListOf<List<TranscriptWord>>()
        var current = mutableListOf<TranscriptWord>()
        words.forEachIndexed { index, word ->
            current += word
            val next = words.getOrNull(index + 1)
            if (word.text.endsWith('.') || word.text.endsWith('!') || word.text.endsWith('?') || next == null || next.range.startMs - word.range.endMs > 900) {
                groups += current
                current = mutableListOf()
            }
        }
        return groups.filter { it.isNotEmpty() }.map { TimeRange(it.first().range.startMs, it.last().range.endMs) to it }
    }

    companion object { private val DISFLUENCIES = setOf("um", "uh", "erm", "hmm") }
}
