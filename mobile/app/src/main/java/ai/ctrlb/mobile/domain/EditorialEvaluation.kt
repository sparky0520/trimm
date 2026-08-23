package ai.ctrlb.mobile.domain

import kotlinx.serialization.Serializable

@Serializable
data class EditorialGroundTruth(
    val preferredTakeByGroup: Map<String, String>,
    val mustKeepEvidenceIds: Set<String>,
    val safeRemovalEvidenceIds: Set<String>,
    val validEvidenceIds: Set<String>
)

@Serializable
data class EditorialDecision(
    val id: String,
    val evidenceIds: Set<String>,
    val destructive: Boolean = false,
    val selectedTakeId: String? = null,
    val takeGroupId: String? = null
)

@Serializable
data class EditorialEvaluationRecord(
    val fixtureVersion: String,
    val falseDestructiveCuts: Int,
    val destructiveCutCount: Int,
    val takeSelectionsCorrect: Int,
    val takeSelectionCount: Int,
    val evidenceGroundedDecisions: Int,
    val automaticDecisionCount: Int,
    val previewExportParity: Boolean,
    val timeToPreviewMs: Long
) {
    val takeSelectionAccuracy: Double
        get() = if (takeSelectionCount == 0) 1.0 else takeSelectionsCorrect.toDouble() / takeSelectionCount
    val evidenceGroundingRate: Double
        get() = if (automaticDecisionCount == 0) 1.0 else evidenceGroundedDecisions.toDouble() / automaticDecisionCount
}

object EditorialEvaluator {
    fun evaluate(
        fixtureVersion: String,
        groundTruth: EditorialGroundTruth,
        decisions: List<EditorialDecision>,
        previewFingerprint: String,
        exportFingerprint: String,
        timeToPreviewMs: Long
    ): EditorialEvaluationRecord {
        require(fixtureVersion.isNotBlank())
        require(timeToPreviewMs >= 0)
        require(groundTruth.mustKeepEvidenceIds.all(groundTruth.validEvidenceIds::contains))
        require(groundTruth.safeRemovalEvidenceIds.all(groundTruth.validEvidenceIds::contains))

        val destructive = decisions.filter(EditorialDecision::destructive)
        val falseDestructive = destructive.count { decision ->
            decision.evidenceIds.isEmpty() ||
                decision.evidenceIds.any(groundTruth.mustKeepEvidenceIds::contains) ||
                decision.evidenceIds.none(groundTruth.safeRemovalEvidenceIds::contains)
        }
        val selections = decisions.filter { it.takeGroupId != null || it.selectedTakeId != null }
        val correctSelections = selections.count { decision ->
            val groupId = decision.takeGroupId
            groupId != null && decision.selectedTakeId == groundTruth.preferredTakeByGroup[groupId]
        }
        val grounded = decisions.count { decision ->
            decision.evidenceIds.isNotEmpty() &&
                decision.evidenceIds.all(groundTruth.validEvidenceIds::contains)
        }

        return EditorialEvaluationRecord(
            fixtureVersion = fixtureVersion,
            falseDestructiveCuts = falseDestructive,
            destructiveCutCount = destructive.size,
            takeSelectionsCorrect = correctSelections,
            takeSelectionCount = selections.size,
            evidenceGroundedDecisions = grounded,
            automaticDecisionCount = decisions.size,
            previewExportParity = previewFingerprint == exportFingerprint,
            timeToPreviewMs = timeToPreviewMs
        )
    }
}
