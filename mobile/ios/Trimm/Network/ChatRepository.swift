import Foundation
import Supabase

public class ChatRepository {
    public static let shared = ChatRepository()
    
    private init() {}
    
    public func submitAgentRun(request: AgentRunRequest) async throws -> PersistedAgentRun {
        // In the future this will use supabase.functions.invoke("agent-run", options: ...)
        // For now, this just defines the interface for the EditorViewModel
        fatalError("Not yet implemented - requires AnyCodable for dynamic JSON")
    }
    
    public func fetchProjectHistory(projectId: String) async throws -> [PersistedAgentRun] {
        fatalError("Not yet implemented - database schema pending")
    }
}
