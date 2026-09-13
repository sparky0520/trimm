import Foundation
import AVFoundation
import AVFoundation

struct RemoteTranscriptWord: Codable {
    let text: String
    let startMs: Int64
    let endMs: Int64
    let confidence: Float
    let speakerId: String?
}

struct RemoteTranscript: Codable {
    let id: String
    let languageCode: String?
    let words: [RemoteTranscriptWord]
}

struct CreateUpload: Codable {
    let contentType: String
    let size: Int64
}

struct UploadSession: Codable {
    let id: String
    let offset: Int64
    let chunkSize: Int
}

struct UploadOffset: Codable {
    let offset: Int64
}

public class TranscriptionService {
    public static let shared = TranscriptionService()
    
    private let uploadsEndpoint = "\(Secrets.backendURL)/v1/transcribe/uploads"
    
    private init() {}
    
    public func transcribe(url: URL) async throws -> [TranscriptWord] {
        let asset = AVURLAsset(url: url)
        
        let audioUrl = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".m4a")
        defer { try? FileManager.default.removeItem(at: audioUrl) }
        
        guard let exportSession = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetAppleM4A) else {
            throw NSError(domain: "TranscriptionService", code: 1, userInfo: [NSLocalizedDescriptionKey: "Failed to create export session"])
        }
        
        exportSession.outputURL = audioUrl
        exportSession.outputFileType = .m4a
        await exportSession.export()
        
        guard exportSession.status == .completed else {
            throw NSError(domain: "TranscriptionService", code: 2, userInfo: [NSLocalizedDescriptionKey: "Audio extraction failed"])
        }
        
        let fileAttrs = try FileManager.default.attributesOfItem(atPath: audioUrl.path)
        guard let fileSize = fileAttrs[.size] as? Int64 else {
            throw NSError(domain: "TranscriptionService", code: 3, userInfo: [NSLocalizedDescriptionKey: "Cannot read file size"])
        }
        
        let session = try await createSession(contentType: "audio/mp4", size: fileSize)
        var offset = session.offset
        
        guard let fileHandle = try? FileHandle(forReadingFrom: audioUrl) else {
            throw NSError(domain: "TranscriptionService", code: 4, userInfo: [NSLocalizedDescriptionKey: "Cannot open audio file for reading"])
        }
        defer { try? fileHandle.close() }
        
        try fileHandle.seek(toOffset: UInt64(offset))
        
        while offset < fileSize {
            let chunkData = fileHandle.readData(ofLength: session.chunkSize)
            guard !chunkData.isEmpty else { break }
            
            offset = try await uploadChunkWithRetry(id: session.id, initialOffset: offset, chunk: chunkData)
        }
        
        let remoteTranscript = try await completeSession(id: session.id)
        
        return remoteTranscript.words.map {
            TranscriptWord(text: $0.text, range: TimeRange(startMs: $0.startMs, endMs: $0.endMs), confidence: $0.confidence, speakerId: $0.speakerId)
        }
    }
    
    private func getToken() async throws -> String {
        let session = try await supabase.auth.session
        return session.accessToken
    }
    
    private func createSession(contentType: String, size: Int64) async throws -> UploadSession {
        var req = URLRequest(url: URL(string: uploadsEndpoint)!)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("Bearer \(try await getToken())", forHTTPHeaderField: "Authorization")
        
        let body = CreateUpload(contentType: contentType, size: size)
        req.httpBody = try JSONEncoder().encode(body)
        
        let (data, response) = try await URLSession.shared.data(for: req)
        try requireSuccess(response, data: data)
        return try JSONDecoder().decode(UploadSession.self, from: data)
    }
    
    private func uploadChunkWithRetry(id: String, initialOffset: Int64, chunk: Data) async throws -> Int64 {
        var currentOffset = initialOffset
        for attempt in 0..<4 {
            do {
                var req = URLRequest(url: URL(string: "\(uploadsEndpoint)/\(id)")!)
                req.httpMethod = "PUT"
                req.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
                req.setValue("\(currentOffset)", forHTTPHeaderField: "Upload-Offset")
                req.setValue("Bearer \(try await getToken())", forHTTPHeaderField: "Authorization")
                req.httpBody = chunk
                
                let (data, response) = try await URLSession.shared.data(for: req)
                try requireSuccess(response, data: data)
                let result = try JSONDecoder().decode(UploadOffset.self, from: data)
                return result.offset
            } catch {
                if attempt == 3 { throw error }
                try await Task.sleep(nanoseconds: UInt64(500_000_000 * (attempt + 1)))
                
                let remoteOff = try await getRemoteOffset(id: id)
                if remoteOff == initialOffset + Int64(chunk.count) {
                    return remoteOff
                }
                currentOffset = remoteOff
            }
        }
        throw URLError(.cannotConnectToHost)
    }
    
    private func getRemoteOffset(id: String) async throws -> Int64 {
        var req = URLRequest(url: URL(string: "\(uploadsEndpoint)/\(id)")!)
        req.httpMethod = "HEAD"
        req.setValue("Bearer \(try await getToken())", forHTTPHeaderField: "Authorization")
        
        let (_, response) = try await URLSession.shared.data(for: req)
        try requireSuccess(response, data: nil)
        
        if let httpResp = response as? HTTPURLResponse,
           let offsetStr = httpResp.value(forHTTPHeaderField: "Upload-Offset"),
           let offset = Int64(offsetStr) {
            return offset
        }
        throw URLError(.badServerResponse)
    }
    
    private func completeSession(id: String) async throws -> RemoteTranscript {
        var req = URLRequest(url: URL(string: "\(uploadsEndpoint)/\(id)/complete")!)
        req.httpMethod = "POST"
        req.timeoutInterval = 15 * 60
        req.setValue("Bearer \(try await getToken())", forHTTPHeaderField: "Authorization")
        
        let (data, response) = try await URLSession.shared.data(for: req)
        try requireSuccess(response, data: data)
        return try JSONDecoder().decode(RemoteTranscript.self, from: data)
    }
    
    private func requireSuccess(_ response: URLResponse, data: Data?) throws {
        guard let httpResponse = response as? HTTPURLResponse else {
            throw URLError(.badServerResponse)
        }
        if !(200...299).contains(httpResponse.statusCode) {
            let msg = data.flatMap { String(data: $0, encoding: .utf8) } ?? ""
            throw NSError(domain: "TranscriptionService", code: httpResponse.statusCode, userInfo: [NSLocalizedDescriptionKey: "Upload failed: \(msg)"])
        }
    }
}
