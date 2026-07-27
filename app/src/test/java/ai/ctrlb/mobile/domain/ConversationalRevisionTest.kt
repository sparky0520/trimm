package ai.ctrlb.mobile.domain

import ai.ctrlb.mobile.evidence.NormalizedBounds
import ai.ctrlb.mobile.evidence.VisualKind
import org.junit.Assert.*
import org.junit.Test

class ConversationalRevisionTest {
    private val source = SourceMedia("content://video", "source", 20_000, 1920, 1080)
    private val plan = StagedEditorialPlan(
        story = emptyList(),
        decisions = listOf(PlannedEditorialDecision(
            "effects", EditorialDecisionStage.OPTIONAL_TREATMENT, EditorialDecisionKind.OPTIONAL_STYLE,
            "The product evidence supports restrained emphasis.", listOf("visual-1"), .9f,
            expectedStoryEffect = "Makes the demonstration easier to follow."
        )),
        operationPlans = listOf(
            PlannedOperation("early", "effects", "Emphasize the opening product.", listOf("visual-1"), .9f,
                expectedStoryEffect = "Clear opening."),
            PlannedOperation("late", "effects", "Emphasize the later product.", listOf("visual-2"), .8f,
                expectedStoryEffect = "Clear demonstration.")
        )
    )
    private val early = effect("early", 1_000, "visual-1", .9f)
    private val late = effect("late", 8_000, "visual-2", .8f)

    @Test fun `why returns persisted evidence and rationale without replacing the proposal`() {
        val project = project()
        val result = ConversationalRevisionEngine.revise(project, "Why did you add these effects?")
        assertTrue(result is ConversationalRevisionResult.Answer)
        result as ConversationalRevisionResult.Answer
        assertTrue(result.text.contains("restrained emphasis"))
        assertEquals(listOf("visual-1", "visual-2"), result.evidenceIds)
        assertEquals(project.analysis.pendingProposal, result.project.analysis.pendingProposal)
        assertEquals(1, result.project.analysis.revisionConversation.size)
    }

    @Test fun `named follow ups revise only their scope and persist settled constraints`() {
        val faster = ConversationalRevisionEngine.revise(project(), "Open faster") as ConversationalRevisionResult.Revision
        assertEquals(listOf("late"), faster.proposal.operations.map { it.id })
        assertEquals(EditorialConstraintKind.OPENING_PACE, faster.project.analysis.settledConstraints.single().kind)

        val fewer = ConversationalRevisionEngine.revise(project(), "Use fewer effects") as ConversationalRevisionResult.Revision
        assertEquals(listOf("early"), fewer.proposal.operations.map { it.id })
        assertEquals(EditorialConstraintKind.EFFECT_DENSITY, fewer.project.analysis.settledConstraints.single().kind)

        val privateProject = project(early.copy(subject = early.subject.copy(kind = VisualKind.FACE)), late)
        val privacy = ConversationalRevisionEngine.revise(privateProject, "Keep privacy") as ConversationalRevisionResult.Revision
        assertEquals(listOf("late"), privacy.proposal.operations.map { it.id })
        assertEquals("preserve", privacy.project.analysis.settledConstraints.single().value)
    }

    @Test fun `revised proposal applies once and undo restores the exact prior project`() {
        val original = project()
        val revised = (ConversationalRevisionEngine.revise(original, "Use fewer effects") as ConversationalRevisionResult.Revision).project
        val proposal = requireNotNull(revised.analysis.pendingProposal)
        val applied = revised.replaceOperations(OperationValidator.validate(revised, proposal.operations, proposal.expectedRevision))
            .copy(analysis = revised.analysis.copy(editorialPlan = proposal.plan, pendingProposal = null))
        assertEquals(listOf("early"), applied.operations.map { it.id })
        val undone = applied.undoOnce()
        assertEquals(original.operations, undone.operations)
        assertEquals(revised.analysis, undone.analysis)
    }

    private fun project(vararg effects: ApplySemanticVisual): MobileProject {
        val operations = effects.toList().ifEmpty { listOf(early, late) }
        return MobileProject(
            title = "Conversation", source = source, operations = listOf(SetAudioCleanup(id = "existing")),
            analysis = Analysis(pendingProposal = PendingEditorialProposal(
                0, "Grounded emphasis", plan, operations, listOf("visual-1", "visual-2")
            ))
        )
    }

    private fun effect(id: String, start: Long, evidenceId: String, confidence: Float) = ApplySemanticVisual(
        id = id, intent = SemanticIntent.EMPHASIZE, treatment = SemanticTreatment.SPOTLIGHT,
        range = TimeRange(start, start + 1_000),
        subject = GroundedVisualSubject(evidenceId, VisualKind.PRODUCT, NormalizedBounds(.2f, .2f, .8f, .8f), confidence)
    )
}
