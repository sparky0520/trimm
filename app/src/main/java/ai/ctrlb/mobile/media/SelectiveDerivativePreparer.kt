package ai.ctrlb.mobile.media

import ai.ctrlb.mobile.domain.SourceMedia
import ai.ctrlb.mobile.domain.TimeRange
import ai.ctrlb.mobile.layer1.NormalizedRect
import ai.ctrlb.mobile.planner.AuthorizedEvidenceJob
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest
import java.time.Instant

@Serializable
enum class SelectiveDerivativeType { AUDIO, FRAME, CROP, AUDIOVISUAL }

data class DerivativeAuthorization(val job: AuthorizedEvidenceJob)

@Serializable
data class PreparedDerivative(
    val jobId: String,
    val sourceId: String,
    val sourceFingerprint: String,
    val type: SelectiveDerivativeType,
    val purpose: String,
    val sourceRange: TimeRange,
    val acceptedFormat: String,
    val byteLength: Long,
    val sha256: String,
    val localPath: String,
    val createdAt: String,
    val expiresAt: String
)

fun interface DerivativeWriter {
    suspend fun write(
        type: SelectiveDerivativeType,
        source: SourceMedia,
        range: TimeRange,
        crop: NormalizedRect?,
        acceptedFormat: String,
        destination: File
    )
}

class DerivativePreparationException(message: String) : IllegalArgumentException(message)

class SelectiveDerivativePreparer(
    private val root: File,
    private val writer: DerivativeWriter,
    private val now: () -> Instant = Instant::now
) {
    init { root.mkdirs() }

    suspend fun prepare(
        authorization: DerivativeAuthorization,
        sources: List<SourceMedia>,
        isCancelled: () -> Boolean = { false }
    ): PreparedDerivative {
        val job = authorization.job
        val current = now()
        val expiry = runCatching { Instant.parse(job.expiresAt) }
            .getOrElse { throw DerivativePreparationException("Evidence expiry is invalid.") }
        requireSafeId(job.id)
        if (!expiry.isAfter(current)) throw DerivativePreparationException("Evidence request has expired.")
        if (job.type != "video" || job.purpose != "video-inspection") throw DerivativePreparationException("Only video inspection jobs are supported.")
        if (job.maximumBytes <= 0) throw DerivativePreparationException("Evidence byte budget must be positive.")
        val source = sources.singleOrNull { it.fingerprint == job.sourceId }
            ?: throw DerivativePreparationException("Evidence source is not selected.")
        val range = TimeRange(job.range.startMs, job.range.endMs)
        if (range.endMs > source.durationMs) throw DerivativePreparationException("Evidence range exceeds the source.")
        if (range.durationMs > 15_000 || job.maximumDurationMs == null || range.durationMs > job.maximumDurationMs) {
            throw DerivativePreparationException("Evidence range exceeds its duration budget.")
        }
        val type = type(job.type)
        validateFormat(type, job.acceptedFormat)
        if (isCancelled()) throw DerivativePreparationException("Evidence preparation was cancelled.")

        val jobRoot = File(root, job.id)
        val extension = extension(job.acceptedFormat)
        val partial = File(jobRoot, "derivative.$extension.partial")
        val completed = File(jobRoot, "derivative.$extension")
        jobRoot.mkdirs()
        return try {
            partial.delete()
            writer.write(type, source, range, null, job.acceptedFormat, partial)
            if (isCancelled()) throw DerivativePreparationException("Evidence preparation was cancelled.")
            if (!partial.isFile || partial.length() <= 0) {
                throw DerivativePreparationException("Derivative writer produced no media.")
            }
            if (partial.length() > job.maximumBytes) {
                throw DerivativePreparationException("Prepared derivative exceeds its byte budget.")
            }
            if (completed.exists()) completed.delete()
            if (!partial.renameTo(completed)) throw DerivativePreparationException("Could not finalize derivative.")
            PreparedDerivative(
                jobId = job.id,
                sourceId = job.sourceId,
                sourceFingerprint = source.fingerprint,
                type = type,
                purpose = job.purpose,
                sourceRange = range,
                acceptedFormat = job.acceptedFormat,
                byteLength = completed.length(),
                sha256 = sha256(completed),
                localPath = completed.canonicalPath,
                createdAt = current.toString(),
                expiresAt = expiry.toString()
            )
        } catch (error: Throwable) {
            partial.delete()
            completed.delete()
            if (jobRoot.listFiles().isNullOrEmpty()) jobRoot.delete()
            throw error
        }
    }

    fun cleanupExpired(manifests: Collection<PreparedDerivative>): List<String> {
        val current = now()
        return manifests.filter { !Instant.parse(it.expiresAt).isAfter(current) }.map { derivative ->
            val file = File(derivative.localPath)
            val contained = runCatching { file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()) }.getOrDefault(false)
            if (contained) {
                file.delete()
                file.parentFile?.takeIf { it.listFiles().isNullOrEmpty() }?.delete()
            }
            derivative.jobId
        }
    }

    private fun type(value: String) = if (value == "video") SelectiveDerivativeType.AUDIOVISUAL
        else throw DerivativePreparationException("Unsupported evidence type.")

    private fun validateFormat(type: SelectiveDerivativeType, value: String) {
        val allowed = when (type) {
            // AndroidDerivativeWriter produces an MP4 audio derivative. Reject
            // other otherwise-valid audio MIME types before any media work.
            SelectiveDerivativeType.AUDIO -> setOf("audio/mp4")
            SelectiveDerivativeType.FRAME, SelectiveDerivativeType.CROP -> setOf("image/jpeg", "image/png", "image/webp")
            SelectiveDerivativeType.AUDIOVISUAL -> setOf("video/mp4")
        }
        if (value !in allowed) throw DerivativePreparationException("Evidence format is not accepted for $type.")
    }

    private fun extension(format: String) = when (format) {
        "audio/mp4", "video/mp4" -> "mp4"
        "audio/aac" -> "aac"
        "audio/wav" -> "wav"
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "bin"
    }

    private fun requireSafeId(value: String) {
        if (!value.matches(Regex("^[A-Za-z0-9_-]{1,100}$"))) {
            throw DerivativePreparationException("Evidence job id is unsafe.")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
