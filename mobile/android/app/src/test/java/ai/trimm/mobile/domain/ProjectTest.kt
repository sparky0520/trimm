package ai.trimm.mobile.domain

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
        val changed = project.replaceEditInstructions(listOf(SetAudioLevels()))
        assertEquals(1, changed.operations.size)
        assertEquals(project.operations, changed.restoreCheckpoint().operations)
    }
    @Test(expected = IllegalArgumentException::class) fun `rejects stale planner revisions`() {
        InstructionValidator.validate(MobileProject(title = "Test", source = source, revision = 2), listOf(SetAudioLevels()), 1)
    }
    @Test fun `legacy unrendered operations disappear from operation cards`() {
        val operations = listOf(
            AddMotionPreset(range = TimeRange(0, 1_000), preset = "legacy"),
            SetAudioLevels()
        )
        assertEquals(listOf(operations.last()), InstructionValidator.visible(operations))
    }
    @Test fun `every enabled mobile operation changes the documented render fixture`() {
        val fixture = listOf<EditInstruction>(
            RemoveRanges(ranges = listOf(TimeRange(100, 200)), confidence = .96f, reason = "immediate-retake"),
            SetColorGrade(grade = ColorGrade(exposure = .1f)),
            SetAudioLevels(dialogueLufs = -16f),
            AddCaptions(cues = listOf(CaptionCue("Fixture", TimeRange(0, 500))))
        )
        val baseline = InstructionValidator.renderFingerprint(emptyList())
        fixture.forEach { operation ->
            assertNotEquals(operation.toString(), baseline, InstructionValidator.renderFingerprint(listOf(operation)))
        }
    }
    @Test fun `cards disclose evidence preference or default basis`() {
        val operations = listOf<EditInstruction>(
            RemoveRanges(ranges = listOf(TimeRange(100, 200)), confidence = .96f, reason = "retake"),
            SetAudioLevels()
        )
        assertTrue(InstructionValidator.basis(operations[0]).startsWith("Transcript evidence"))
        assertTrue(InstructionValidator.basis(operations[1]).startsWith("Default"))
    }
    @Test fun `cloud-grounded color is available to the rendered proposal contract`() {
        val color = SetColorGrade(grade = ColorGrade())
        assertEquals(listOf(color), InstructionValidator.validate(MobileProject(title = "Test", source = source), listOf(color)))
    }
    @Test fun `fine operations preserve exact source targets and reject ranges outside the source`() {
        val project = MobileProject(title = "Test", source = source)
        val cleanup = SetAudioLevels(target = OperationTarget("hash", 1_000, 4_000))
        val exposure = SetColorGrade(target = OperationTarget("hash", 4_000, 6_000), parameter = "exposure", value = .15f)
        assertEquals(listOf(cleanup, exposure), InstructionValidator.validate(project, listOf(cleanup, exposure)))
        assertThrows(IllegalArgumentException::class.java) {
            InstructionValidator.validate(project, listOf(cleanup.copy(target = OperationTarget("hash", 9_000, 11_000))))
        }
    }

    @Test fun `mobile project with schema serializes and deserializes properly`() {
        val schema = CompositionSchema(
            timeline = listOf(TimelineNode.Video(src = "test.mp4", startMs = 0, endMs = 1000))
        )
        val snapshot = ProjectSnapshot(schema = schema)
        val project = MobileProject(title = "Test Project", source = source, undo = listOf(snapshot))
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
        val encoded = json.encodeToString(MobileProject.serializer(), project)
        val decoded = json.decodeFromString(MobileProject.serializer(), encoded)
        assertEquals(project.title, decoded.title)
    }
}
