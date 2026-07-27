package ai.ctrlb.mobile.domain

import ai.ctrlb.mobile.evidence.NormalizedBounds
import ai.ctrlb.mobile.evidence.VisualKind
import org.junit.Assert.*
import org.junit.Test

class SemanticVisualLanguageTest {
    private val source = SourceMedia("content://private/video", "hash", 20_000, 1920, 1080)
    private val subject = GroundedVisualSubject(
        "visual-evidence-1", VisualKind.PRODUCT,
        NormalizedBounds(.35f, .25f, .65f, .7f), .88f
    )

    @Test fun `all semantic intents are supported by the render operation`() {
        SemanticIntent.entries.forEach { intent ->
            val operation = ApplySemanticVisual(
                intent = intent, treatment = SemanticTreatment.CALLOUT,
                range = TimeRange(1_000, 2_000), subject = subject
            )
            assertTrue(OperationValidator.isRendered(operation))
            OperationValidator.validate(MobileProject(title = "Test", source = source), listOf(operation))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `semantic treatments reject ungrounded motion evidence`() {
        val ungrounded = subject.copy(kind = VisualKind.MOTION)
        OperationValidator.validate(
            MobileProject(title = "Test", source = source),
            listOf(ApplySemanticVisual(
                intent = SemanticIntent.EMPHASIZE, treatment = SemanticTreatment.SPOTLIGHT,
                range = TimeRange(1_000, 2_000), subject = ungrounded
            ))
        )
    }

    @Test fun `unsafe portrait text reframing asks for help`() {
        val wideText = GroundedVisualSubject(
            "ocr-1", VisualKind.OCR_TEXT,
            NormalizedBounds(.05f, .3f, .95f, .55f), .92f
        )
        val decision = SemanticVisualPolicy.reframe(wideText, outputPortrait = true)
        assertTrue(decision.needsHelp)
        assertNull(decision.framing)
        assertTrue(decision.reason!!.contains("preserve"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `motion density is bounded`() {
        val operations = (0..3).map { index ->
            ApplySemanticVisual(
                intent = SemanticIntent.POINT_OUT, treatment = SemanticTreatment.CALLOUT,
                range = TimeRange(index * 2_000L, index * 2_000L + 700), subject = subject
            )
        }
        OperationValidator.validate(MobileProject(title = "Test", source = source), operations)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `reduced motion requires a static hold`() {
        val operation = ApplySemanticVisual(
            intent = SemanticIntent.RECAP, treatment = SemanticTreatment.CALLOUT,
            range = TimeRange(1_000, 2_000), subject = subject,
            motion = SemanticMotion(reducedMotion = true)
        )
        OperationValidator.validate(MobileProject(title = "Test", source = source), listOf(operation))
    }

    @Test fun `semantic operations participate in preview export fingerprint`() {
        val operation = ApplySemanticVisual(
            intent = SemanticIntent.REVEAL, treatment = SemanticTreatment.SPOTLIGHT,
            range = TimeRange(1_000, 2_000), subject = subject
        )
        assertNotEquals(
            OperationValidator.renderFingerprint(emptyList()),
            OperationValidator.renderFingerprint(listOf(operation))
        )
    }
}
