package ai.trimm.mobile.evidence

import ai.trimm.mobile.domain.TimeRange
import kotlinx.serialization.Serializable

@Serializable enum class RetrievedEvidenceType { STORY_BEAT, TRANSCRIPT, FRAME, VISUAL, AUDIO_SUMMARY, SHOT }

@Serializable
data class EvidenceRetrievalBudget(
    val storyBeats: Int = 12, val transcriptSpans: Int = 48, val frames: Int = 8,
    val visualObservations: Int = 24, val audioSummaries: Int = 24, val shots: Int = 12,
    val total: Int = 96
) {
    init { listOf(storyBeats, transcriptSpans, frames, visualObservations, audioSummaries, shots, total).forEach { require(it in 0..256) } }
}

@Serializable
data class EvidenceRetrievalQuery(
    val request: String,
    val evidenceTypes: Set<RetrievedEvidenceType> = RetrievedEvidenceType.entries.toSet(),
    val sourceIds: Set<String> = emptySet(),
    val ranges: List<TimeRange> = emptyList(),
    val excludeEvidenceIds: Set<String> = emptySet(),
    val budget: EvidenceRetrievalBudget = EvidenceRetrievalBudget()
) {
    init { require(request.length <= 2_000 && sourceIds.size <= 100 && ranges.size <= 32 && excludeEvidenceIds.size <= 512) }
}

@Serializable
data class RetrievedEvidence(
    val id: String, val type: RetrievedEvidenceType, val sourceId: String, val range: TimeRange,
    val confidence: Float, val summary: String, val derivedFromEvidenceIds: List<String> = emptyList()
) {
    init { require(id.isNotBlank() && sourceId.isNotBlank() && confidence in 0f..1f && summary.length <= 1_000 && derivedFromEvidenceIds.size <= 64) }
}

@Serializable
data class EvidencePacket(
    val schemaVersion: Int = 1,
    val evidenceFingerprint: String,
    val items: List<RetrievedEvidence>,
    val exhaustedTypes: Set<RetrievedEvidenceType>,
    val omittedCounts: Map<RetrievedEvidenceType, Int>
) {
    init { require(schemaVersion == 1 && evidenceFingerprint.isNotBlank() && items.map { it.id }.distinct().size == items.size) }
}

