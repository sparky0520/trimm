package ai.ctrlb.mobile.evidence

import ai.ctrlb.mobile.domain.TimeRange
import kotlinx.serialization.Serializable
import java.security.MessageDigest
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
    private val nearTieDelta: Float = .04f
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
            val total = .18f * take.completeness + .16f * take.clarity + .16f * take.delivery +
                .14f * take.technicalQuality + .12f * take.continuity + .18f * take.storyFit -
                .06f * take.editRisk
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
        val nearTie = scores.size > 1 && scores[0].total - scores[1].total <= nearTieDelta
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
        val repeatedWording = aligned >= wordingThreshold && sameSpeaker != false
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
