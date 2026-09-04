package ai.trimm.mobile.evidence

import ai.trimm.mobile.domain.SourceMedia
import ai.trimm.mobile.domain.TimeRange
import ai.trimm.mobile.domain.TranscriptWord

class SpeechEvidenceAnalyzer(
    private val words: List<TranscriptWord>,
    override val identity: AnalyzerIdentity = AnalyzerIdentity("trimm speech structure", "1.0.0", ExecutionLocation.EXTERNAL_SERVICE)
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
        speakerRegions(valid).forEach { (range, regionWords) ->
            val speakerId = requireNotNull(regionWords.first().speakerId)
            result += speech(source, range, SpeechKind.SPEAKER_REGION, regionWords.minOf { it.confidence }, speakerId = speakerId)
        }
        return result
    }

    private fun speech(source: SourceMedia, range: TimeRange, kind: SpeechKind, confidence: Float, text: String? = null, speakerId: String? = null) =
        SpeechEvidence(
            stableEvidenceId(source.fingerprint, identity, kind.name, range, text.orEmpty()),
            source.fingerprint, range, confidence.coerceIn(0f, 1f),
            EvidenceProvenance(identity, source.fingerprint),
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

    private fun speakerRegions(words: List<TranscriptWord>): List<Pair<TimeRange, List<TranscriptWord>>> {
        val regions = mutableListOf<List<TranscriptWord>>()
        var current = mutableListOf<TranscriptWord>()
        words.forEach { word ->
            if (word.speakerId == null) {
                if (current.isNotEmpty()) regions += current
                current = mutableListOf()
            } else if (current.isNotEmpty() && current.last().speakerId != word.speakerId) {
                regions += current
                current = mutableListOf(word)
            } else current += word
        }
        if (current.isNotEmpty()) regions += current
        return regions.map { TimeRange(it.first().range.startMs, it.last().range.endMs) to it }
    }

    companion object { private val DISFLUENCIES = setOf("um", "uh", "erm", "hmm") }
}