/** Returns compact summaries; source fingerprints and frame artifact paths remain local. */
object BoundedEvidenceRetriever {
    fun retrieve(graphs: List<EvidenceGraph>, storyGraph: StoryGraph, query: EvidenceRetrievalQuery): EvidencePacket {
        val sourceIds = graphs.mapIndexed { index, graph -> graph.sourceFingerprint to "source-${index + 1}" }.toMap()
        val tokens = query.request.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.toSet()
        fun allowed(sourceId: String, range: TimeRange) =
            (query.sourceIds.isEmpty() || sourceId in query.sourceIds) &&
                (query.ranges.isEmpty() || query.ranges.any { it.startMs < range.endMs && range.startMs < it.endMs })
        fun score(item: RetrievedEvidence) = tokens.count { item.summary.lowercase().contains(it) } * 10 + (item.confidence * 9).toInt()
        val candidates = mutableListOf<RetrievedEvidence>()
        if (RetrievedEvidenceType.STORY_BEAT in query.evidenceTypes) storyGraph.beats.forEach { beat ->
            beat.candidateRanges.forEach { candidate ->
                val sourceId = sourceIds[candidate.sourceFingerprint] ?: return@forEach
                if (allowed(sourceId, candidate.range)) candidates += RetrievedEvidence(
                    beat.id, RetrievedEvidenceType.STORY_BEAT, sourceId, candidate.range, beat.confidence,
                    "${beat.role}: ${beat.claims.joinToString(" ") { it.text }}".take(1_000), beat.evidenceIds.sorted())
            }
        }
        graphs.forEach { graph ->
            val sourceId = sourceIds.getValue(graph.sourceFingerprint)
            graph.observations.forEach observation@{ observation ->
                if (!allowed(sourceId, observation.range)) return@observation
                val item = when (observation) {
                    is SpeechEvidence -> if (observation.kind == SpeechKind.SENTENCE && RetrievedEvidenceType.TRANSCRIPT in query.evidenceTypes)
                        RetrievedEvidence(observation.id, RetrievedEvidenceType.TRANSCRIPT, sourceId, observation.range, observation.confidence, observation.text.orEmpty().take(1_000), observation.provenance.derivedFrom) else null
                    is FrameEvidence -> if (RetrievedEvidenceType.FRAME in query.evidenceTypes)
                        RetrievedEvidence(observation.id, RetrievedEvidenceType.FRAME, sourceId, observation.range, observation.confidence, "Representative frame ${observation.width}x${observation.height}.", observation.provenance.derivedFrom) else null
                    is VisualEvidence -> if (RetrievedEvidenceType.VISUAL in query.evidenceTypes)
                        RetrievedEvidence(observation.id, RetrievedEvidenceType.VISUAL, sourceId, observation.range, observation.confidence, "${observation.kind.name.lowercase()}: ${observation.text ?: observation.value ?: "observed"}", observation.provenance.derivedFrom) else null
                    is SoundEvidence -> if (RetrievedEvidenceType.AUDIO_SUMMARY in query.evidenceTypes)
                        RetrievedEvidence(observation.id, RetrievedEvidenceType.AUDIO_SUMMARY, sourceId, observation.range, observation.confidence, "${observation.kind.name.lowercase()}: ${observation.value ?: "observed"} ${observation.unit.orEmpty()}".trim(), observation.provenance.derivedFrom) else null
                    is ShotEvidence -> if (RetrievedEvidenceType.SHOT in query.evidenceTypes)
                        RetrievedEvidence(observation.id, RetrievedEvidenceType.SHOT, sourceId, observation.range, observation.confidence, "Shot with ${observation.representativeFrameIds.size} representative frame(s).", observation.representativeFrameIds) else null
                }
                if (item != null) candidates += item
            }
        }
        val limits = mapOf(
            RetrievedEvidenceType.STORY_BEAT to query.budget.storyBeats, RetrievedEvidenceType.TRANSCRIPT to query.budget.transcriptSpans,
            RetrievedEvidenceType.FRAME to query.budget.frames, RetrievedEvidenceType.VISUAL to query.budget.visualObservations,
            RetrievedEvidenceType.AUDIO_SUMMARY to query.budget.audioSummaries, RetrievedEvidenceType.SHOT to query.budget.shots)
        val eligible = candidates.filterNot { it.id in query.excludeEvidenceIds }
        val selected = eligible.groupBy { it.type }.flatMap { (type, items) ->
            items.sortedWith(compareByDescending<RetrievedEvidence>(::score).thenBy { it.sourceId }.thenBy { it.range.startMs }.thenBy { it.id }).take(limits.getValue(type))
        }.sortedWith(compareBy<RetrievedEvidence> { it.sourceId }.thenBy { it.range.startMs }.thenBy { it.id }).take(query.budget.total)
        val selectedCounts = selected.groupingBy { it.type }.eachCount()
        val eligibleCounts = eligible.groupingBy { it.type }.eachCount()
        return EvidencePacket(evidenceFingerprint = storyGraph.evidenceFingerprint, items = selected,
            exhaustedTypes = query.evidenceTypes.filterTo(mutableSetOf()) { selectedCounts.getOrDefault(it, 0) == eligibleCounts.getOrDefault(it, 0) },
            omittedCounts = query.evidenceTypes.associateWith { (eligibleCounts.getOrDefault(it, 0) - selectedCounts.getOrDefault(it, 0)).coerceAtLeast(0) })
    }

    fun validateReferences(packet: EvidencePacket, evidenceIds: Collection<String>) {
        val valid = packet.items.mapTo(mutableSetOf()) { it.id }
        require(evidenceIds.isNotEmpty() && evidenceIds.all(valid::contains)) { "Planner returned missing, stale, or invented evidence." }
    }
}
