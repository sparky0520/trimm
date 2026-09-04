package ai.trimm.mobile.planning

import ai.trimm.mobile.domain.TimeRange
import ai.trimm.mobile.evidence.*
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable enum class MediaIndexScope {
    @kotlinx.serialization.SerialName("exact") EXACT,
    @kotlinx.serialization.SerialName("broad_editorial") BROAD_EDITORIAL,
    @kotlinx.serialization.SerialName("specialist") SPECIALIST
}
@Serializable enum class SelectionReason {
    @kotlinx.serialization.SerialName("user_requested") USER_REQUESTED,
    @kotlinx.serialization.SerialName("representative_coverage") REPRESENTATIVE_COVERAGE,
    @kotlinx.serialization.SerialName("measured_candidate") MEASURED_CANDIDATE,
    @kotlinx.serialization.SerialName("uncertainty") UNCERTAINTY,
    @kotlinx.serialization.SerialName("agent_requested") AGENT_REQUESTED
}
@Serializable enum class DerivativeKind {
    @kotlinx.serialization.SerialName("audio") AUDIO, @kotlinx.serialization.SerialName("frame") FRAME,
    @kotlinx.serialization.SerialName("crop") CROP, @kotlinx.serialization.SerialName("text") TEXT,
    @kotlinx.serialization.SerialName("evidence") EVIDENCE
}
@Serializable enum class SignalKind {
    @kotlinx.serialization.SerialName("speech") SPEECH, @kotlinx.serialization.SerialName("sound") SOUND,
    @kotlinx.serialization.SerialName("visual") VISUAL, @kotlinx.serialization.SerialName("motion") MOTION,
    @kotlinx.serialization.SerialName("text") TEXT, @kotlinx.serialization.SerialName("technical") TECHNICAL
}

@Serializable data class MediaIndexSegment(
    val id: String, val sourceId: String, val startMs: Long, val endMs: Long,
    val signalKinds: List<SignalKind>, val observationIds: List<String>
)
@Serializable data class MediaIndexSelection(
    val id: String, val sourceId: String, val startMs: Long, val endMs: Long,
    val reasons: List<SelectionReason>, val observationIds: List<String> = emptyList(),
    val derivativeKinds: List<DerivativeKind>
)
@Serializable data class RequestShapedMediaIndex(
    val schemaVersion: Int = 1,
    val indexId: String,
    val requestScope: MediaIndexScope,
    val generatedAtEpochMs: Long,
    val segments: List<MediaIndexSegment>,
    val selections: List<MediaIndexSelection>
)
data class RequestedRange(
    val sourceId: String, val range: TimeRange, val reason: SelectionReason,
    val derivativeKinds: List<DerivativeKind> = listOf(DerivativeKind.AUDIO, DerivativeKind.FRAME)
)
data class MediaIndexRequirements(
    val scope: MediaIndexScope,
    val requestedRanges: List<RequestedRange> = emptyList(),
    val candidateRanges: List<RequestedRange> = emptyList(),
    val uncertaintyRanges: List<RequestedRange> = emptyList(),
    val maximumSelections: Int = 24
)

object MediaIndexRequestPlanner {
    private val broadTerms = listOf("publishable", "polish", "first cut", "best version", "shorten", "story")
    private val audioTerms = listOf("audio", "speech", "pause", "silence", "noise", "music", "loud", "volume")
    private val visualTerms = listOf("face", "frame", "screen", "ui", "text", "caption", "visual", "shot", "crop")
    private val timestamp = Regex("""(?<!\d)(?:(\d{1,2}):)?(\d{1,2}):(\d{2})(?!\d)""")

