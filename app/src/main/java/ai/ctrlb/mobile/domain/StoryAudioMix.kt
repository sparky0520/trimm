package ai.ctrlb.mobile.domain

import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable enum class AudioStructuralDecisionKind {
    ENTRANCE, SECTION_CHANGE, ENDING, DUCKING, SPEECH_TIMING, FALLBACK
}

@Serializable data class AudioStructuralDecision(
    val kind: AudioStructuralDecisionKind,
    val timelineAtMs: Long? = null,
    val storyEventId: String? = null,
    val evidenceIds: Set<String> = emptySet(),
    val confidence: Float,
    val rationale: String,
    val fallback: Boolean = false
)

@Serializable data class MusicStructureSegment(
    val sourceRange: TimeRange,
    val timelineStartMs: Long,
    val fadeInMs: Long = 250,
    val fadeOutMs: Long = 450
) {
    val timelineEndMs: Long get() = timelineStartMs + sourceRange.durationMs
}

@Serializable data class StoryAudioStructure(
    val version: String = StoryAudioStructurePlanner.VERSION,
    val segments: List<MusicStructureSegment>,
    val duckAttackMs: Long = 150,
    val duckReleaseMs: Long = 350,
    val decisions: List<AudioStructuralDecision>
)

/**
 * Produces an edit plan only for music. Story and spoken-media timestamps are
 * immutable inputs: music is clipped or faded to the edit, never the reverse.
 */
object StoryAudioStructurePlanner {
    const val VERSION = "story-audio-structure-v1"
    private const val ALIGN_WINDOW_MS = 1_500L
    private const val MIN_SECTION_MS = 1_000L
    private const val MIN_BEAT_CONFIDENCE = .65f
    private const val MIN_EVENT_CONFIDENCE = .6f

    fun plan(item: AudioCatalogItem, context: StoryAudioContext): StoryAudioStructure {
        val decisions = mutableListOf<AudioStructuralDecision>()
        val events = context.storyEvents.sortedWith(compareBy<StoryAudioEvent> { it.atMs }.thenBy { it.id })
        val entranceEvent = events.firstOrNull { it.confidence >= MIN_EVENT_CONFIDENCE }
        val entrance = entranceEvent?.atMs?.coerceIn(0, context.editDurationMs - 1) ?: 0L
        decisions += if (entranceEvent != null) decision(
            AudioStructuralDecisionKind.ENTRANCE, entrance, entranceEvent,
            "Music enters on the first sufficiently supported story beat."
        ) else AudioStructuralDecision(
            AudioStructuralDecisionKind.FALLBACK, 0, confidence = 0f, fallback = true,
            rationale = "No sufficiently supported entrance beat; music starts at the edit boundary."
        )

        val sections = (item.analysis?.sections ?: item.sections)
            .filter { it.endMs - it.startMs >= MIN_SECTION_MS }
            .sortedBy { it.startMs }
        val beatConfidence = item.analysis?.beatConfidence ?: item.beatConfidence
        val usableSectionChanges = if (beatConfidence >= MIN_BEAT_CONFIDENCE) sections.drop(1) else emptyList()
        val changes = mutableListOf<Pair<AudioSection, StoryAudioEvent>>()
        if (usableSectionChanges.isNotEmpty()) {
            events.filter { it.atMs > entrance && it.confidence >= MIN_EVENT_CONFIDENCE }.forEach { event ->
                val section = usableSectionChanges
                    .filterNot { candidate -> changes.any { it.first.startMs == candidate.startMs } }
                    .minByOrNull { abs((it.startMs - sections.first().startMs) - (event.atMs - entrance)) }
                if (section != null &&
                    abs((section.startMs - sections.first().startMs) - (event.atMs - entrance)) <= ALIGN_WINDOW_MS
                ) changes += section to event
            }
        }

        val sourceStart = sections.firstOrNull()?.startMs ?: 0L
        val boundaries = listOf(sourceStart to entrance) +
            changes.map { it.first.startMs to it.second.atMs } +
            listOf(minOf(item.durationMs, sourceStart + context.editDurationMs - entrance) to context.editDurationMs)
        val segments = boundaries.zipWithNext().mapNotNull { (start, end) ->
            val sourceEnd = minOf(end.first, item.durationMs)
            val timelineEnd = minOf(end.second, context.editDurationMs)
            val duration = minOf(sourceEnd - start.first, timelineEnd - start.second)
            if (duration <= 0) null else MusicStructureSegment(
                sourceRange = TimeRange(start.first, start.first + duration),
                timelineStartMs = start.second,
                fadeInMs = if (start.second == entrance) 300 else 180,
                fadeOutMs = if (timelineEnd == context.editDurationMs) minOf(750, duration / 2) else 180
            )
        }

        changes.forEach { (section, event) ->
            decisions += decision(
                AudioStructuralDecisionKind.SECTION_CHANGE, event.atMs, event,
                "A confident music section change aligns within ${ALIGN_WINDOW_MS}ms of this story beat " +
                    "(music beat confidence ${percent(beatConfidence)}; section ${section.label})."
            )
        }
        if (changes.isEmpty()) decisions += AudioStructuralDecision(
            AudioStructuralDecisionKind.FALLBACK, confidence = beatConfidence, fallback = true,
            rationale = if (beatConfidence < MIN_BEAT_CONFIDENCE)
                "Beat confidence ${percent(beatConfidence)} is too low for section edits; the continuous section is preserved."
            else "No supported story beat was close enough to a music section boundary; the continuous section is preserved."
        )
        decisions += AudioStructuralDecision(
            AudioStructuralDecisionKind.ENDING, context.editDurationMs, confidence = 1f,
            rationale = "Music ends with a bounded fade at the edit boundary."
        )
        decisions += AudioStructuralDecision(
            AudioStructuralDecisionKind.DUCKING, confidence = 1f,
            rationale = "Music uses smooth 150ms attack and 350ms release around cited speech ranges."
        )
        decisions += AudioStructuralDecision(
            AudioStructuralDecisionKind.SPEECH_TIMING, confidence = 1f,
            rationale = "Spoken content keeps its authored timing; only music clips and gain are changed."
        )
        return StoryAudioStructure(segments = segments, decisions = decisions)
    }

    private fun decision(
        kind: AudioStructuralDecisionKind,
        atMs: Long,
        event: StoryAudioEvent,
        rationale: String
    ) = AudioStructuralDecision(
        kind, atMs, event.id, event.evidenceIds, event.confidence, rationale
    )

    private fun percent(value: Float) = "${kotlin.math.round(value.coerceIn(0f, 1f) * 100).toInt()}%"
}
