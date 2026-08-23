package ai.ctrlb.mobile.evidence

import ai.ctrlb.mobile.domain.TimeRange
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max

@Serializable
data class TakeCandidate(
    val id: String,
    val sourceFingerprint: String,
    val range: TimeRange,
    val transcript: String,
    val speakerId: String? = null,
    val evidenceIds: Set<String>,
    val facts: Map<String, String> = emptyMap(),
    val completeness: Float,
    val clarity: Float,
    val delivery: Float,
    val technicalQuality: Float,
    val continuity: Float,
    val storyFit: Float,
    val editRisk: Float
) {
    init {
        require(id.isNotBlank() && transcript.isNotBlank() && evidenceIds.isNotEmpty())
        listOf(completeness, clarity, delivery, technicalQuality, continuity, storyFit, editRisk)
            .forEach { require(it in 0f..1f) }
    }
}

@Serializable
data class TakeConstraints(
    val mustKeepTakeIds: Set<String> = emptySet(),
    val mustKeepWording: List<String> = emptyList(),
    val explicitChoiceByGroup: Map<String, String> = emptyMap()
)

@Serializable
data class TakeSimilarityEvidence(
    val leftTakeId: String,
    val rightTakeId: String,
    val alignedTokenRatio: Float,
    val topicTokenOverlap: Float,
    val sameSpeaker: Boolean?,
    val repeatedWording: Boolean
)

@Serializable
data class FactualDifference(val key: String, val valuesByTakeId: Map<String, String>)

@Serializable
data class TakeScore(
    val takeId: String,
    val completeness: Float,
    val clarity: Float,
    val delivery: Float,
    val technicalQuality: Float,
    val continuity: Float,
    val storyFit: Float,
    val editRisk: Float,
    val total: Float,
    val evidenceIds: Set<String>
)

@Serializable enum class TakeResolution { SELECTED, ALTERNATIVES, CLARIFICATION_REQUIRED }

@Serializable
data class TakeGroup(
    val id: String,
    val candidates: List<TakeCandidate>,
    val similarityEvidence: List<TakeSimilarityEvidence>,
    val factualDifferences: List<FactualDifference>,
    val scores: List<TakeScore>,
    val preferredTakeId: String?,
    val alternativeTakeIds: List<String>,
    val confidence: Float,
    val resolution: TakeResolution,
    val explanation: List<String>
)

