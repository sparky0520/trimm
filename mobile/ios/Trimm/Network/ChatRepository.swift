import Foundation
import Supabase

public struct ChatSummary: Codable, Identifiable, Equatable {
    public let id: String
    public let title: String
}

private struct ProjectsResponse: Codable {
    let projects: [ChatSummary]
}

public class ChatRepository {
    public static let shared = ChatRepository()
    
    private let baseURL = URL(string: "https://ctrlb-backend.onrender.com/v1")!
    
    private init() {}
    
    private func fetchWithRetry(request: URLRequest) async throws -> (Data, URLResponse) {
        let (data, response) = try await URLSession.shared.data(for: request)
        if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 401 {
            // Attempt retry
            let session = try await supabase.auth.session
            let token = session.accessToken
            var retryRequest = request
            retryRequest.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            return try await URLSession.shared.data(for: retryRequest)
        }
        return (data, response)
    }
    
    private func makeRequest(endpoint: String, method: String, body: Data? = nil) async throws -> Data {
        let url = baseURL.appendingPathComponent(endpoint)
        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = method
        urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        
        let session = try await supabase.auth.session
        let token = session.accessToken
        urlRequest.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        urlRequest.httpBody = body
        
        let (data, response) = try await fetchWithRetry(request: urlRequest)
        
        guard let httpResponse = response as? HTTPURLResponse else {
            throw URLError(.badServerResponse)
        }
        
        guard (200...299).contains(httpResponse.statusCode) else {
            if let errorMsg = String(data: data, encoding: .utf8) {
                print("API Error: \(errorMsg)")
            }
            throw URLError(.badServerResponse)
        }
        return data
    }
    
    public func executeAgentTask(request: AgentRunRequest) async throws -> AgentExecuteResponse {
        let encoder = JSONEncoder()
        let body = try encoder.encode(request)
        let data = try await makeRequest(endpoint: "agent/execute", method: "POST", body: body)
        
        let decoder = JSONDecoder()
        return try decoder.decode(AgentExecuteResponse.self, from: data)
    }
    
    public func getProjects() async throws -> [ChatSummary] {
        let data = try await makeRequest(endpoint: "projects", method: "GET")
        let decoder = JSONDecoder()
        let response = try decoder.decode(ProjectsResponse.self, from: data)
        return response.projects
    }
    
    public func getProject(id: String) async throws -> MobileProject {
        let data = try await makeRequest(endpoint: "projects/\(id)", method: "GET")
        let decoder = JSONDecoder()
        return try decoder.decode(MobileProject.self, from: data)
    }
    
    public func syncProject(project: MobileProject) async throws {
        let encoder = JSONEncoder()
        let body = try encoder.encode(project)
        _ = try await makeRequest(endpoint: "projects/sync", method: "POST", body: body)
    }
    
    public func deleteProject(id: String) async throws {
        _ = try await makeRequest(endpoint: "projects/\(id)", method: "DELETE")
    }
}
