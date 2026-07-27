package ai.ctrlb.mobile.domain

import kotlinx.serialization.Serializable

@Serializable enum class EditorialDecisionStage {
    STORY, TAKE_SELECTION, CORRECTION, PACING, COVERAGE, OPTIONAL_TREATMENT
}

@Serializable enum class EditorialDecisionKind {
    REQUIRED_CLEANUP, OPTIONAL_STYLE, OPTIONAL_MUSIC
}

@Serializable data class StoryPlanChoice(
    val beatId: String,
    val selectedTakeId: String? = null,
    val rationale: String,
    val evidenceIds: List<String>,
    val confidence: Float,
    val assumptions: List<String> = emptyList(),
    val expectedStoryEffect: String
)

@Serializable data class PlannedEditorialDecision(
    val id: String,
    val stage: EditorialDecisionStage,
    val kind: EditorialDecisionKind,
    val rationale: String,
    val evidenceIds: List<String>,
    val confidence: Float,
    val assumptions: List<String> = emptyList(),
    val expectedStoryEffect: String
)

@Serializable data class PlannedOperation(
    val operationId: String,
    val decisionId: String,
    val rationale: String,
    val evidenceIds: List<String>,
    val confidence: Float,
    val assumptions: List<String> = emptyList(),
    val expectedStoryEffect: String
)

@Serializable data class StagedEditorialPlan(
    val story: List<StoryPlanChoice>,
    val decisions: List<PlannedEditorialDecision>,
    val operationPlans: List<PlannedOperation>
)
