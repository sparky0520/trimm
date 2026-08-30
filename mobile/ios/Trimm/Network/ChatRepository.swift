import Foundation
import Supabase
import ClerkKit

public class ChatRepository {
    public static let shared = ChatRepository()
    
    // Pointing to cloud backend from android .env.local
    private let executeEndpoint = URL(string: "https://ctrlb-backend.onrender.com/v1/agent/execute")!
    
    private init() {}
    
    public func executeAgentTask(request: AgentRunRequest) async throws -> AgentExecuteResponse {
        var urlRequest = URLRequest(url: executeEndpoint)
        urlRequest.httpMethod = "POST"
        urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        
        guard let token = try await Clerk.shared.session?.getToken() else {
            throw URLError(.userAuthenticationRequired)
        }
        urlRequest.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        
        let encoder = JSONEncoder()
        urlRequest.httpBody = try encoder.encode(request)
        
        let (data, response) = try await URLSession.shared.data(for: urlRequest)
        
        guard let httpResponse = response as? HTTPURLResponse else {
            throw URLError(.badServerResponse)
        }
        
        guard (200...299).contains(httpResponse.statusCode) else {
            if let errorMsg = String(data: data, encoding: .utf8) {
                print("Agent API Error: \(errorMsg)")
            }
            throw URLError(.badServerResponse)
        }
        
        let decoder = JSONDecoder()
        return try decoder.decode(AgentExecuteResponse.self, from: data)
    }
}
