package ai.ctrlb.mobile.domain

import ai.ctrlb.mobile.evidence.*
import org.junit.Assert.*
import org.junit.Test

class EditorialReviewTest {
    private val source = SourceMedia("content://video", "source", 10_000, 1920, 1080)

    @Test fun `proposal review exposes structure duration assumptions evidence and warnings`() {
        val beat = StoryBeat("beat-1", "hook", listOf(StoryCandidateRange("source", TimeRange(0, 2_000), setOf("sentence-1"))),
            listOf(StoryClaim("A useful opening", setOf("sentence-1"), .9f)), setOf("visual-1"), .8f, emptyList(), .9f, StoryMaterialStatus.OPTIONAL, setOf("sentence-1", "visual-1"))
        val plan = StagedEditorialPlan(
            story = listOf(StoryPlanChoice("beat-1", null, "Lead clearly", listOf("sentence-1"), .9f, listOf("The hook stands alone"), "Faster opening")),
            decisions = emptyList(),
            operationPlans = emptyList()
        )
        val proposal = PendingEditorialProposal(0, "Tight opening", plan,
            listOf(RemoveRanges(ranges = listOf(TimeRange(2_000, 3_000), TimeRange(2_500, 4_000)), confidence = .95f, reason = "Repeated start")),
            listOf("sentence-1", "visual-1"))
        val project = MobileProject(title = "Review", source = source, analysis = Analysis(
            storyGraph = StoryGraph(evidenceFingerprint = "v1", beats = listOf(beat), constraints = emptyList(), missingMaterial = emptyList(), editorialRead = emptyList()),
            pendingProposal = proposal
        ))

        val review = EditorialReviewBuilder.proposal(project)
        assertNotNull(review)
        requireNotNull(review)
        assertEquals(listOf("Hook"), review.beforeStory)
        assertEquals(listOf("Hook"), review.afterStory)
        assertEquals(8_000, review.afterDurationMs)
        assertEquals(listOf("The hook stands alone"), review.assumptions)
        assertEquals(listOf("sentence-1", "visual-1"), review.evidenceIds)
    }

    @Test fun `rejection can clear review without mutating timeline or revision`() {
        val plan = StagedEditorialPlan(emptyList(), emptyList(), emptyList())
        val project = MobileProject(title = "Review", source = source, operations = listOf(SetAudioLevels()),
            analysis = Analysis(pendingProposal = PendingEditorialProposal(0, "Cut", plan, emptyList(), emptyList())))
        val rejected = project.copy(analysis = project.analysis.copy(pendingProposal = null))
        assertEquals(project.operations, rejected.operations)
        assertEquals(project.revision, rejected.revision)
        assertEquals(project.undo, rejected.undo)
    }


    @Test fun `atomic apply creates one checkpoint and undo restores exact operations`() {
        val before = listOf<EditInstruction>(SetAudioLevels(id = "before"))
        val after = listOf<EditInstruction>(SetAudioLevels(id = "after"))
        val plan = StagedEditorialPlan(emptyList(), emptyList(), emptyList())
        val pending = PendingEditorialProposal(0, "Cut", plan, after, emptyList())
        val project = MobileProject(title = "Review", source = source, operations = before, analysis = Analysis(pendingProposal = pending))
        val applied = project.replaceEditInstructions(InstructionValidator.validate(project, after, 0))
            .copy(analysis = project.analysis.copy(editorialPlan = plan, pendingProposal = null))
        assertEquals(1, applied.revision)
        assertEquals(1, applied.undo.size)
        val undone = applied.restoreCheckpoint()
        assertEquals(before, undone.operations)
        assertEquals(project.analysis, undone.analysis)
    }
}
