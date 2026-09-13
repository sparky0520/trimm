import Foundation

struct AnyCodable: Codable, Equatable {
    let value: Any
    static func ==(lhs: AnyCodable, rhs: AnyCodable) -> Bool { return false }
    func encode(to encoder: Encoder) throws {}
    init(from decoder: Decoder) throws { value = 0 }
}

struct AgentRunRequest: Equatable {
    let existingOperations: [[String: AnyCodable]]
}
