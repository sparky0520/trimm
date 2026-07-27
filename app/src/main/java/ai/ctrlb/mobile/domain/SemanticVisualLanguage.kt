package ai.ctrlb.mobile.domain

import ai.ctrlb.mobile.evidence.NormalizedBounds
import ai.ctrlb.mobile.evidence.VisualKind
import kotlinx.serialization.Serializable

@Serializable enum class SemanticIntent { INTRODUCE, POINT_OUT, TRANSITION, REVEAL, EMPHASIZE, RECAP }
@Serializable enum class SemanticTreatment { CALLOUT, SPOTLIGHT, REFRAME }
@Serializable enum class MotionCurve { HOLD, EASE_IN_OUT }

@Serializable data class GroundedVisualSubject(
    val evidenceId: String,
    val kind: VisualKind,
    val bounds: NormalizedBounds,
    val confidence: Float
)

@Serializable data class SemanticMotion(
    val intensity: Float = .35f,
    val curve: MotionCurve = MotionCurve.EASE_IN_OUT,
    val reducedMotion: Boolean = false
)

@Serializable data class SemanticFraming(
    val zoom: Float,
    val preservesSubject: Boolean,
    val preservesText: Boolean = true
)

@Serializable data class ReframeDecision(
    val framing: SemanticFraming?,
    val needsHelp: Boolean,
    val reason: String? = null
)

object SemanticVisualPolicy {
    val groundedKinds = setOf(
        VisualKind.FACE, VisualKind.SCREEN_UI, VisualKind.PRODUCT,
        VisualKind.OCR_TEXT, VisualKind.SALIENCE
    )
    const val maximumSimultaneousTreatments = 2
    const val maximumTreatmentsPerTenSeconds = 3

    fun validate(operation: ApplySemanticVisual, durationMs: Long) {
        require(operation.range.endMs <= durationMs)
        require(operation.range.durationMs in 300..8_000)
        require(operation.subject.evidenceId.isNotBlank())
        require(operation.subject.kind in groundedKinds)
        require(operation.subject.confidence in .55f..1f)
        require(operation.motion.intensity in 0f..0.7f)
        if (operation.motion.reducedMotion) require(operation.motion.curve == MotionCurve.HOLD)
        if (operation.treatment == SemanticTreatment.REFRAME) {
            val framing = requireNotNull(operation.framing) { "Reframing requires a safe framing decision" }
            require(framing.zoom in 1f..2.25f)
            require(framing.preservesSubject) { "Reframing would lose the grounded subject" }
            require(framing.preservesText) { "Reframing would crop important text" }
        } else require(operation.framing == null)
    }

    fun validateDensity(operations: List<ApplySemanticVisual>) {
        operations.sortedBy { it.range.startMs }.forEachIndexed { index, operation ->
            val overlapping = operations.count {
                it.range.startMs < operation.range.endMs && operation.range.startMs < it.range.endMs
            }
            require(overlapping <= maximumSimultaneousTreatments) { "Too many overlapping semantic treatments" }
            val windowCount = operations.sortedBy { it.range.startMs }.drop(index).count {
                it.range.startMs < operation.range.startMs + 10_000
            }
            require(windowCount <= maximumTreatmentsPerTenSeconds) { "Semantic motion density exceeds the budget" }
        }
    }

    fun reframe(subject: GroundedVisualSubject, outputPortrait: Boolean): ReframeDecision {
        require(subject.kind in groundedKinds)
        if (subject.confidence < .65f) return ReframeDecision(null, true, "Subject confidence is too low.")
        val bounds = subject.bounds
        val text = subject.kind == VisualKind.OCR_TEXT || subject.kind == VisualKind.SCREEN_UI
        val width = bounds.right - bounds.left
        val height = bounds.bottom - bounds.top
        if (outputPortrait && (width > .72f || (text && width > .58f))) {
            return ReframeDecision(null, true, "Portrait framing cannot preserve the important subject or text.")
        }
        val zoom = if (outputPortrait) (1.45f / maxOf(width, height)).coerceIn(1f, 2.25f) else 1.15f
        return ReframeDecision(SemanticFraming(zoom, preservesSubject = true, preservesText = true), false)
    }
}
