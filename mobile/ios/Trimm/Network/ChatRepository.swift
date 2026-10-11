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
    public let schema: CompositionSchema?
}

public enum ChatRepositoryError: LocalizedError, Equatable {
    case unauthorized
    case badServerResponse
    
    public var errorDescription: String? {
        switch self {
        case .unauthorized:
            return "Please sign in to continue chat."
        case .badServerResponse:
            return "Server returned an unexpected response."
        }
    }
}

public class ChatRepository {
    public static let shared = ChatRepository()
    
    private let baseURL = URL(string: "\(Secrets.backendURL)/v1")!
    
    private init() {}
    
    public func isAuthenticated() async -> Bool {
        return (try? await supabase.auth.session) != nil
    }
    
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
        urlRequest.timeoutInterval = .infinity // Allow long-running agent requests
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
        
        let sessionData: Session
        do {
            sessionData = try await supabase.auth.session
        } catch {
            throw ChatRepositoryError.unauthorized
        }
        let token = sessionData.accessToken
        urlRequest.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        urlRequest.httpBody = body
        
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = .infinity
        config.timeoutIntervalForResource = .infinity
        let session = URLSession(configuration: config)
        
        let (bytes, response) = try await session.bytes(for: urlRequest)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw URLError(.badServerResponse)
        }
        
        if httpResponse.statusCode == 401 {
            do {
                let refreshedSession = try await supabase.auth.refreshSession()
                urlRequest.setValue("Bearer \(refreshedSession.accessToken)", forHTTPHeaderField: "Authorization")
                let (retryBytes, retryResponse) = try await session.bytes(for: urlRequest)
                guard let retryHttpResponse = retryResponse as? HTTPURLResponse, (200...299).contains(retryHttpResponse.statusCode) else {
                    if let retryHttpResponse = retryResponse as? HTTPURLResponse, retryHttpResponse.statusCode == 401 {
                        throw ChatRepositoryError.unauthorized
                    }
                    throw URLError(.badServerResponse)
                }
                return try await parseAgentStream(bytes: retryBytes, onStatus: onStatus)
            } catch {
                throw ChatRepositoryError.unauthorized
            }
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
    
    private var projectsDirectory: URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        let dir = docs.appendingPathComponent("Projects", isDirectory: true)
        if !FileManager.default.fileExists(atPath: dir.path) {
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        return dir
    }
    
    public func getProjects() async throws -> [ChatSummary] {
        let dir = projectsDirectory
        let files = try FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.creationDateKey])
        var summaries: [(ChatSummary, Date)] = []
        let decoder = JSONDecoder()
        
        for file in files where file.pathExtension == "json" {
            if let data = try? Data(contentsOf: file),
               let project = try? decoder.decode(MobileProject.self, from: data) {
                let attrs = try? FileManager.default.attributesOfItem(atPath: file.path)
                let creationDate = attrs?[.creationDate] as? Date ?? Date.distantPast
                summaries.append((ChatSummary(id: project.id, title: project.title), creationDate))
            }
        }
        
        return summaries.sorted { $0.1 > $1.1 }.map { $0.0 }
    }
    
    public func getProject(id: String) async throws -> MobileProject {
        let file = projectsDirectory.appendingPathComponent("\(id).json")
        let data = try Data(contentsOf: file)
        let decoder = JSONDecoder()
        return try decoder.decode(MobileProject.self, from: data)
    }
    
    public func syncProject(project: MobileProject) async throws {
        let file = projectsDirectory.appendingPathComponent("\(project.id).json")
        let encoder = JSONEncoder()
        encoder.outputFormatting = .prettyPrinted
        let data = try encoder.encode(project)
        try data.write(to: file, options: .atomic)
    }
    
    public func deleteProject(id: String) async throws {
        if let project = try? await getProject(id: id) {
            for source in project.sources {
                if let url = source.resolvedUrl, url.isFileURL {
                    try? FileManager.default.removeItem(at: url)
                }
            }
        }
        let file = projectsDirectory.appendingPathComponent("\(id).json")
        try? FileManager.default.removeItem(at: file)
        
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        let schemaFile = docs.appendingPathComponent("\(id)_composition.json")
        try? FileManager.default.removeItem(at: schemaFile)
    }
}
