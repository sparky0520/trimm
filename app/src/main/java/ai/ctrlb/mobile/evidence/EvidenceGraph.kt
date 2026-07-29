package ai.ctrlb.mobile.evidence

import ai.ctrlb.mobile.domain.TimeRange
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
data class AnalyzerIdentity(val name: String, val version: String, val execution: ExecutionLocation)

@Serializable enum class ExecutionLocation { LOCAL_DEVICE, EXTERNAL_SERVICE }

@Serializable
data class EvidenceProvenance(
    val analyzer: AnalyzerIdentity,
    val sourceFingerprint: String,
    val derivedFrom: List<String> = emptyList(),
    val note: String? = null
)

@Serializable
sealed interface EvidenceObservation {
    val id: String
    val sourceFingerprint: String
    val range: TimeRange
    val confidence: Float
    val provenance: EvidenceProvenance
}

@Serializable @SerialName("shot")
data class ShotEvidence(
    override val id: String, override val sourceFingerprint: String,
    override val range: TimeRange, override val confidence: Float,
    override val provenance: EvidenceProvenance,
    val representativeFrameIds: List<String>
) : EvidenceObservation

@Serializable @SerialName("frame")
data class FrameEvidence(
    override val id: String, override val sourceFingerprint: String,
    override val range: TimeRange, override val confidence: Float,
    override val provenance: EvidenceProvenance,
    val artifactPath: String, val width: Int, val height: Int
) : EvidenceObservation

@Serializable @SerialName("visual")
data class VisualEvidence(
    override val id: String, override val sourceFingerprint: String,
    override val range: TimeRange, override val confidence: Float,
    override val provenance: EvidenceProvenance,
    val kind: VisualKind, val value: Float? = null, val text: String? = null,
    val bounds: NormalizedBounds? = null
) : EvidenceObservation

@Serializable enum class VisualKind {
    FACE, SCREEN_UI, PRODUCT, OCR_TEXT, SALIENCE, MOTION, BLUR, SHAKE, EXPOSURE, CROP_SAFETY
}

@Serializable data class NormalizedBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init { require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f && right > left && bottom > top) }
}

@Serializable @SerialName("speech")
data class SpeechEvidence(
    override val id: String, override val sourceFingerprint: String,
    override val range: TimeRange, override val confidence: Float,
    override val provenance: EvidenceProvenance,
    val kind: SpeechKind, val text: String? = null, val speakerId: String? = null
) : EvidenceObservation

@Serializable enum class SpeechKind { WORD, SENTENCE, SPEAKER_REGION, PAUSE, DISFLUENCY }

@Serializable @SerialName("sound")
data class SoundEvidence(
    override val id: String, override val sourceFingerprint: String,
    override val range: TimeRange, override val confidence: Float,
    override val provenance: EvidenceProvenance,
    val kind: SoundKind, val value: Float? = null, val unit: String? = null
) : EvidenceObservation

@Serializable enum class SoundKind {
    LOUDNESS, NOISE_FLOOR, SPEECH_LEVEL, SNR, CLIPPING, MUSIC, SILENCE, TRANSIENT,
    MEASUREMENT_UNCERTAINTY
}

@Serializable
data class AnalyzerRun(
    val analyzer: AnalyzerIdentity,
    val status: AnalyzerStatus,
    val startedAtEpochMs: Long,
    val completedAtEpochMs: Long,
    val observationCount: Int,
    val safeError: String? = null
)

@Serializable enum class AnalyzerStatus { COMPLETE, FAILED, UNSUPPORTED, CACHED }

@Serializable
data class EvidenceGraph(
    val schemaVersion: Int = 1,
    val sourceFingerprint: String,
    val sourceDurationMs: Long,
    val createdAtEpochMs: Long,
    val observations: List<EvidenceObservation>,
    val analyzerRuns: List<AnalyzerRun>,
    val disclosure: EvidenceDisclosure
) {
    init {
        require(schemaVersion == 1)
        require(sourceDurationMs > 0)
        require(observations.map { it.id }.distinct().size == observations.size)
        observations.forEach {
            require(it.sourceFingerprint == sourceFingerprint)
            require(it.range.endMs <= sourceDurationMs)
            require(it.confidence in 0f..1f)
        }
    }
}

@Serializable
data class EvidenceDisclosure(
    val computedLocally: List<String>,
    val sentExternally: List<ExternalDisclosure>
)

@Serializable data class ExternalDisclosure(val service: String, val data: String, val purpose: String)

fun stableEvidenceId(sourceFingerprint: String, analyzer: AnalyzerIdentity, kind: String, range: TimeRange, discriminator: String = ""): String {
    val bytes = "$sourceFingerprint|${analyzer.name}|${analyzer.version}|$kind|${range.startMs}|${range.endMs}|$discriminator".toByteArray()
    return MessageDigest.getInstance("SHA-256").digest(bytes).take(16).joinToString("") { "%02x".format(it) }
}
