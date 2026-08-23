package ai.ctrlb.mobile.media

import ai.ctrlb.mobile.domain.SourceMedia
import ai.ctrlb.mobile.planner.AuthorizedEvidenceJob
import ai.ctrlb.mobile.planner.EvidenceRange
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VideoDerivativePreparerTest {
    private val source = SourceMedia("content://video", "a".repeat(64), 60_000, 1920, 1080)
    private fun job(start: Long, end: Long, resolution: String = "LOW") = AuthorizedEvidenceJob(
        "job-1", source.fingerprint, "video", "video-inspection", "video/mp4",
        EvidenceRange(start, end), 1_000_000, end - start, resolution, "",
        "2026-08-01T00:00:00Z", false
    )

    @Test fun `prepares an authorized video window`() = runBlocking {
        val root = Files.createTempDirectory("video-window").toFile()
        val preparer = SelectiveDerivativePreparer(root, DerivativeWriter { type, _, _, _, format, output ->
            assertEquals(SelectiveDerivativeType.AUDIOVISUAL, type)
            assertEquals("video/mp4", format)
            output.writeBytes(ByteArray(16) { 1 })
        }, now = { Instant.parse("2026-07-31T00:00:00Z") })
        val result = preparer.prepare(DerivativeAuthorization(job(0, 15_000)), listOf(source))
        assertEquals(15_000, result.sourceRange.durationMs)
    }

    @Test fun `rejects windows longer than fifteen seconds`() {
        val root = Files.createTempDirectory("video-window-limit").toFile()
        val preparer = SelectiveDerivativePreparer(root, DerivativeWriter { _, _, _, _, _, output -> output.writeBytes(byteArrayOf(1)) },
            now = { Instant.parse("2026-07-31T00:00:00Z") })
        assertThrows(DerivativePreparationException::class.java) {
            runBlocking { preparer.prepare(DerivativeAuthorization(job(0, 15_001)), listOf(source)) }
        }
    }
}
