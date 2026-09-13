import Foundation

public struct EditorialGroundTruth: Codable, Equatable {
    public let preferredTakeByGroup: [String: String]
    public let mustKeepEvidenceIds: Set<String>
    public let safeRemovalEvidenceIds: Set<String>
    public let validEvidenceIds: Set<String>
    
    public init(preferredTakeByGroup: [String: String], mustKeepEvidenceIds: Set<String>, safeRemovalEvidenceIds: Set<String>, validEvidenceIds: Set<String>) {
        self.preferredTakeByGroup = preferredTakeByGroup
        self.mustKeepEvidenceIds = mustKeepEvidenceIds
        self.safeRemovalEvidenceIds = safeRemovalEvidenceIds
        self.validEvidenceIds = validEvidenceIds
    }
}

public struct EditorialDecision: Codable, Equatable {
    public let id: String
    public let evidenceIds: Set<String>
    public let destructive: Bool
    public let selectedTakeId: String?
    public let takeGroupId: String?
    
    public init(id: String, evidenceIds: Set<String>, destructive: Bool = false, selectedTakeId: String? = nil, takeGroupId: String? = nil) {
        self.id = id
        self.evidenceIds = evidenceIds
        self.destructive = destructive
        self.selectedTakeId = selectedTakeId
        self.takeGroupId = takeGroupId
    }
}

public struct EditorialEvaluationRecord: Codable, Equatable {
    public let fixtureVersion: String
    public let falseDestructiveCuts: Int
    public let destructiveCutCount: Int
    public let takeSelectionsCorrect: Int
    public let takeSelectionCount: Int
    public let evidenceGroundedDecisions: Int
    public let automaticDecisionCount: Int
    public let previewExportParity: Bool
    public let timeToPreviewMs: Int64
    
    public init(fixtureVersion: String, falseDestructiveCuts: Int, destructiveCutCount: Int, takeSelectionsCorrect: Int, takeSelectionCount: Int, evidenceGroundedDecisions: Int, automaticDecisionCount: Int, previewExportParity: Bool, timeToPreviewMs: Int64) {
        self.fixtureVersion = fixtureVersion
        self.falseDestructiveCuts = falseDestructiveCuts
        self.destructiveCutCount = destructiveCutCount
        self.takeSelectionsCorrect = takeSelectionsCorrect
        self.takeSelectionCount = takeSelectionCount
        self.evidenceGroundedDecisions = evidenceGroundedDecisions
        self.automaticDecisionCount = automaticDecisionCount
        self.previewExportParity = previewExportParity
        self.timeToPreviewMs = timeToPreviewMs
    }
    
    public var takeSelectionAccuracy: Double {
        takeSelectionCount == 0 ? 1.0 : Double(takeSelectionsCorrect) / Double(takeSelectionCount)
    }
    
    public var evidenceGroundingRate: Double {
        automaticDecisionCount == 0 ? 1.0 : Double(evidenceGroundedDecisions) / Double(automaticDecisionCount)
    }
}

public enum EditorialEvaluator {
    public static func evaluate(
        fixtureVersion: String,
        groundTruth: EditorialGroundTruth,
        decisions: [EditorialDecision],
        previewFingerprint: String,
        exportFingerprint: String,
        timeToPreviewMs: Int64
    ) -> EditorialEvaluationRecord {
        precondition(!fixtureVersion.isEmpty)
        precondition(timeToPreviewMs >= 0)
        precondition(groundTruth.mustKeepEvidenceIds.isSubset(of: groundTruth.validEvidenceIds))
        precondition(groundTruth.safeRemovalEvidenceIds.isSubset(of: groundTruth.validEvidenceIds))
        
        let destructive = decisions.filter { $0.destructive }
        let falseDestructive = destructive.filter { decision in
            decision.evidenceIds.isEmpty ||
            !decision.evidenceIds.isDisjoint(with: groundTruth.mustKeepEvidenceIds) ||
            decision.evidenceIds.isDisjoint(with: groundTruth.safeRemovalEvidenceIds)
        }.count
        
        let selections = decisions.filter { $0.takeGroupId != nil || $0.selectedTakeId != nil }
        let correctSelections = selections.filter { decision in
            if let groupId = decision.takeGroupId, decision.selectedTakeId == groundTruth.preferredTakeByGroup[groupId] {
                return true
            }
            return false
        }.count
        
        let grounded = decisions.filter { decision in
            !decision.evidenceIds.isEmpty && decision.evidenceIds.isSubset(of: groundTruth.validEvidenceIds)
        }.count
        
        return EditorialEvaluationRecord(
            fixtureVersion: fixtureVersion,
            falseDestructiveCuts: falseDestructive,
            destructiveCutCount: destructive.count,
            takeSelectionsCorrect: correctSelections,
            takeSelectionCount: selections.count,
            evidenceGroundedDecisions: grounded,
            automaticDecisionCount: decisions.count,
            previewExportParity: previewFingerprint == exportFingerprint,
            timeToPreviewMs: timeToPreviewMs
        )
    }
}
