import Foundation

public struct AgentTask: Codable, Equatable {
    public let id: String
    public let description: String
    public let dependsOn: [String]
    
    public init(id: String, description: String, dependsOn: [String] = []) {
        self.id = id
        self.description = description
        self.dependsOn = dependsOn
    }
}
