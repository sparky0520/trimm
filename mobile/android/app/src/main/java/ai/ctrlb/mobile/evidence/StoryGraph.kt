package ai.ctrlb.mobile.evidence

import ai.ctrlb.mobile.domain.TimeRange
import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
enum class StoryMaterialStatus { ESSENTIAL, OPTIONAL, REPETITIVE, CONFLICTING }

@Serializable
data class StoryClaim(
    val text: String,
    val evidenceIds: Set<String>,
    val confidence: Float
)

@Serializable
data class StoryCandidateRange(
    val sourceFingerprint: String,
    val range: TimeRange,
    val evidenceIds: Set<String>
)

@Serializable
data class StoryBeat(
    val id: String,
    val role: String,
    val candidateRanges: List<StoryCandidateRange>,
    val claims: List<StoryClaim>,
    val supportingVisualEvidenceIds: Set<String>,
    val importance: Float,
    val dependencyBeatIds: List<String>,
    val confidence: Float,
    val status: StoryMaterialStatus,
    val evidenceIds: Set<String>
)

@Serializable
data class StoryConstraint(
    val text: String,
    val matchedBeatIds: List<String>,
    val satisfied: Boolean
)

@Serializable
data class MissingStoryMaterial(val description: String, val requiredByConstraint: String)

@Serializable
data class StoryGraph(
    val schemaVersion: Int = 1,
    val policyVersion: String = "story-graph-v1",
    val evidenceFingerprint: String,
    val beats: List<StoryBeat>,
    val constraints: List<StoryConstraint>,
    val missingMaterial: List<MissingStoryMaterial>,
    val editorialRead: List<String>
)

/**
 * Produces a conservative local story read. Roles are flexible labels inferred only from cited
 * speech; absence of a conventional role is never reported as a defect.
 */
object StoryGraphDeriver {
    private val roleCues = linkedMapOf(
        "call to action" to listOf("subscribe", "follow", "comment", "try it", "download", "get started", "let me know"),
        "proof" to listOf("result", "worked", "tested", "before and after", "as you can see"),
        "demonstration" to listOf("tap", "click", "open", "select", "turn on", "here is", "watch", "show you"),
        "problem" to listOf("problem", "struggle", "difficult", "can't", "cannot", "issue", "frustrat"),
        "conclusion" to listOf("in summary", "that's it", "finally", "overall", "to recap"),
        "context" to listOf("because", "today", "when", "if you", "for anyone", "we are"),
        "hook" to listOf("how to", "did you know", "want to", "here's why", "stop", "best way")
    )

