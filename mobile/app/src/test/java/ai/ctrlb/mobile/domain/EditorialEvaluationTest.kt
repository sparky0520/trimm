package ai.ctrlb.mobile.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorialEvaluationTest {
    private val fixtureRoot by lazy {
        val text = checkNotNull(javaClass.classLoader?.getResourceAsStream(
            "editorial/mobile-editorial-fixtures.v1.json"
        )) { "Missing representative editorial fixture" }.bufferedReader().use { it.readText() }
        Json.parseToJsonElement(text).jsonObject
    }

    @Test fun `fixture is real multi-clip redistributable editorial material`() {
        assertEquals(1, fixtureRoot.getValue("schemaVersion").jsonPrimitive.content.toInt())
        val sources = fixtureRoot.getValue("sources").jsonArray
        val clips = fixtureRoot.getValue("clips").jsonArray
        assertTrue(sources.size >= 2)
        assertTrue(clips.size >= 5)
        sources.forEach {
            val source = it.jsonObject
            assertTrue(source.getValue("sourcePage").jsonPrimitive.content.startsWith("https://commons.wikimedia.org/"))
            assertTrue(source.getValue("license").jsonPrimitive.content.startsWith("CC "))
            assertEquals(64, source.getValue("sha256").jsonPrimitive.content.length)
        }
        val features = clips.flatMap { clip ->
            clip.jsonObject.getValue("features").jsonArray.map { it.jsonPrimitive.content }
        }.toSet()
        listOf("repeated-take", "preferred-take", "product-demonstration", "clear-cta",
            "handheld-shake", "screen-glare", "missing-production-audio").forEach {
            assertTrue("missing representative feature $it", it in features)
        }
        assertFalse(fixtureRoot.getValue("redistribution").jsonObject
            .getValue("preparedMediaCommitted").jsonPrimitive.content.toBoolean())
    }

    @Test fun `ground truth covers every editorial acceptance dimension`() {
        val truth = fixtureRoot.getValue("groundTruth").jsonObject
        listOf("takeGroups", "shots", "storyBeats", "mustKeepClaims", "safeRemovals",
            "supportingVisuals", "audioProblems", "technicalDefects").forEach {
            assertTrue("$it must not be empty", truth.getValue(it).jsonArray.isNotEmpty())
        }
        val group = truth.getValue("takeGroups").jsonArray.single().jsonObject
        assertTrue(group.getValue("candidateClipIds").jsonArray.size >= 2)
        assertTrue(group.getValue("preferredClipId").jsonPrimitive.content.isNotBlank())
        assertTrue(group.getValue("rationale").jsonPrimitive.content.isNotBlank())
    }

    @Test fun `evaluation records destructive take grounding parity and latency metrics`() {
        val truth = EditorialGroundTruth(
            preferredTakeByGroup = mapOf("water-bottle-line" to "take-b"),
            mustKeepEvidenceIds = setOf("take-b", "cta"),
            safeRemovalEvidenceIds = setOf("take-a", "take-c"),
            validEvidenceIds = setOf("take-a", "take-b", "take-c", "product-demo", "cta")
        )
        val record = EditorialEvaluator.evaluate(
            fixtureVersion = "mobile-editorial-v1",
            groundTruth = truth,
            decisions = listOf(
                EditorialDecision("select", setOf("take-b"), selectedTakeId = "take-b", takeGroupId = "water-bottle-line"),
                EditorialDecision("remove-a", setOf("take-a"), destructive = true),
                EditorialDecision("bad-cut", setOf("cta"), destructive = true),
                EditorialDecision("cover", setOf("product-demo"))
            ),
            previewFingerprint = "canonical-render-42",
            exportFingerprint = "canonical-render-42",
            timeToPreviewMs = 1_234
        )
        assertEquals(1, record.falseDestructiveCuts)
        assertEquals(2, record.destructiveCutCount)
        assertEquals(1.0, record.takeSelectionAccuracy, 0.0)
        assertEquals(1.0, record.evidenceGroundingRate, 0.0)
        assertTrue(record.previewExportParity)
        assertEquals(1_234, record.timeToPreviewMs)
    }
}
