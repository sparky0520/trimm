import Foundation

public enum EditorialDecisionStage: String, Codable {
    case story = "STORY", takeSelection = "TAKE_SELECTION", correction = "CORRECTION", pacing = "PACING", coverage = "COVERAGE", optionalTreatment = "OPTIONAL_TREATMENT"
}

public enum EditorialDecisionKind: String, Codable {
    case requiredCleanup = "REQUIRED_CLEANUP", optionalStyle = "OPTIONAL_STYLE", optionalMusic = "OPTIONAL_MUSIC"
}

public struct StoryPlanChoice: Codable, Equatable {
    public let beatId: String
    public let selectedTakeId: String?
    public let rationale: String
    public let evidenceIds: [String]
    public let confidence: Float
    public let assumptions: [String]
    public let expectedStoryEffect: String
    
    public init(beatId: String, selectedTakeId: String? = nil, rationale: String, evidenceIds: [String], confidence: Float, assumptions: [String] = [], expectedStoryEffect: String) {
        self.beatId = beatId
        self.selectedTakeId = selectedTakeId
        self.rationale = rationale
        self.evidenceIds = evidenceIds
        self.confidence = confidence
        self.assumptions = assumptions
        self.expectedStoryEffect = expectedStoryEffect
    }
}

public struct PlannedEditorialDecision: Codable, Equatable {
    public let id: String
    public let stage: EditorialDecisionStage
    public let kind: EditorialDecisionKind
    public let rationale: String
    public let evidenceIds: [String]
    public let confidence: Float
    public let assumptions: [String]
    public let expectedStoryEffect: String
    
    public init(id: String, stage: EditorialDecisionStage, kind: EditorialDecisionKind, rationale: String, evidenceIds: [String], confidence: Float, assumptions: [String] = [], expectedStoryEffect: String) {
        self.id = id
        self.stage = stage
        self.kind = kind
        self.rationale = rationale
        self.evidenceIds = evidenceIds
        self.confidence = confidence
        self.assumptions = assumptions
        self.expectedStoryEffect = expectedStoryEffect
    }
}

public struct PlannedOperation: Codable, Equatable {
    public let operationId: String
    public let decisionId: String
    public let rationale: String
    public let evidenceIds: [String]
    public let confidence: Float
    public let assumptions: [String]
    public let expectedStoryEffect: String
    
    public init(operationId: String, decisionId: String, rationale: String, evidenceIds: [String], confidence: Float, assumptions: [String] = [], expectedStoryEffect: String) {
        self.operationId = operationId
        self.decisionId = decisionId
        self.rationale = rationale
        self.evidenceIds = evidenceIds
        self.confidence = confidence
        self.assumptions = assumptions
        self.expectedStoryEffect = expectedStoryEffect
    }
}

public struct StagedEditorialPlan: Codable, Equatable {
    public let story: [StoryPlanChoice]
    public let decisions: [PlannedEditorialDecision]
    public let operationPlans: [PlannedOperation]
    
    public init(story: [StoryPlanChoice] = [], decisions: [PlannedEditorialDecision] = [], operationPlans: [PlannedOperation] = []) {
        self.story = story
        self.decisions = decisions
        self.operationPlans = operationPlans
    }
}

public struct PendingEditorialProposal: Codable, Equatable {
    public let expectedRevision: Int64
    public let summary: String
    public let plan: StagedEditorialPlan
    public let operations: [EditOperation]
    public let evidenceIds: [String]
    
    public init(expectedRevision: Int64, summary: String, plan: StagedEditorialPlan, operations: [EditOperation], evidenceIds: [String]) {
        self.expectedRevision = expectedRevision
        self.summary = summary
        self.plan = plan
        self.operations = operations
        self.evidenceIds = evidenceIds
    }
}
