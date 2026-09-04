package ai.trimm.mobile.domain

import kotlinx.serialization.Serializable

@Serializable enum class CreatorPreferenceKind {
    BRAND_ASSET, TERMINOLOGY, CAPTION_RULE, PACING, MOTION_TOLERANCE
}

@Serializable data class CreatorPreference(
    val kind: CreatorPreferenceKind,
    val value: String,
    val decisionIds: List<String>,
    val confirmations: Int = 1,
    val explicit: Boolean = false,
    val enabled: Boolean = true
) {
    init {
        require(value.isNotBlank())
        require(confirmations > 0)
        require(decisionIds.isNotEmpty())
    }
    val active: Boolean get() = enabled && (explicit || confirmations >= 2)
}

@Serializable data class CreatorPreferenceProfile(
    val schemaVersion: Int = 1,
    val entries: List<CreatorPreference> = emptyList()
) {
    init { require(schemaVersion == 1) }
    val activeEntries: List<CreatorPreference> get() = entries.filter(CreatorPreference::active)

    fun record(kind: CreatorPreferenceKind, value: String, decisionId: String, explicit: Boolean = false): CreatorPreferenceProfile {
        val clean = value.trim().take(500)
        require(clean.isNotEmpty() && decisionId.isNotBlank())
        val prior = entries.firstOrNull { it.kind == kind && it.value.equals(clean, ignoreCase = true) }
        val next = if (prior == null) {
            CreatorPreference(kind, clean, listOf(decisionId), explicit = explicit)
        } else {
            prior.copy(
                decisionIds = (prior.decisionIds + decisionId).distinct().takeLast(20),
                confirmations = prior.confirmations + if (decisionId in prior.decisionIds) 0 else 1,
                explicit = prior.explicit || explicit,
                enabled = true
            )
        }
        return copy(entries = (entries.filterNot { it.kind == kind && it.value.equals(clean, true) } + next)
            .sortedWith(compareBy({ it.kind.name }, { it.value })))
    }

    fun setEnabled(kind: CreatorPreferenceKind, value: String, enabled: Boolean) =
        copy(entries = entries.map { if (it.kind == kind && it.value == value) it.copy(enabled = enabled) else it })

    fun reset(kind: CreatorPreferenceKind? = null, value: String? = null) = when {
        kind == null -> copy(entries = emptyList())
        value == null -> copy(entries = entries.filterNot { it.kind == kind })
        else -> copy(entries = entries.filterNot { it.kind == kind && it.value == value })
    }
}

@Serializable data class ResolvedCreatorPreferences(
    val brandAssets: List<String> = emptyList(),
    val terminology: List<String> = emptyList(),
    val captionRules: List<String> = emptyList(),
    val pacing: String? = null,
    val motionTolerance: String? = null,
    val provenanceDecisionIds: List<String> = emptyList()
)

object CreatorPreferenceResolver {
    /** Learned taste stays secondary to the current brief and never supplies footage evidence. */
    fun resolve(profile: CreatorPreferenceProfile, projectIntent: WizardPreferences): ResolvedCreatorPreferences {
        val active = profile.activeEntries
        fun values(kind: CreatorPreferenceKind) = active.filter { it.kind == kind }.map { it.value }
        val learnedPace = values(CreatorPreferenceKind.PACING).lastOrNull()
        return ResolvedCreatorPreferences(
            brandAssets = values(CreatorPreferenceKind.BRAND_ASSET),
            terminology = values(CreatorPreferenceKind.TERMINOLOGY),
            captionRules = if (projectIntent.captions) values(CreatorPreferenceKind.CAPTION_RULE) else emptyList(),
            pacing = learnedPace?.takeIf { it.equals(projectIntent.pace.name, true) },
            motionTolerance = values(CreatorPreferenceKind.MOTION_TOLERANCE).lastOrNull(),
            provenanceDecisionIds = active.flatMap { it.decisionIds }.distinct()
        )
    }
}

object CreatorPreferenceLearner {
    fun confirmedProposal(profile: CreatorPreferenceProfile, project: MobileProject, proposal: PendingEditorialProposal): CreatorPreferenceProfile {
        var learned = profile
        proposal.plan.decisions.filter { it.stage == EditorialDecisionStage.PACING }.forEach {
            learned = learned.record(CreatorPreferenceKind.PACING, project.preferences.pace.name, it.id)
        }
        proposal.operations.filterIsInstance<AddCaptions>().forEach {
            learned = learned.record(CreatorPreferenceKind.CAPTION_RULE, it.style, it.id)
        }
        return learned
    }
}

data class PersonalizationEvaluation(
    val baselineAccepted: Int,
    val personalizedAccepted: Int,
    val baselineUngrounded: Int,
    val personalizedUngrounded: Int
) {
    val improvesAcceptance get() = personalizedAccepted > baselineAccepted
    val avoidsUngroundedIncrease get() = personalizedUngrounded <= baselineUngrounded
}