    fun plan(request: String, sources: List<Pair<String, Long>>, maximumSelections: Int = 24): MediaIndexRequirements {
        require(request.isNotBlank())
        require(sources.isNotEmpty() && sources.all { it.first.isNotBlank() && it.second > 0 })
        val normalized = request.lowercase()
        val match = timestamp.find(normalized)
        val kinds = buildList {
            if (audioTerms.any(normalized::contains)) add(DerivativeKind.AUDIO)
            if (visualTerms.any(normalized::contains)) addAll(listOf(DerivativeKind.FRAME, DerivativeKind.CROP, DerivativeKind.TEXT))
            if (isEmpty()) addAll(listOf(DerivativeKind.AUDIO, DerivativeKind.FRAME))
            add(DerivativeKind.EVIDENCE)
        }.distinct()
        if (match != null) {
            val requestedMs = ((match.groupValues[1].toLongOrNull() ?: 0L) * 3_600 +
                match.groupValues[2].toLong() * 60 + match.groupValues[3].toLong()) * 1_000
            val (sourceId, duration) = sources.first()
            val start = (requestedMs - 2_000).coerceAtLeast(0)
            val end = (requestedMs + 2_000).coerceAtMost(duration)
            require(start < end) { "Requested timestamp is outside the source" }
            return MediaIndexRequirements(
                scope = MediaIndexScope.EXACT,
                requestedRanges = listOf(RequestedRange(sourceId, TimeRange(start, end), SelectionReason.USER_REQUESTED, kinds)),
                maximumSelections = maximumSelections
            )
        }
        return MediaIndexRequirements(
            scope = if (broadTerms.any(normalized::contains)) MediaIndexScope.BROAD_EDITORIAL else MediaIndexScope.SPECIALIST,
            requestedRanges = if (broadTerms.any(normalized::contains)) emptyList() else sources.map { (sourceId, duration) ->
                RequestedRange(sourceId, TimeRange(0, duration), SelectionReason.USER_REQUESTED, kinds)
            },
            maximumSelections = maximumSelections
        )
    }
}

object RequestShapedMediaIndexBuilder {
    fun build(graphs: List<EvidenceGraph>, requirements: MediaIndexRequirements): RequestShapedMediaIndex {
        require(graphs.isNotEmpty())
        require(requirements.maximumSelections > 0)
        val bySource = graphs.associateBy { it.sourceFingerprint }
        val explicit = requirements.requestedRanges + requirements.candidateRanges + requirements.uncertaintyRanges
        explicit.forEach {
            val duration = requireNotNull(bySource[it.sourceId]) { "Unknown source ${it.sourceId}" }.sourceDurationMs
            require(it.range.startMs >= 0 && it.range.endMs <= duration)
        }
        val inferredCandidates = if (requirements.scope == MediaIndexScope.BROAD_EDITORIAL) measuredCandidateRanges(graphs) else emptyList()
        val inferredUncertainty = if (requirements.scope == MediaIndexScope.BROAD_EDITORIAL) uncertaintyRanges(graphs) else emptyList()
        val representativeBudget = (requirements.maximumSelections - explicit.size - inferredCandidates.size - inferredUncertainty.size)
            .coerceAtLeast(graphs.size)
        val representative = if (requirements.scope == MediaIndexScope.BROAD_EDITORIAL) representativeRanges(graphs, representativeBudget) else emptyList()
        val requested = distinctRanges(explicit + roundRobin(representative, inferredCandidates, inferredUncertainty))
            .take(requirements.maximumSelections)
        val selections = requested.mapIndexed { index, item ->
            val observations = bySource.getValue(item.sourceId).observations
                .filter { it.range.startMs < item.range.endMs && it.range.endMs > item.range.startMs }
            MediaIndexSelection(
                id = "selection-${index + 1}", sourceId = item.sourceId,
                startMs = item.range.startMs, endMs = item.range.endMs,
                reasons = listOf(item.reason),
                observationIds = observations.map { it.id },
                derivativeKinds = item.derivativeKinds
            )
        }
        require(selections.isNotEmpty())
        return RequestShapedMediaIndex(
            indexId = UUID.randomUUID().toString(),
            requestScope = requirements.scope,
            generatedAtEpochMs = System.currentTimeMillis(),
            segments = graphs.flatMap { graph -> graph.observations.map { it.toSegment() } },
            selections = selections
        )
    }

    private fun roundRobin(vararg groups: List<RequestedRange>): List<RequestedRange> {
        val maximum = groups.maxOfOrNull(List<RequestedRange>::size) ?: 0
        return (0 until maximum).flatMap { index -> groups.mapNotNull { it.getOrNull(index) } }
    }