    fun derive(
        graphs: List<EvidenceGraph>,
        takeGroups: List<TakeGroup>,
        mustKeep: String
    ): StoryGraph {
        val sentences = graphs.flatMap { graph ->
            graph.observations.filterIsInstance<SpeechEvidence>()
                .filter { it.kind == SpeechKind.SENTENCE && !it.text.isNullOrBlank() }
                .map { graph to it }
        }
        val takeByCandidate = takeGroups.flatMap { group -> group.candidates.map { it.id to group } }.toMap()
        val normalizedMustKeep = normalize(mustKeep)

        val draft = sentences.mapIndexed { index, (graph, sentence) ->
            val text = requireNotNull(sentence.text)
            val normalized = normalize(text)
            val group = takeByCandidate[sentence.id]
            val preferred = group?.preferredTakeId
            val mustKeepMatch = normalizedMustKeep.isNotBlank() && normalized.contains(normalizedMustKeep)
            val status = when {
                mustKeepMatch -> StoryMaterialStatus.ESSENTIAL
                group?.resolution == TakeResolution.CLARIFICATION_REQUIRED -> StoryMaterialStatus.CONFLICTING
                group != null && preferred != null && preferred != sentence.id -> StoryMaterialStatus.REPETITIVE
                else -> StoryMaterialStatus.OPTIONAL
            }
            val overlapping = graph.observations.filter {
                it.id != sentence.id && it.range.startMs < sentence.range.endMs && sentence.range.startMs < it.range.endMs
            }
            val visuals = overlapping.filter { it is FrameEvidence || it is VisualEvidence }
            val role = classify(normalized, index, sentences.size)
            val evidenceIds = (listOf(sentence.id) + overlapping.map { it.id }).toSet()
            StoryBeat(
                id = stableId(graph.sourceFingerprint, sentence.id, role),
                role = role,
                candidateRanges = listOf(StoryCandidateRange(graph.sourceFingerprint, sentence.range, evidenceIds)),
                claims = listOf(StoryClaim(text, setOf(sentence.id), sentence.confidence)),
                supportingVisualEvidenceIds = visuals.map { it.id }.toSet(),
                importance = when {
                    mustKeepMatch -> 1f
                    role == "call to action" || role == "hook" -> .8f
                    else -> (.35f + sentence.confidence * .4f).coerceIn(0f, 1f)
                },
                dependencyBeatIds = emptyList(),
                confidence = sentence.confidence,
                status = status,
                evidenceIds = evidenceIds
            )
        }
        val beats = draft.mapIndexed { index, beat ->
            beat.copy(dependencyBeatIds = if (index == 0) emptyList() else listOf(draft[index - 1].id))
        }
        val matched = if (normalizedMustKeep.isBlank()) emptyList() else beats.filter { beat ->
            beat.claims.any { normalize(it.text).contains(normalizedMustKeep) }
        }.map { it.id }
        val constraints = if (normalizedMustKeep.isBlank()) emptyList() else
            listOf(StoryConstraint(mustKeep.trim(), matched, matched.isNotEmpty()))
        val missing = constraints.filterNot { it.satisfied }
            .map { MissingStoryMaterial("No cited transcript range contains the must-keep material.", it.text) }
        val read = buildList {
            if (beats.isEmpty()) add("No evidence-backed spoken story could be identified.")
            else {
                add("Found ${beats.size} candidate story ${if (beats.size == 1) "beat" else "beats"} across ${graphs.size} ${if (graphs.size == 1) "source" else "sources"}.")
                beats.groupingBy { it.role }.eachCount().forEach { (role, count) ->
                    add("${role.replaceFirstChar(Char::uppercase)}: $count candidate${if (count == 1) "" else "s"}.")
                }
                val uncertain = beats.count { it.status == StoryMaterialStatus.CONFLICTING }
                val repeated = beats.count { it.status == StoryMaterialStatus.REPETITIVE }
                if (repeated > 0) add("$repeated repeated candidate${if (repeated == 1) "" else "s"} can be compared before removal.")
                if (uncertain > 0) add("$uncertain conflicting candidate${if (uncertain == 1) "" else "s"} require clarification.")
            }
            constraints.forEach { add(if (it.satisfied) "Must keep: “${it.text}” is represented." else "Missing must-keep material: “${it.text}”.") }
            add("Conventional beat types that are absent are not treated as required.")
        }
        return StoryGraph(
            evidenceFingerprint = fingerprint(graphs, takeGroups, mustKeep),
            beats = beats,
            constraints = constraints,
            missingMaterial = missing,
            editorialRead = read
        )
    }

    private fun classify(text: String, index: Int, count: Int): String =
        roleCues.entries.firstOrNull { (_, cues) -> cues.any(text::contains) }?.key
            ?: when {
                index == 0 && count > 1 -> "opening"
                index == count - 1 && count > 1 -> "closing"
                else -> "story point"
            }

    private fun normalize(value: String) =
        value.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun stableId(source: String, sentenceId: String, role: String) =
        "beat-" + sha("$source|$sentenceId|$role").take(16)

    private fun fingerprint(graphs: List<EvidenceGraph>, groups: List<TakeGroup>, mustKeep: String) =
        sha((graphs.sortedBy { it.sourceFingerprint }.joinToString("|") { graph ->
            "${graph.sourceFingerprint}:${graph.observations.joinToString(",") { it.id }}"
        }) + "|" + groups.sortedBy { it.id }.joinToString(",") { "${it.id}:${it.preferredTakeId}:${it.resolution}" } +
            "|" + normalize(mustKeep))

    private fun sha(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
