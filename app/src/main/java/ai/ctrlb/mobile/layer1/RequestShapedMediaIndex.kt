package ai.ctrlb.mobile.layer1

import ai.ctrlb.mobile.domain.TimeRange
import ai.ctrlb.mobile.evidence.*
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

object RequestShapedMediaIndexBuilder {
    fun build(graphs: List<EvidenceGraph>, requirements: MediaIndexRequirements): RequestShapedMediaIndex {
        require(graphs.isNotEmpty())
        require(requirements.maximumSelections > 0)
        val bySource = graphs.associateBy { it.sourceFingerprint }
        val requested = requirements.requestedRanges + requirements.candidateRanges + requirements.uncertaintyRanges
        requested.forEach {
            val duration = requireNotNull(bySource[it.sourceId]) { "Unknown source ${it.sourceId}" }.sourceDurationMs
            require(it.range.startMs >= 0 && it.range.endMs <= duration)
        }
        val representative = if (requirements.scope == MediaIndexScope.BROAD_EDITORIAL) {
            representativeRanges(graphs, (requirements.maximumSelections - requested.size).coerceAtLeast(graphs.size))
        } else emptyList()
        val selections = (requested + representative).take(requirements.maximumSelections).mapIndexed { index, item ->
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
