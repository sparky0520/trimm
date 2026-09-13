import Foundation

public struct ClarificationOption: Codable, Equatable {
    public let id: String
    public let label: String
    public let description: String
    
    public init(id: String, label: String, description: String) {
        self.id = id
        self.label = label
        self.description = description
    }
}

public struct ClarificationQuestion: Codable, Equatable {
    public let id: String
    public let prompt: String
    public let options: [ClarificationOption]
    public let recommendedOptionId: String
    
    public init(id: String, prompt: String, options: [ClarificationOption], recommendedOptionId: String = "") {
        self.id = id
        self.prompt = prompt
        self.options = options
        self.recommendedOptionId = recommendedOptionId
    }
}

public struct ClarificationDecision: Codable, Equatable {
    public let type: String
    public let questions: [ClarificationQuestion]
    
    public init(type: String, questions: [ClarificationQuestion]) {
        self.type = type
        self.questions = questions
    }
}

public struct AgentPlanResponse: Codable, Equatable {
    public let tasks: [AgentTask]?
    public let clarification: ClarificationDecision?
    
    public init(tasks: [AgentTask]? = nil, clarification: ClarificationDecision? = nil) {
        self.tasks = tasks
        self.clarification = clarification
    }
}

public protocol AgentRunGateway {
    // In Android: suspend fun chatCompletion(request: AgentRunRequest): JsonObject
    func chatCompletion(request: AgentRunRequest) async throws -> [String: AnyCodable]
    func generateBgm(prompt: String, durationSecs: Float, isEffect: Bool, chatId: String) async throws -> String
    func generateSfx(prompt: String) async throws -> String
}

public protocol AgentEvidenceFulfiller {
    // func fulfill(job: AuthorizedEvidenceJob) async throws -> String
}
