package ai.ctrlb.mobile.media

import ai.ctrlb.mobile.domain.SourceMedia
import ai.ctrlb.mobile.layer1.NormalizedRect
import ai.ctrlb.mobile.planner.AuthorizedEvidenceJob
import ai.ctrlb.mobile.planner.EvidenceRange
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

class SelectiveDerivativePreparerTest {
    private val source = SourceMedia(
        uri = "content://selected/video", durationMs = 60_000,
        width = 1920, height = 1080, hasAudio = true, fingerprint = "a".repeat(64)
    )
    private val now = Instant.parse("2026-07-29T12:00:00Z")

    @Test fun `prepares every selective type with exact source mapping and integrity`() = runTest {
        val root = Files.createTempDirectory("derivatives").toFile()
        val writes = mutableListOf<String>()
        val preparer = SelectiveDerivativePreparer(root, DerivativeWriter { type, _, range, crop, format, destination ->
            writes += "$type:${range.startMs}-${range.endMs}:$crop:$format"
            destination.writeBytes(ByteArray(256) { it.toByte() })
        }, { now })
        val authorizations = listOf(
            authorization("audio", "audio/mp4", 1_000, 4_000),
            authorization("frame", "image/jpeg", 5_000, 5_001),
            authorization("crop", "image/png", 6_000, 6_001, NormalizedRect(.1f, .2f, .5f, .4f)),
            authorization("audiovisual", "video/mp4", 8_000, 12_000)
        )

        val prepared = authorizations.map { preparer.prepare(it, listOf(source)) }

        assertEquals(4, prepared.size)
        assertEquals(listOf(1_000L, 5_000L, 6_000L, 8_000L), prepared.map { it.sourceRange.startMs })
        assertTrue(prepared.all { it.sourceFingerprint == source.fingerprint && it.byteLength == 256L })
        assertTrue(prepared.all { it.sha256.matches(Regex("[a-f0-9]{64}")) })
        assertTrue(prepared.all { java.io.File(it.localPath).isFile })
        assertTrue(writes.single { it.startsWith("CROP") }.contains("NormalizedRect"))
    }

    @Test fun `rejects unauthorized sources ranges formats budgets expiry and implicit whole source`() = runTest {
        val root = Files.createTempDirectory("derivative-policy").toFile()
        val preparer = SelectiveDerivativePreparer(root, DerivativeWriter { _, _, _, _, _, destination ->
            destination.writeBytes(ByteArray(32))
        }, { now })
        suspend fun rejected(auth: DerivativeAuthorization, sources: List<SourceMedia> = listOf(source)) {
            assertThrows(DerivativePreparationException::class.java) {
                kotlinx.coroutines.runBlocking { preparer.prepare(auth, sources) }
            }
        }

        rejected(authorization("audio", "audio/mp4", 0, 1_000), emptyList())
        rejected(authorization("audio", "image/jpeg", 0, 1_000))
        rejected(authorization("audio", "audio/mp4", 59_000, 61_000))
        rejected(authorization("audio", "audio/mp4", 0, 2_000, maximumDurationMs = 1_000))
        rejected(authorization("crop", "image/png", 1_000, 1_001))
        rejected(authorization("audiovisual", "video/mp4", 0, 60_000))
        rejected(authorization("audio", "audio/mp4", 0, 1_000, expiresAt = "2026-07-29T11:59:59Z"))
    }

    @Test fun `explicit proxy opt-in is required and byte overflow or cancellation cleans partial files`() = runTest {
        val root = Files.createTempDirectory("derivative-cleanup").toFile()
        val oversized = SelectiveDerivativePreparer(root, DerivativeWriter { _, _, _, _, _, destination ->
            destination.writeBytes(ByteArray(2_048))
        }, { now })
        val wholeSource = authorization("proxy", "video/mp4", 0, 60_000, maximumBytes = 1_024)
            .copy(wholeSourceProxyOptIn = true)
        assertThrows(DerivativePreparationException::class.java) {
            kotlinx.coroutines.runBlocking { oversized.prepare(wholeSource, listOf(source)) }
        }
        assertFalse(root.walkTopDown().any { it.isFile })

        val canceled = SelectiveDerivativePreparer(root, DerivativeWriter { _, _, _, _, _, destination ->
            destination.writeBytes(ByteArray(100))
        }, { now })
        var checks = 0
        assertThrows(DerivativePreparationException::class.java) {
            kotlinx.coroutines.runBlocking {
                canceled.prepare(authorization("audio", "audio/mp4", 0, 1_000), listOf(source)) { ++checks > 1 }
            }
        }
        assertFalse(root.walkTopDown().any { it.isFile })
    }

    @Test fun `expired derivatives are deleted only inside the governed root`() = runTest {
        val root = Files.createTempDirectory("derivative-expiry").toFile()
        val preparer = SelectiveDerivativePreparer(root, DerivativeWriter { _, _, _, _, _, destination ->
            destination.writeBytes(ByteArray(64))
        }, { now })
        val prepared = preparer.prepare(
            authorization("audio", "audio/mp4", 0, 1_000, expiresAt = "2026-07-29T12:00:01Z"),
            listOf(source)
        )
        val cleanup = SelectiveDerivativePreparer(root, DerivativeWriter { _, _, _, _, _, _ -> }, { now.plusSeconds(2) })
        assertEquals(listOf(prepared.jobId), cleanup.cleanupExpired(listOf(prepared)))
        assertFalse(java.io.File(prepared.localPath).exists())
    }

    private fun authorization(
        type: String,
        format: String,
        startMs: Long,
        endMs: Long,
        crop: NormalizedRect? = null,
        maximumDurationMs: Long? = endMs - startMs,
        maximumBytes: Long = 4_096,
        expiresAt: String = "2026-07-29T13:00:00Z"
    ) = DerivativeAuthorization(
        AuthorizedEvidenceJob(
            id = "job-${type}-${startMs}", sourceId = source.fingerprint, type = type,
            purpose = "Answer the requested measurement", acceptedFormat = format,
            range = EvidenceRange(startMs, endMs), maximumBytes = maximumBytes,
            maximumDurationMs = maximumDurationMs, expiresAt = expiresAt, reused = false
        ),
        crop = crop
    )
}
