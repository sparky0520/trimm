package ai.ctrlb.mobile.domain

import org.junit.Assert.*
import org.junit.Test

class ProjectTest {
    private val source = SourceMedia("content://private/video", "hash", 10_000, 1920, 1080)
    @Test fun `new projects do not require an audio catalog`() {
        assertEquals(MusicChoice.NONE, WizardPreferences().music)
        assertTrue(MobileProject(title = "Test", source = source).operations.isEmpty())
    }
    @Test fun `operation changes are exactly undoable`() {
        val project = MobileProject(title = "Test", source = source)
        val changed = project.replaceEditOperations(listOf(SetAudioLevels()))
        assertEquals(1, changed.operations.size)
        assertEquals(project.operations, changed.restoreCheckpoint().operations)
    }
    @Test(expected = IllegalArgumentException::class) fun `rejects stale planner revisions`() {
        OperationValidator.validate(MobileProject(title = "Test", source = source, revision = 2), listOf(SetAudioLevels()), 1)
    }
    @Test fun `legacy unrendered operations disappear from operation cards`() {
        val operations = listOf(
            AddMotionPreset(range = TimeRange(0, 1_000), preset = "legacy"),
            SetAudioLevels()
        )
        assertEquals(listOf(operations.last()), OperationValidator.visible(operations))
    }
    @Test fun `every enabled mobile operation changes the documented render fixture`() {
        val fixture = listOf<EditOperation>(
            RemoveRanges(ranges = listOf(TimeRange(100, 200)), confidence = .96f, reason = "immediate-retake"),
            SetColorGrade(grade = ColorGrade(exposure = .1f)),
            SetAudioLevels(dialogueLufs = -16f),
            AddCaptions(cues = listOf(CaptionCue("Fixture", TimeRange(0, 500))))
        )
        val baseline = OperationValidator.renderFingerprint(emptyList())
        fixture.forEach { operation ->
            assertNotEquals(operation.toString(), baseline, OperationValidator.renderFingerprint(listOf(operation)))
        }
    }
    @Test fun `cards disclose evidence preference or default basis`() {
        val operations = listOf<EditOperation>(
            RemoveRanges(ranges = listOf(TimeRange(100, 200)), confidence = .96f, reason = "retake"),
            SetAudioLevels()
        )
        assertTrue(OperationValidator.basis(operations[0]).startsWith("Transcript evidence"))
        assertTrue(OperationValidator.basis(operations[1]).startsWith("Default"))
    }
    @Test fun `cloud-grounded color is available to the rendered proposal contract`() {
        val color = SetColorGrade(grade = ColorGrade())
        assertEquals(listOf(color), OperationValidator.validate(MobileProject(title = "Test", source = source), listOf(color)))
    }
    @Test fun `fine operations preserve exact source targets and reject ranges outside the source`() {
        val project = MobileProject(title = "Test", source = source)
        val cleanup = SetAudioLevels(target = OperationTarget("hash", 1_000, 4_000))
        val exposure = SetColorGrade(target = OperationTarget("hash", 4_000, 6_000), parameter = "exposure", value = .15f)
        assertEquals(listOf(cleanup, exposure), OperationValidator.validate(project, listOf(cleanup, exposure)))
        assertThrows(IllegalArgumentException::class.java) {
            OperationValidator.validate(project, listOf(cleanup.copy(target = OperationTarget("hash", 9_000, 11_000))))
        }
    }
}
