package ai.ctrlb.mobile.domain

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable enum class EditorialConstraintKind {
    TAKE_SELECTION, OPENING_PACE, PRIVACY, EFFECT_DENSITY
}

@Serializable data class SettledEditorialConstraint(
    val kind: EditorialConstraintKind,
    val value: String,
    val sourceTurnId: String
)

@Serializable data class EditorialConversationTurn(
    val id: String = UUID.randomUUID().toString(),
    val request: String,
    val response: String,
    val evidenceIds: List<String> = emptyList(),
    val isPending: Boolean = false,
    val isError: Boolean = false,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

sealed interface ConversationalRevisionResult {
    data class Answer(val project: MobileProject, val text: String, val evidenceIds: List<String>) : ConversationalRevisionResult
    data class Revision(val project: MobileProject, val proposal: PendingEditorialProposal) : ConversationalRevisionResult
    data class Clarification(val project: MobileProject, val question: String) : ConversationalRevisionResult
}

/**
 * Deterministic local interpretation for high-value follow-ups. It never creates timing or
 * evidence: revisions only select known takes or remove/disable operations already in the current
 * proposal. A cloud planner may handle broader language against the same persisted constraints.
 */
object ConversationalRevisionEngine {
    fun revise(project: MobileProject, request: String): ConversationalRevisionResult {
        val text = request.trim()
        require(text.isNotEmpty() && text.length <= 2_000)
        val normalized = text.lowercase()
        return when {
            normalized.startsWith("why") || "why did" in normalized ->
                answerWhy(project, text)
            "other take" in normalized ->
                useOtherTake(project, text)

            else -> clarification(project, text, "What part of the current story should I revise?")
        }
    }

    private fun answerWhy(project: MobileProject, request: String): ConversationalRevisionResult {
        val proposal = project.analysis.pendingProposal
        val plan = proposal?.plan ?: project.analysis.editorialPlan
        val explanations = buildList {
            plan?.story.orEmpty().forEach { add(it.rationale) }
            plan?.decisions.orEmpty().forEach { add(it.rationale) }
            plan?.operationPlans.orEmpty().forEach { add(it.rationale) }
        }.distinct()
        val evidence = buildList {
            addAll(proposal?.evidenceIds.orEmpty())
            plan?.story.orEmpty().forEach { addAll(it.evidenceIds) }
            plan?.decisions.orEmpty().forEach { addAll(it.evidenceIds) }
            plan?.operationPlans.orEmpty().forEach { addAll(it.evidenceIds) }
        }.distinct()
        val response = explanations.takeIf { it.isNotEmpty() }?.joinToString(" ") ?:
            "There is no recorded editorial decision to explain yet."
        val updated = appendTurn(project, request, response, evidence)
        return ConversationalRevisionResult.Answer(updated, response, evidence)
    }

    private fun useOtherTake(project: MobileProject, request: String): ConversationalRevisionResult {
        val current = project.analysis.pendingProposal
            ?: return clarification(project, request, "There is no current proposal to revise.")
        val choices = current.plan.story.map { choice ->
            val group = project.analysis.takeGroups.firstOrNull { group ->
                group.candidates.any { it.id == choice.selectedTakeId }
            }
            val other = group?.alternativeTakeIds?.firstOrNull { it != choice.selectedTakeId }
            if (other == null) choice else choice.copy(
                selectedTakeId = other,
                rationale = "The user explicitly requested the other known take.",
                evidenceIds = group.candidates.first { it.id == other }.evidenceIds.toList(),
                confidence = 1f,
                assumptions = emptyList(),
                expectedStoryEffect = "Keeps the same story beat while changing only its delivery."
            )
        }
        if (choices == current.plan.story) {
            return clarification(project, request, "Which story beat should use another take?")
        }
        val selected = choices.mapNotNull { it.selectedTakeId }.toSet()
        val alternateRanges = project.analysis.takeGroups.flatMap { group ->
            group.candidates.filter { it.id !in selected }.map { it.range }
        }
        val operations = current.operations.map { operation ->
            if (operation is RemoveRanges) operation.copy(
                ranges = (operation.ranges + alternateRanges).distinct().sortedBy { it.startMs },
                reason = "Superseded alternate take"
            ) else operation
        }
        val constraint = SettledEditorialConstraint(
            EditorialConstraintKind.TAKE_SELECTION, selected.sorted().joinToString(","), ""
        )
        return replaceProposal(project, request, current.copy(
            expectedRevision = project.revision,
            summary = "Use the other take without changing the rest of the story",
            plan = current.plan.copy(story = choices),
            operations = operations,
            evidenceIds = (current.evidenceIds + choices.flatMap { it.evidenceIds }).distinct()
        ), constraint)
    }

    private fun scopedRevision(
        project: MobileProject,
        request: String,
        kind: EditorialConstraintKind,
        value: String,
        transform: (List<EditOperation>) -> List<EditOperation>
    ): ConversationalRevisionResult {
        val current = project.analysis.pendingProposal
            ?: return clarification(project, request, "There is no current proposal to revise.")
        val operations = transform(current.operations)
        val proposal = current.copy(
            expectedRevision = project.revision,
            summary = when (kind) {
                EditorialConstraintKind.OPENING_PACE -> "Open faster while preserving the settled story"
                EditorialConstraintKind.PRIVACY -> "Preserve privacy throughout the current story"
                EditorialConstraintKind.EFFECT_DENSITY -> "Use fewer effects without changing story timing"
                EditorialConstraintKind.TAKE_SELECTION -> current.summary
            },
            operations = operations,
            plan = current.plan.copy(
                operationPlans = current.plan.operationPlans.filter { planned ->
                    operations.any { it.id == planned.operationId }
                }
            )
        )
        return replaceProposal(project, request, proposal, SettledEditorialConstraint(kind, value, ""))
    }

    private fun replaceProposal(
        project: MobileProject,
        request: String,
        proposal: PendingEditorialProposal,
        constraint: SettledEditorialConstraint
    ): ConversationalRevisionResult.Revision {
        val turn = EditorialConversationTurn(
            request = request, response = proposal.summary, evidenceIds = proposal.evidenceIds
        )
        val settled = project.analysis.settledConstraints
            .filterNot { it.kind == constraint.kind } + constraint.copy(sourceTurnId = turn.id)
        val updated = project.copy(analysis = project.analysis.copy(
            pendingProposal = proposal,
            revisionConversation = (project.analysis.revisionConversation + turn).takeLast(100),
            settledConstraints = settled
        ))
        return ConversationalRevisionResult.Revision(updated, proposal)
    }

    private fun clarification(project: MobileProject, request: String, question: String): ConversationalRevisionResult.Clarification {
        val updated = appendTurn(project, request, question, emptyList())
        return ConversationalRevisionResult.Clarification(updated, question)
    }

    private fun appendTurn(project: MobileProject, request: String, response: String, evidenceIds: List<String>): MobileProject =
        project.copy(analysis = project.analysis.copy(
            revisionConversation = (project.analysis.revisionConversation +
                EditorialConversationTurn(request = request, response = response, evidenceIds = evidenceIds)).takeLast(100)
        ))
}
