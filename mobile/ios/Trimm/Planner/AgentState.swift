import Foundation

public enum AgentRecoveryStatus: String, Codable {
    case repairFailed = "repair_failed"
    case retrying = "retrying"
    case resolved = "resolved"
}

public enum AgentRecoveryAction: String, Codable {
    case retryRepair = "retry_repair"
    case regenerateProposal = "regenerate_proposal"
    case reviewIssue = "review_issue"
}

public struct AgentRecoveryTurn: Codable, Equatable {
    public let action: AgentRecoveryAction
    public let message: String
    public let createdAt: String
    
    public init(action: AgentRecoveryAction, message: String, createdAt: String) {
        self.action = action
        self.message = message
        self.createdAt = createdAt
    }
}

public struct AgentRecoveryState: Codable, Equatable {
    public let status: AgentRecoveryStatus
    public let issue: String
    public let reviewRequested: Bool
    public let conversation: [AgentRecoveryTurn]
    
    public init(status: AgentRecoveryStatus, issue: String = "The response format could not be fixed safely. Nothing was applied.", reviewRequested: Bool = false, conversation: [AgentRecoveryTurn] = []) {
        self.status = status
        self.issue = issue
        self.reviewRequested = reviewRequested
        self.conversation = conversation
    }
}
