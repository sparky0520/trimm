import Foundation
import Supabase

public struct ChatSummary: Codable, Identifiable, Equatable {
    public let id: String
    public let title: String
}

private struct ProjectsResponse: Codable {
    let projects: [ChatSummary]
}

public struct AgentExecuteResponse: Codable, Equatable {
    public let type: String
    public let operations: [EditOperation]?
    public let removedOperations: [String]?
    public let questions: [ClarificationQuestion]?
    public let summary: String?
}

public class ChatRepository {
    public static let shared = ChatRepository()
    
    private let baseURL = URL(string: "\(Secrets.backendURL)/v1")!
    
    private init() {}
    
    private func fetchWithRetry(request: URLRequest) async throws -> (Data, URLResponse) {
        let (data, response) = try await URLSession.shared.data(for: request)
        if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 401 {
            // Attempt retry
            let session = try await supabase.auth.refreshSession()
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
        urlRequest.timeoutInterval = 120 // Allow long-running agent requests
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
    
    public func executeAgentTask(request: AgentRunRequest, onStatus: ((String) -> Void)? = nil) async throws -> AgentExecuteResponse {
        let encoder = JSONEncoder()
        let body = try encoder.encode(request)
        
        let url = baseURL.appendingPathComponent("chat/completion")
        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = "POST"
        urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        
        let sessionData = try await supabase.auth.session
        let token = sessionData.accessToken
        urlRequest.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        urlRequest.httpBody = body
        
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 120
        let session = URLSession(configuration: config)
        
        let (bytes, response) = try await session.bytes(for: urlRequest)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw URLError(.badServerResponse)
        }
        
        if httpResponse.statusCode == 401 {
            let refreshedSession = try await supabase.auth.refreshSession()
            urlRequest.setValue("Bearer \(refreshedSession.accessToken)", forHTTPHeaderField: "Authorization")
            let (retryBytes, retryResponse) = try await session.bytes(for: urlRequest)
            guard let retryHttpResponse = retryResponse as? HTTPURLResponse, (200...299).contains(retryHttpResponse.statusCode) else {
                throw URLError(.badServerResponse)
            }
            return try await parseAgentStream(bytes: retryBytes, onStatus: onStatus)
        }
        
        guard (200...299).contains(httpResponse.statusCode) else {
            throw URLError(.badServerResponse)
        }
        
        return try await parseAgentStream(bytes: bytes, onStatus: onStatus)
    }
    
    private func parseAgentStream(bytes: URLSession.AsyncBytes, onStatus: ((String) -> Void)?) async throws -> AgentExecuteResponse {
        var currentEvent = ""
        let decoder = JSONDecoder()
        
        for try await line in bytes.lines {
            if line.hasPrefix("event: ") {
                currentEvent = String(line.dropFirst("event: ".count))
            } else if line.hasPrefix("data: ") {
                let dataString = String(line.dropFirst("data: ".count))
                guard let data = dataString.data(using: .utf8) else { continue }
                
                if currentEvent == "status" {
                    if let statusDict = try? JSONSerialization.jsonObject(with: data) as? [String: String],
                       let status = statusDict["status"] {
                        onStatus?(status)
                    }
                } else if currentEvent == "done" {
                    return try decoder.decode(AgentExecuteResponse.self, from: data)
                } else if currentEvent == "error" {
                    if let errDict = try? JSONSerialization.jsonObject(with: data) as? [String: String],
                       let errMsg = errDict["error"] {
                        throw NSError(domain: "Agent", code: -1, userInfo: [NSLocalizedDescriptionKey: errMsg])
                    }
                }
            }
        }
        throw NSError(domain: "Agent", code: -1, userInfo: [NSLocalizedDescriptionKey: "Stream ended without a done event"])
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