class TakeRanker(
    private val wordingThreshold: Float = .72f,
    private val nearTieDelta: Float = .04f,
    private val profile: EditorialProfile? = null
) {
    fun detectAndRank(candidates: List<TakeCandidate>, constraints: TakeConstraints = TakeConstraints()): List<TakeGroup> {
        require(candidates.map { it.id }.distinct().size == candidates.size)
        val similarities = candidates.indices.flatMap { left ->
            (left + 1 until candidates.size).map { right -> compare(candidates[left], candidates[right]) }
        }
        val repeated = similarities.filter { it.repeatedWording }
        val adjacency = candidates.associate { it.id to mutableSetOf<String>() }
        repeated.forEach {
            adjacency.getValue(it.leftTakeId).add(it.rightTakeId)
            adjacency.getValue(it.rightTakeId).add(it.leftTakeId)
        }
        val byId = candidates.associateBy { it.id }
        val visited = mutableSetOf<String>()
        return candidates.mapNotNull { seed ->
            if (seed.id in visited || adjacency.getValue(seed.id).isEmpty()) return@mapNotNull null
            val pending = ArrayDeque(listOf(seed.id))
            val ids = mutableSetOf<String>()
            while (pending.isNotEmpty()) {
                val id = pending.removeFirst()
                if (visited.add(id)) {
                    ids.add(id)
                    adjacency.getValue(id).forEach(pending::addLast)
                }
            }
            rankGroup(ids.map(byId::getValue), similarities.filter { it.leftTakeId in ids && it.rightTakeId in ids }, constraints)
        }
    }

    private fun rankGroup(
        candidates: List<TakeCandidate>,
        similarities: List<TakeSimilarityEvidence>,
        constraints: TakeConstraints
    ): TakeGroup {
        val id = stableGroupId(candidates.map { it.id })
        val differences = candidates.flatMap { it.facts.keys }.distinct().mapNotNull { key ->
            val values = candidates.mapNotNull { take -> take.facts[key]?.let { take.id to it } }.toMap()
            if (values.values.distinct().size > 1) FactualDifference(key, values) else null
        }
        val scores = candidates.map { take ->
            val weights = profile?.policy?.rankingWeights.orEmpty()
            val total = (weights["completeness"] ?: .18f) * take.completeness +
                (weights["clarity"] ?: .16f) * take.clarity +
                (weights["delivery"] ?: .16f) * take.delivery +
                (weights["technicalQuality"] ?: .14f) * take.technicalQuality +
                (weights["continuity"] ?: .12f) * take.continuity +
                (weights["storyFit"] ?: .18f) * take.storyFit +
                (weights["editRisk"] ?: -.06f) * take.editRisk
            TakeScore(take.id, take.completeness, take.clarity, take.delivery, take.technicalQuality,
                take.continuity, take.storyFit, take.editRisk, total.coerceIn(0f, 1f), take.evidenceIds)
        }.sortedByDescending { it.total }
        val explicit = constraints.explicitChoiceByGroup[id]
        require(explicit == null || candidates.any { it.id == explicit }) { "Explicit take choice does not belong to group $id" }
        val wordingKeep = candidates.filter { take ->
            constraints.mustKeepWording.any { required ->
                normalize(take.transcript).contains(normalize(required))
            }
        }.map { it.id }
        val wordingRequired = wordingKeep.isNotEmpty()
        val hardKeep = candidates.filter { it.id in constraints.mustKeepTakeIds }.map { it.id }
        val factualConflict = differences.isNotEmpty()
        val effectiveNearTieDelta = profile?.policy?.nearTieDelta ?: nearTieDelta
        val nearTie = scores.size > 1 && scores[0].total - scores[1].total <= effectiveNearTieDelta
        val constrainedIds = (hardKeep + wordingKeep).distinct()
        val constrainedWinner = scores.firstOrNull { it.takeId in constrainedIds }?.takeId
        val preferred = explicit ?: constrainedWinner ?: scores.first().takeId
        val explicitConstraintConflict = explicit != null && constrainedIds.any { it != explicit }
        val resolution = when {
            explicitConstraintConflict -> TakeResolution.CLARIFICATION_REQUIRED
            explicit != null -> TakeResolution.SELECTED
            hardKeep.size > 1 || (hardKeep.isNotEmpty() && wordingKeep.any { it !in hardKeep }) || factualConflict ->
                TakeResolution.CLARIFICATION_REQUIRED
            nearTie -> TakeResolution.ALTERNATIVES
            else -> TakeResolution.SELECTED
        }
        val confidence = similarities.map { it.alignedTokenRatio }.average().toFloat()
            .times(if (factualConflict || nearTie) .7f else 1f).coerceIn(0f, 1f)
        val explanation = buildList {
            add("Grouped by repeated wording alignment; topic overlap alone is not sufficient.")
            add("Ranking compares completeness, clarity, delivery, technical quality, continuity, story fit, and edit risk.")
            profile?.let {
                add("Adaptive policy ${it.policyVersion} (${it.evidenceFingerprint.take(12)}) used profile-relative thresholds and disclosed versioned ranking weights.")
            }
            if (wordingRequired) add("Must-keep wording is present and cannot be removed from the edit.")
            if (explicit != null) add("The user's explicit take choice overrides automatic ranking.")
            if (explicitConstraintConflict) add("The explicit choice conflicts with other must-keep material, so neither may be deleted automatically.")
            if (factualConflict) add("Factual or product-state differences require clarification before deletion.")
            else if (nearTie) add("The leading takes are a near tie, so alternatives are preserved.")
        }
        return TakeGroup(id, candidates.sortedBy { it.range.startMs }, similarities, differences, scores,
            preferred, scores.map { it.takeId }.filter { it != preferred }, confidence, resolution, explanation)
    }

    private fun compare(left: TakeCandidate, right: TakeCandidate): TakeSimilarityEvidence {
        val a = tokens(left.transcript)
        val b = tokens(right.transcript)
        val aligned = (2f * lcs(a, b) / (a.size + b.size).coerceAtLeast(1))
        val union = (a.toSet() + b.toSet()).size.coerceAtLeast(1)
        val overlap = (a.toSet() intersect b.toSet()).size.toFloat() / union
        val sameSpeaker = if (left.speakerId == null || right.speakerId == null) null else left.speakerId == right.speakerId
        val repeatedWording = aligned >= (profile?.policy?.repeatedWordingThreshold ?: wordingThreshold) && sameSpeaker != false
        return TakeSimilarityEvidence(left.id, right.id, aligned, overlap, sameSpeaker, repeatedWording)
    }

    private fun lcs(a: List<String>, b: List<String>): Int {
        val row = IntArray(b.size + 1)
        a.forEach { token ->
            var diagonal = 0
            b.forEachIndexed { index, other ->
                val prior = row[index + 1]
                row[index + 1] = if (token == other) diagonal + 1 else max(row[index + 1], row[index])
                diagonal = prior
            }
        }
        return row.last()
    }

    private fun tokens(text: String) = normalize(text).split(' ').filter(String::isNotBlank)
    private fun normalize(text: String) = text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    private fun stableGroupId(ids: List<String>): String {
        val bytes = ids.sorted().joinToString("|").toByteArray()
        return "take-" + MessageDigest.getInstance("SHA-256").digest(bytes).take(8).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Builds ranking inputs only from provenance-bearing observations. The generator deliberately
 * does not infer facts: product-state differences must come from a detector or user input that
 * can name and cite the differing state.
 */
object TakeCandidateGenerator {
    fun fromEvidence(graphs: List<EvidenceGraph>): List<TakeCandidate> = graphs.flatMap { graph ->
        val speech = graph.observations.filterIsInstance<SpeechEvidence>()
        speech.filter { it.kind == SpeechKind.SENTENCE && !it.text.isNullOrBlank() }.map { sentence ->
            val overlapping = graph.observations.filter { it.range.overlaps(sentence.range) }
            val visuals = overlapping.filterIsInstance<VisualEvidence>()
            val sounds = overlapping.filterIsInstance<SoundEvidence>()
            val disfluencies = overlapping.count { it is SpeechEvidence && it.kind == SpeechKind.DISFLUENCY }
            val speakers = overlapping.filterIsInstance<SpeechEvidence>()
                .filter { it.kind == SpeechKind.SPEAKER_REGION }.mapNotNull { it.speakerId }.distinct()
            val clarity = (sentence.confidence - sounds.penalty()).coerceIn(0f, 1f)
            val technical = technicalQuality(visuals, sounds)
            val salience = visuals.filter { it.kind == VisualKind.SALIENCE }.mapNotNull { it.value }
                .averageOr(.5f)
            TakeCandidate(
                id = sentence.id,
                sourceFingerprint = graph.sourceFingerprint,
                range = sentence.range,
                transcript = requireNotNull(sentence.text),
                speakerId = speakers.singleOrNull(),
                evidenceIds = overlapping.map { it.id }.toSet(),
                completeness = sentence.confidence,
                clarity = clarity,
                delivery = (clarity - disfluencies * .18f).coerceIn(0f, 1f),
                technicalQuality = technical,
                continuity = continuity(overlapping, sentence.range),
                storyFit = (.7f * sentence.confidence + .3f * salience).coerceIn(0f, 1f),
                editRisk = (1f - minOf(sentence.confidence, clarity, technical)).coerceIn(0f, 1f)
            )
        }
    }

    private fun technicalQuality(visuals: List<VisualEvidence>, sounds: List<SoundEvidence>): Float {
        val exposure = visuals.filter { it.kind == VisualKind.EXPOSURE }.mapNotNull { it.value }
            .map { 1f - (abs(it - .5f) * 2f).coerceIn(0f, 1f) }
        val sharpness = visuals.filter { it.kind == VisualKind.BLUR }.mapNotNull { it.value }
        val stability = visuals.filter { it.kind == VisualKind.SHAKE }.mapNotNull { it.value }
            .map { 1f - it }
        return (listOf(exposure.averageOr(.7f), sharpness.averageOr(.7f), stability.averageOr(.7f))
            .average().toFloat() - sounds.penalty()).coerceIn(0f, 1f)
    }

    private fun continuity(observations: List<EvidenceObservation>, range: TimeRange): Float {
        val shotCount = observations.filterIsInstance<ShotEvidence>().count { it.range.overlaps(range) }
        return when (shotCount) {
            0, 1 -> .85f
            2 -> .65f
            else -> .45f
        }
    }

    private fun List<SoundEvidence>.penalty(): Float {
        val clipping = filter { it.kind == SoundKind.CLIPPING }.mapNotNull { it.value }.maxOrNull() ?: 0f
        val noise = filter { it.kind == SoundKind.NOISE_FLOOR }.mapNotNull { it.value }
            .maxOrNull()?.let { ((it + 60f) / 40f).coerceIn(0f, 1f) } ?: 0f
        return (.45f * clipping + .2f * noise).coerceIn(0f, .65f)
    }

    private fun List<Float>.averageOr(fallback: Float) =
        if (isEmpty()) fallback else average().toFloat()

    private fun TimeRange.overlaps(other: TimeRange) =
        startMs < other.endMs && other.startMs < endMs
}

@Serializable
data class TakeDetectionGroundTruth(
    val groups: Set<Set<String>>,
    val preferredTakeByGroupMembers: Map<String, String>,
    val mustKeepTakeIds: Set<String>
)

@Serializable
data class TakeDetectionMetrics(
    val groupPrecision: Double,
    val groupRecall: Double,
    val preferredTakeAccuracy: Double,
    val destructiveFalsePositives: Int
)

object TakeDetectionEvaluator {
    fun evaluate(groups: List<TakeGroup>, truth: TakeDetectionGroundTruth): TakeDetectionMetrics {
        val predictedPairs = groups.flatMap { pairs(it.candidates.map(TakeCandidate::id)) }.toSet()
        val actualPairs = truth.groups.flatMap { pairs(it.toList()) }.toSet()
        val correctPairs = predictedPairs.intersect(actualPairs).size
        val selections = truth.preferredTakeByGroupMembers.mapNotNull { (members, preferred) ->
            val memberSet = members.split('|').toSet()
            groups.find { it.candidates.map(TakeCandidate::id).toSet() == memberSet }?.preferredTakeId == preferred
        }
        val removed = groups.flatMap { group ->
            if (group.resolution == TakeResolution.SELECTED) group.candidates.map { it.id }.filter { it != group.preferredTakeId } else emptyList()
        }
        return TakeDetectionMetrics(
            if (predictedPairs.isEmpty()) if (actualPairs.isEmpty()) 1.0 else 0.0 else correctPairs.toDouble() / predictedPairs.size,
            if (actualPairs.isEmpty()) 1.0 else correctPairs.toDouble() / actualPairs.size,
            if (selections.isEmpty()) 1.0 else selections.count { it }.toDouble() / selections.size,
            removed.count { it in truth.mustKeepTakeIds }
        )
    }

    private fun pairs(ids: List<String>) = ids.indices.flatMap { left ->
        (left + 1 until ids.size).map { right -> listOf(ids[left], ids[right]).sorted().joinToString("|") }
    }
}
