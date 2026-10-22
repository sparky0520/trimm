import Foundation

public struct AgentChatMessage: Codable, Equatable {
    public let role: String
    public let content: String
    
    public init(role: String, content: String) {
        self.role = role
        self.content = content
    }
}

public struct AgentTranscriptWord: Codable, Equatable {
    public let sourceId: String
    public let text: String
    public let startMs: Int64
    public let endMs: Int64
    public let confidence: Float
    public let speakerId: String?
    
    public init(sourceId: String, text: String, startMs: Int64, endMs: Int64, confidence: Float, speakerId: String? = nil) {
        self.sourceId = sourceId
        self.text = text
        self.startMs = startMs
        self.endMs = endMs
        self.confidence = confidence
        self.speakerId = speakerId
    }
}

public struct AgentSourceInventory: Codable, Equatable {
    public let id: String
    public let fingerprint: String
    public let durationMs: Int64
    public let width: Int
    public let height: Int
    public let hasAudio: Bool
    public let visual: VisualSourceProfile?
    
    public init(id: String, fingerprint: String, durationMs: Int64, width: Int, height: Int, hasAudio: Bool, visual: VisualSourceProfile? = nil) {
        self.id = id
        self.fingerprint = fingerprint
        self.durationMs = durationMs
        self.width = width
        self.height = height
        self.hasAudio = hasAudio
        self.visual = visual
    }
}

public struct AgentBudgets: Codable, Equatable {
    public init() {}
}

public struct AgentMediaConsent: Codable, Equatable {
    public init() {}
}

public struct AgentContractVersions: Codable, Equatable {
    public init() {}
}

public struct AgentRunRequest: Codable, Equatable {
    public let projectId: String
    public let deviceSessionId: String
    public let projectRevision: Int64
    public let intent: String
    public let sources: [AgentSourceInventory]
    public let transcript: [AgentTranscriptWord]
    public let history: [AgentChatMessage]
    public let evidenceJobIds: [String]
    public let existingOperations: [EditOperation]
    public let schema: CompositionSchema?
    public let uploadInitialVideoContext: Bool
    public let budgets: AgentBudgets
    public let mediaConsent: AgentMediaConsent
    public let supportedContractVersions: AgentContractVersions
    public let platform: String
    
    public init(projectId: String, deviceSessionId: String, projectRevision: Int64, intent: String, sources: [AgentSourceInventory], transcript: [AgentTranscriptWord] = [], history: [AgentChatMessage] = [], evidenceJobIds: [String] = [], existingOperations: [EditOperation] = [], schema: CompositionSchema? = nil, uploadInitialVideoContext: Bool = false, budgets: AgentBudgets = AgentBudgets(), mediaConsent: AgentMediaConsent = AgentMediaConsent(), supportedContractVersions: AgentContractVersions = AgentContractVersions(), platform: String = "ios") {
        self.projectId = projectId
        self.deviceSessionId = deviceSessionId
        self.projectRevision = projectRevision
        self.intent = intent
        self.sources = sources
        self.transcript = transcript
        self.history = history
        self.evidenceJobIds = evidenceJobIds
        self.existingOperations = existingOperations
        self.schema = schema
        self.uploadInitialVideoContext = uploadInitialVideoContext
        self.budgets = budgets
        self.mediaConsent = mediaConsent
        self.supportedContractVersions = supportedContractVersions
        self.platform = platform
    }
}

public enum AgentRunRequestBuilder {
    public static func sourceTranscript(words: [TranscriptWord], sources: [SourceMedia]) -> [AgentTranscriptWord] {
        var offset: Int64 = 0
        var result = [AgentTranscriptWord]()
        
        for (index, source) in sources.enumerated() {
            let sourceEnd = offset + source.durationMs
            var lastWordEndMs = offset
            let sourceId = "\(source.fingerprint)-\(index)"
            
            let filteredWords = words.filter { !$0.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && $0.range.startMs >= offset && $0.range.startMs < sourceEnd }
            
            for word in filteredWords {
                let startMs = word.range.startMs
                if startMs - lastWordEndMs > 1000 {
                    let gapSecs = String(format: "%.1f", Float(startMs - lastWordEndMs) / 1000.0)
                    result.append(AgentTranscriptWord(sourceId: sourceId, text: "[SILENCE: \(gapSecs) seconds]", startMs: lastWordEndMs - offset, endMs: startMs - offset, confidence: 1.0, speakerId: nil))
                }
                result.append(AgentTranscriptWord(sourceId: sourceId, text: word.text, startMs: startMs - offset, endMs: min(word.range.endMs, sourceEnd) - offset, confidence: word.confidence, speakerId: word.speakerId))
                lastWordEndMs = max(word.range.endMs, lastWordEndMs)
            }
            
            if sourceEnd - lastWordEndMs > 1000 {
                let gapSecs = String(format: "%.1f", Float(sourceEnd - lastWordEndMs) / 1000.0)
                result.append(AgentTranscriptWord(sourceId: sourceId, text: "[SILENCE: \(gapSecs) seconds]", startMs: lastWordEndMs - offset, endMs: sourceEnd - offset, confidence: 1.0, speakerId: nil))
            }
            offset = sourceEnd
        }
        
        return result
    }
}
