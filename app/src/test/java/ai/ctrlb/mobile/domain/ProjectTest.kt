package ai.ctrlb.mobile.domain

import org.junit.Assert.*
import org.junit.Test

class ProjectTest {
    private val source = SourceMedia("content://private/video", "hash", 10_000, 1920, 1080)
    @Test fun `operation changes are exactly undoable`() {
        val project = MobileProject(title = "Test", source = source)
        val changed = project.replaceOperations(listOf(SetAudioCleanup()))
        assertEquals(1, changed.operations.size)
        assertEquals(project.operations, changed.undoOnce().operations)
    }
    @Test(expected = IllegalArgumentException::class) fun `rejects stale planner revisions`() {
        OperationValidator.validate(MobileProject(title = "Test", source = source, revision = 2), listOf(SetAudioCleanup()), 1)
    }
}
