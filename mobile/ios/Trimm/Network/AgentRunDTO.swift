import Foundation
import Supabase // if needed for AnyJSON

public enum AgentRunStatus: String, Codable {
    case waitingForEvidence = "waiting_for_evidence"
    case waitingForUser = "waiting_for_user"
    case proposalReady = "proposal_ready"
    case partial = "partial"
    case failed = "failed"
    case cancelled = "cancelled"
}

public struct AgentActivityEvent: Codable, Equatable {
    public let sequence: Int
    public let type: String
    public let message: String
    public let createdAt: String
}

public struct AgentUsageCall: Codable, Equatable {
    public let callId: String
    public let provider: String
    public let model: String
    public let purpose: String
    public let inputTokens: Int64
    public let outputTokens: Int64
    public let thinkingTokens: Int64
    public let cachedTokens: Int64
    public let textTokens: Int64
    public let videoTokens: Int64
    public let totalTokens: Int64
    public let estimatedCostMicros: Int64
    public let occurredAt: String?
    public let sourceId: String?
    public let startMs: Int64?
    public let endMs: Int64?
    public let resolution: String?
    public let costEstimated: Bool
}

public struct AgentUsageSummary: Codable, Equatable {
    public let calls: [AgentUsageCall]
    public let inputTokens: Int64
    public let outputTokens: Int64
    public let thinkingTokens: Int64
    public let cachedTokens: Int64
    public let totalTokens: Int64
    public let estimatedCostMicros: Int64
}

public struct ClarificationOption: Codable, Equatable {
    public let id: String
    public let label: String
    public let description: String
}

public struct ClarificationQuestion: Codable, Equatable {
    public let id: String
    public let prompt: String
    public let options: [ClarificationOption]
    public let recommendedOptionId: String
}

public struct ClarificationDecision: Codable, Equatable {
    public let type: String
    public let questions: [ClarificationQuestion]
}

public struct Layer2SourceInventory: Codable, Equatable {
    public let id: String
    public let fingerprint: String
    public let durationMs: Int64
    public let width: Int
    public let height: Int
    public let hasAudio: Bool
}

public struct Layer2TranscriptWord: Codable, Equatable {
    public let sourceId: String
    public let text: String
    public let startMs: Int64
    public let endMs: Int64
    public let confidence: Float
    public let speakerId: String?
}

public struct Layer2Budgets: Codable, Equatable {
    public let maximumBytes: Int64
    public let maximumDurationMs: Int64
    public let retentionMs: Int64
}

public struct AgentRunRequest: Codable {
    public let projectId: String
    public let deviceSessionId: String
    public let projectRevision: Int64
    public let intent: String
    public let sources: [Layer2SourceInventory]
    public let transcript: [Layer2TranscriptWord]
    public let evidenceJobIds: [String]
    // In iOS we can encode the existing operations to Data/AnyCodable
    // public let existingOperations: [AnyCodable]
    public let uploadInitialVideoContext: Bool
    public let budgets: Layer2Budgets
}

public struct PersistedAgentRun: Codable, Equatable {
    public let contractVersion: Int
    public let runId: String
    public let projectId: String
    public let projectRevision: Int64
    public let status: AgentRunStatus
    public let events: [AgentActivityEvent]
    // public let decision: [String: AnyCodable] // Handled custom or using AnyCodable
    public let fulfilledEvidenceJobIds: [String]
    public let usage: AgentUsageSummary
}
