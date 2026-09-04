package ai.trimm.mobile.domain

import ai.trimm.mobile.evidence.StoryMaterialStatus
import ai.trimm.mobile.evidence.TakeGroup
import kotlin.math.roundToLong

data class TakeComparison(
    val groupId: String,
    val sentence: String,
    val selectedTakeId: String?,
    val alternatives: List<String>,
    val explanation: String,
    val confidence: Float
)

data class EditorialRead(
    val likelyStory: List<String>,
    val repeatedTakes: List<TakeComparison>,
    val technicalProblems: List<String>,
    val usefulProductShots: List<String>,
    val uncertainty: List<String>
)

data class ProposalReview(
    val summary: String,
    val beforeStory: List<String>,
    val afterStory: List<String>,
    val selectedTakes: List<TakeComparison>,
    val removals: List<String>,
    val beforeDurationMs: Long,
    val afterDurationMs: Long,
    val evidenceIds: List<String>,
    val assumptions: List<String>,
    val warnings: List<String>
)

object EditorialReviewBuilder {
    fun editorialRead(project: MobileProject): EditorialRead {
        val graph = project.analysis.storyGraph
        val groups = project.analysis.takeGroups
        return EditorialRead(
            likelyStory = graph?.beats.orEmpty().map { beat ->
                "${beat.role.replaceFirstChar(Char::uppercase)} — ${beat.claims.firstOrNull()?.text ?: "Unresolved material"}"
            },
            repeatedTakes = groups.map(::comparison),
            technicalProblems = buildList {
                addAll(project.analysis.warnings)
            }.distinct(),
            usefulProductShots = graph?.beats.orEmpty()
                .filter { it.role == "demonstration" || it.supportingVisualEvidenceIds.isNotEmpty() }
                .map { beat -> "${beat.claims.firstOrNull()?.text ?: beat.role} — ${beat.supportingVisualEvidenceIds.size} supporting visual cue(s)" },
            uncertainty = buildList {
                (project.analysis.speechHints + project.analysis.lowConfidenceFumbles).distinctBy { it.range to it.reason }.forEach {
                    val surrounding = listOf(it.contextBefore, it.context, it.contextAfter).filter(String::isNotBlank).joinToString(" | ")
                    add("${it.reason} at ${formatRange(it.range)} (${percent(it.confidence)} measurement confidence)${if (surrounding.isBlank()) "" else ": $surrounding"}")
                }
                graph?.beats.orEmpty().filter { it.status == StoryMaterialStatus.CONFLICTING || it.confidence < .7f }
                    .forEach { add("${it.role.replaceFirstChar(Char::uppercase)} is ${percent(it.confidence)} confident.") }
                groups.filter { it.alternativeTakeIds.isNotEmpty() }.forEach { add("The repeated sentence “${it.candidates.firstOrNull()?.transcript.orEmpty()}” has viable alternatives.") }
                graph?.missingMaterial.orEmpty().forEach { add(it.description) }
            }.distinct()
        )
    }

    fun proposal(project: MobileProject): ProposalReview? {
        val proposal = project.analysis.pendingProposal ?: return null
        val graph = project.analysis.storyGraph
        val takeById = project.analysis.takeGroups.flatMap { group -> group.candidates.map { it.id to group } }.toMap()
        val selected = proposal.plan.story.mapNotNull { choice -> choice.selectedTakeId?.let(takeById::get) }.distinctBy { it.id }.map(::comparison)
        val removedRanges = proposal.operations.filterIsInstance<RemoveRanges>().filter { it.enabled }.flatMap { it.ranges }
        val removedMs = mergeDuration(removedRanges)
        val warnings = buildList {
            if (proposal.expectedRevision != project.revision) add("This proposal targets revision ${proposal.expectedRevision}; the project is now revision ${project.revision}.")
            addAll(project.analysis.warnings)
            proposal.plan.story.filter { it.confidence < .7f }.forEach { add("Low confidence: ${it.rationale}") }
            proposal.plan.decisions.filter { it.confidence < .7f }.forEach { add("Low confidence: ${it.rationale}") }
        }.distinct()
        return ProposalReview(
            summary = proposal.summary,
            beforeStory = graph?.beats.orEmpty().map { it.role.replaceFirstChar(Char::uppercase) },
            afterStory = proposal.plan.story.map { choice ->
                graph?.beats?.firstOrNull { it.id == choice.beatId }?.role?.replaceFirstChar(Char::uppercase) ?: choice.beatId
            },
            selectedTakes = selected,
            removals = proposal.operations.filterIsInstance<RemoveRanges>().flatMap { operation ->
                operation.ranges.map { range -> "${operation.reason.ifBlank { "Range removal" }} — ${formatRange(range)}" }
            },
            beforeDurationMs = project.durationMs,
            afterDurationMs = (project.durationMs - removedMs).coerceAtLeast(0),
            evidenceIds = proposal.evidenceIds.distinct(),
            assumptions = (proposal.plan.story.flatMap { it.assumptions } +
                proposal.plan.decisions.flatMap { it.assumptions } +
                proposal.plan.operationPlans.flatMap { it.assumptions }).distinct(),
            warnings = warnings
        )
    }

    private fun comparison(group: TakeGroup) = TakeComparison(
        groupId = group.id,
        sentence = group.candidates.firstOrNull()?.transcript.orEmpty(),
        selectedTakeId = group.preferredTakeId,
        alternatives = group.alternativeTakeIds,
        explanation = group.explanation.joinToString(" "),
        confidence = group.confidence
    )

    private fun mergeDuration(ranges: List<TimeRange>): Long {
        if (ranges.isEmpty()) return 0
        val sorted = ranges.sortedBy { it.startMs }
        var start = sorted.first().startMs
        var end = sorted.first().endMs
        var total = 0L
        sorted.drop(1).forEach {
            if (it.startMs <= end) end = maxOf(end, it.endMs)
            else { total += end - start; start = it.startMs; end = it.endMs }
        }
        return total + end - start
    }

    private fun percent(value: Float) = "${(value * 100).roundToLong()}%"
    private fun formatRange(range: TimeRange) = "${formatTime(range.startMs)}–${formatTime(range.endMs)}"
    private fun formatTime(ms: Long) = "%d:%02d".format(ms / 60_000, (ms / 1_000) % 60)
}