    private fun distinctRanges(ranges: List<RequestedRange>): List<RequestedRange> {
        val merged = linkedMapOf<String, RequestedRange>()
        ranges.forEach { range ->
            val key = "${range.sourceId}|${range.range.startMs}|${range.range.endMs}|${range.reason}"
            val previous = merged[key]
            merged[key] = if (previous == null) range else previous.copy(
                derivativeKinds = (previous.derivativeKinds + range.derivativeKinds).distinct()
            )
        }
        return merged.values.toList()
    }

    private fun representativeRanges(graphs: List<EvidenceGraph>, budget: Int): List<RequestedRange> {
        val perSource = (budget / graphs.size).coerceAtLeast(1)
        return graphs.flatMap { graph ->
            (0 until perSource).map { index ->
                val start = graph.sourceDurationMs * index / perSource
                val end = graph.sourceDurationMs * (index + 1) / perSource
                RequestedRange(
                    graph.sourceFingerprint, TimeRange(start, end),
                    SelectionReason.REPRESENTATIVE_COVERAGE,
                    listOf(DerivativeKind.AUDIO, DerivativeKind.FRAME, DerivativeKind.EVIDENCE)
                )
            }
        }
    }

    private fun measuredCandidateRanges(graphs: List<EvidenceGraph>): List<RequestedRange> = graphs.flatMap { graph ->
        graph.observations
            .filter { it.confidence >= .7f && it !is FrameEvidence }
            .sortedWith(compareByDescending<EvidenceObservation> { it.confidence }.thenBy { it.range.startMs }.thenBy { it.id })
            .take(4)
            .map { observation ->
                RequestedRange(
                    graph.sourceFingerprint, observation.range, SelectionReason.MEASURED_CANDIDATE,
                    derivativeKindsFor(observation)
                )
            }
    }

    private fun uncertaintyRanges(graphs: List<EvidenceGraph>): List<RequestedRange> = graphs.flatMap { graph ->
        val weak = graph.observations.filter { it.confidence < .7f }
            .sortedWith(compareBy<EvidenceObservation> { it.confidence }.thenBy { it.range.startMs }.thenBy { it.id })
        weak.take(4).map { observation ->
            // Expand weak samples so a later local pass can resample the
            // neighborhood more densely instead of pretending certainty.
            val center = (observation.range.startMs + observation.range.endMs) / 2
            val start = (center - 1_500).coerceAtLeast(0)
            val end = (center + 1_500).coerceAtMost(graph.sourceDurationMs)
            RequestedRange(
                graph.sourceFingerprint, TimeRange(start, end), SelectionReason.UNCERTAINTY,
                derivativeKindsFor(observation)
            )
        }
    }

    private fun derivativeKindsFor(observation: EvidenceObservation): List<DerivativeKind> = when (observation) {
        is SpeechEvidence, is SoundEvidence -> listOf(DerivativeKind.AUDIO, DerivativeKind.EVIDENCE)
        is VisualEvidence -> if (observation.kind == VisualKind.OCR_TEXT) {
            listOf(DerivativeKind.FRAME, DerivativeKind.CROP, DerivativeKind.TEXT, DerivativeKind.EVIDENCE)
        } else listOf(DerivativeKind.FRAME, DerivativeKind.CROP, DerivativeKind.EVIDENCE)
        is FrameEvidence, is ShotEvidence -> listOf(DerivativeKind.FRAME, DerivativeKind.EVIDENCE)
    }

    private fun EvidenceObservation.toSegment(): MediaIndexSegment {
        val signals = when (this) {
            is SpeechEvidence -> listOf(SignalKind.SPEECH)
            is SoundEvidence -> listOf(SignalKind.SOUND)
            is FrameEvidence, is ShotEvidence -> listOf(SignalKind.VISUAL)
            is VisualEvidence -> when (kind) {
                VisualKind.MOTION, VisualKind.SHAKE -> listOf(SignalKind.VISUAL, SignalKind.MOTION)
                VisualKind.OCR_TEXT -> listOf(SignalKind.VISUAL, SignalKind.TEXT)
                else -> listOf(SignalKind.VISUAL)
            }
        }
        return MediaIndexSegment(id, sourceFingerprint, range.startMs, range.endMs, signals, listOf(id))
    }
}
