package ai.trimm.mobile.domain

import org.junit.Assert.*
import org.junit.Test

class CreatorPreferencesTest {
    @Test fun `repeated decisions activate taste and preserve its provenance`() {
        val once = CreatorPreferenceProfile().record(CreatorPreferenceKind.MOTION_TOLERANCE, "restrained", "decision-1")
        assertFalse(once.entries.single().active)
        val twice = once.record(CreatorPreferenceKind.MOTION_TOLERANCE, "restrained", "decision-2")
        assertTrue(twice.entries.single().active)
        assertEquals(listOf("decision-1", "decision-2"), twice.entries.single().decisionIds)
    }

    @Test fun `explicit preferences are editable and individually or globally resettable`() {
        val profile = CreatorPreferenceProfile()
            .record(CreatorPreferenceKind.TERMINOLOGY, "Use Ctrl+B", "explicit-1", explicit = true)
            .record(CreatorPreferenceKind.BRAND_ASSET, "logo-primary.svg", "explicit-2", explicit = true)
        assertEquals(2, profile.activeEntries.size)
        assertFalse(profile.setEnabled(CreatorPreferenceKind.TERMINOLOGY, "Use Ctrl+B", false)
            .entries.first { it.kind == CreatorPreferenceKind.TERMINOLOGY }.active)
        assertEquals(listOf(CreatorPreferenceKind.BRAND_ASSET), profile.reset(CreatorPreferenceKind.TERMINOLOGY, "Use Ctrl+B").entries.map { it.kind })
        assertTrue(profile.reset().entries.isEmpty())
    }

    @Test fun `project intent overrides learned caption and pacing taste`() {
        val profile = CreatorPreferenceProfile()
            .record(CreatorPreferenceKind.CAPTION_RULE, "bold-bottom", "explicit-1", explicit = true)
            .record(CreatorPreferenceKind.PACING, "TIGHT", "explicit-2", explicit = true)
        val resolved = CreatorPreferenceResolver.resolve(
            profile, WizardPreferences(captions = false, pace = Pace.NATURAL)
        )
        assertTrue(resolved.captionRules.isEmpty())
        assertNull(resolved.pacing)
        assertTrue(resolved.provenanceDecisionIds.isNotEmpty())
    }

    @Test fun `personalization evaluation requires acceptance gain without grounding regression`() {
        val evaluation = PersonalizationEvaluation(
            baselineAccepted = 6, personalizedAccepted = 8,
            baselineUngrounded = 0, personalizedUngrounded = 0
        )
        assertTrue(evaluation.improvesAcceptance)
        assertTrue(evaluation.avoidsUngroundedIncrease)
        assertFalse(evaluation.copy(personalizedUngrounded = 1).avoidsUngroundedIncrease)
    }
}
