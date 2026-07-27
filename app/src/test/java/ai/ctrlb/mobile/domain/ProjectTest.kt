package ai.ctrlb.mobile.domain

import org.junit.Assert.*
import org.junit.Test

class ProjectTest {
    private val source = SourceMedia("content://private/video", "hash", 10_000, 1920, 1080)
    @Test fun `new projects do not require an audio catalog`() {
        assertEquals(MusicChoice.NONE, WizardPreferences().music)
        assertTrue(MobileProject(title = "Test", source = source).operations.none { it is SetBackgroundMusic || it is AddSoundEffect })
    }
    @Test fun `operation changes are exactly undoable`() {
        val project = MobileProject(title = "Test", source = source)
        val changed = project.replaceOperations(listOf(SetAudioCleanup()))
        assertEquals(1, changed.operations.size)
        assertEquals(project.operations, changed.undoOnce().operations)
    }
    @Test(expected = IllegalArgumentException::class) fun `rejects stale planner revisions`() {
        OperationValidator.validate(MobileProject(title = "Test", source = source, revision = 2), listOf(SetAudioCleanup()), 1)
    }
    @Test fun `legacy unrendered operations disappear from operation cards`() {
        val operations = listOf(
            AddTransition(atSourceMs = 1_000),
            AddMotionPreset(range = TimeRange(0, 1_000), preset = "legacy"),
            SetAudioCleanup()
        )
        assertEquals(listOf(operations.last()), OperationValidator.visible(operations))
    }
    @Test fun `every enabled mobile operation changes the documented render fixture`() {
        val fixture = listOf<EditOperation>(
            RemoveFumbles(ranges = listOf(TimeRange(100, 200)), confidence = .96f, reasons = listOf("immediate-retake")),
            SetColorGrade(grade = ColorGrade(exposure = .1f)),
            SetAudioCleanup(strength = .4f),
            SetAudioLevels(dialogueLufs = -16f),
            AddSoundEffect(atSourceMs = 500, catalogId = "fixture-effect"),
            SetBackgroundMusic(catalogId = "fixture-music"),
            AddCaptions(cues = listOf(CaptionCue("Fixture", TimeRange(0, 500)))),
            ApplySemanticVisual(
                intent = SemanticIntent.POINT_OUT,
                treatment = SemanticTreatment.CALLOUT,
                range = TimeRange(500, 1_500),
                subject = GroundedVisualSubject(
                    "fixture-product",
                    ai.ctrlb.mobile.evidence.VisualKind.PRODUCT,
                    ai.ctrlb.mobile.evidence.NormalizedBounds(.3f, .2f, .7f, .8f),
                    .9f
                )
            )
        )
        val baseline = OperationValidator.renderFingerprint(emptyList())
        fixture.forEach { operation ->
            assertNotEquals(operation.toString(), baseline, OperationValidator.renderFingerprint(listOf(operation)))
        }
    }
    @Test fun `cards disclose evidence preference or default basis`() {
        val operations = listOf<EditOperation>(
            RemoveFumbles(ranges = listOf(TimeRange(100, 200)), confidence = .96f, reasons = listOf("retake")),
            SetAudioCleanup(),
            SetBackgroundMusic(catalogId = "chosen-track")
        )
        assertTrue(OperationValidator.basis(operations[0]).startsWith("Transcript evidence"))
        assertTrue(OperationValidator.basis(operations[1]).startsWith("Default"))
        assertTrue(OperationValidator.basis(operations[2]).startsWith("User preference"))
    }
    @Test(expected = IllegalArgumentException::class) fun `planner cannot enable color without frame evidence`() {
        OperationValidator.validate(MobileProject(title = "Test", source = source), listOf(SetColorGrade(grade = ColorGrade())))
    }
}
