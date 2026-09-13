import Foundation

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
    
    public init(sequence: Int, type: String, message: String, createdAt: String) {
        self.sequence = sequence
        self.type = type
        self.message = message
        self.createdAt = createdAt
    }
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
    
    public init(callId: String, provider: String, model: String, purpose: String, inputTokens: Int64 = 0, outputTokens: Int64 = 0, thinkingTokens: Int64 = 0, cachedTokens: Int64 = 0, textTokens: Int64 = 0, videoTokens: Int64 = 0, totalTokens: Int64 = 0, estimatedCostMicros: Int64 = 0, occurredAt: String? = nil, sourceId: String? = nil, startMs: Int64? = nil, endMs: Int64? = nil, resolution: String? = nil, costEstimated: Bool = true) {
        self.callId = callId
        self.provider = provider
        self.model = model
        self.purpose = purpose
        self.inputTokens = inputTokens
        self.outputTokens = outputTokens
        self.thinkingTokens = thinkingTokens
        self.cachedTokens = cachedTokens
        self.textTokens = textTokens
        self.videoTokens = videoTokens
        self.totalTokens = totalTokens
        self.estimatedCostMicros = estimatedCostMicros
        self.occurredAt = occurredAt
        self.sourceId = sourceId
        self.startMs = startMs
        self.endMs = endMs
        self.resolution = resolution
        self.costEstimated = costEstimated
    }
}

public struct AgentUsageSummary: Codable, Equatable {
    public let calls: [AgentUsageCall]
    public let inputTokens: Int64
    public let outputTokens: Int64
    public let thinkingTokens: Int64
    public let cachedTokens: Int64
    public let totalTokens: Int64
    public let estimatedCostMicros: Int64
    
    public init(calls: [AgentUsageCall] = [], inputTokens: Int64 = 0, outputTokens: Int64 = 0, thinkingTokens: Int64 = 0, cachedTokens: Int64 = 0, totalTokens: Int64 = 0, estimatedCostMicros: Int64 = 0) {
        self.calls = calls
        self.inputTokens = inputTokens
        self.outputTokens = outputTokens
        self.thinkingTokens = thinkingTokens
        self.cachedTokens = cachedTokens
        self.totalTokens = totalTokens
        self.estimatedCostMicros = estimatedCostMicros
    }
}

public struct AgentRun: Codable {
    public let contractVersion: Int
    public let runId: String
    public let projectId: String
    public let projectRevision: Int64
    public let status: AgentRunStatus
    public let events: [AgentActivityEvent]
    public let decision: [String: AnyCodable] // Assuming AnyCodable for JsonObject equivalence
    public let usage: AgentUsageSummary
    
    public init(contractVersion: Int, runId: String, projectId: String, projectRevision: Int64, status: AgentRunStatus, events: [AgentActivityEvent], decision: [String: AnyCodable], usage: AgentUsageSummary = AgentUsageSummary()) {
        self.contractVersion = contractVersion
        self.runId = runId
        self.projectId = projectId
        self.projectRevision = projectRevision
        self.status = status
        self.events = events
        self.decision = decision
        self.usage = usage
    }
}

public struct PersistedAgentRun: Codable {
    public let contractVersion: Int
    public let runId: String
    public let projectId: String
    public let projectRevision: Int64
    public let status: AgentRunStatus
    public let events: [AgentActivityEvent]
    public let decision: [String: AnyCodable]
    public let fulfilledEvidenceJobIds: [String]
    // public let recovery: AgentRecoveryState? = nil // Omitted for brevity unless needed
    public let usage: AgentUsageSummary
    
    public init(contractVersion: Int = 2, runId: String, projectId: String, projectRevision: Int64, status: AgentRunStatus, events: [AgentActivityEvent], decision: [String: AnyCodable], fulfilledEvidenceJobIds: [String] = [], usage: AgentUsageSummary = AgentUsageSummary()) {
        self.contractVersion = contractVersion
        self.runId = runId
        self.projectId = projectId
        self.projectRevision = projectRevision
        self.status = status
        self.events = events
        self.decision = decision
        self.fulfilledEvidenceJobIds = fulfilledEvidenceJobIds
        self.usage = usage
    }
}
