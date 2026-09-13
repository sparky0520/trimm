import Foundation
import CoreMedia



public class TranscriptionClient {
    private let endpoint: String
    private let accessToken: (_ forceRefresh: Bool) async throws -> String
    private let uploadsEndpoint: String
    
    public init(endpoint: String, accessToken: @escaping (_ forceRefresh: Bool) async throws -> String) {
        precondition(URL(string: endpoint)?.scheme == "https", "Transcription endpoint must use HTTPS")
        self.endpoint = endpoint
        self.accessToken = accessToken
        self.uploadsEndpoint = endpoint.hasSuffix("/") ? "\(endpoint)uploads" : "\(endpoint)/uploads"
    }
    
    public func transcribe(
        source: URL,
        contentType: String = "audio/mp4",
        onProgress: @escaping (_ uploadedBytes: Int64, _ totalBytes: Int64) async -> Void = { _, _ in }
    ) async throws -> [TranscriptWord] {
        var size: Int64 = 0
        do {
            let attrs = try FileManager.default.attributesOfItem(atPath: source.path)
            size = (attrs[.size] as? NSNumber)?.int64Value ?? 0
        } catch {
            throw NSError(domain: "Transcription", code: 1, userInfo: [NSLocalizedDescriptionKey: "The prepared transcription audio is unavailable."])
        }
        
        precondition(size > 0, "The prepared transcription audio is unavailable.")
        
        return try await transcribeMedia(size: size, contentType: contentType, openInput: {
            return InputStream(url: source)
        }, onProgress: onProgress)
    }
    
    private func transcribeMedia(
        size: Int64,
        contentType: String,
        openInput: () -> InputStream?,
        onProgress: @escaping (_ uploadedBytes: Int64, _ totalBytes: Int64) async -> Void
    ) async throws -> [TranscriptWord] {
        let session = try await createSession(contentType: contentType, size: size)
        var completed = false
        
        defer {
            if !completed {
                Task {
                    try? await deleteSession(id: session.id)
                }
            }
        }
        
        var offset = session.offset
        guard let input = openInput() else {
            throw NSError(domain: "Transcription", code: 2, userInfo: [NSLocalizedDescriptionKey: "Could not open source media for transcription."])
        }
        
        input.open()
        defer { input.close() }
        
        skipFully(input: input, bytes: offset)
        
        let chunkCapacity = min(max(session.chunkSize, 256 * 1024), 8 * 1024 * 1024)
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: chunkCapacity)
        defer { buffer.deallocate() }
        
        while offset < size {
            try Task.checkCancellation()
            let bytesToRead = min(Int64(chunkCapacity), size - offset)
            let readCount = input.read(buffer, maxLength: Int(bytesToRead))
            
            precondition(readCount > 0, "Media ended before its declared size.")
            
            let chunkData = Data(bytes: buffer, count: readCount)
            offset = try await uploadChunkWithRetry(id: session.id, initialOffset: offset, chunk: chunkData)
            await onProgress(offset, size)
        }
        
        let transcript = try await completeSession(id: session.id)
        completed = true
        
        return transcript.words.map { word in
            TranscriptWord(
                text: word.text,
                range: TimeRange(startMs: word.startMs, endMs: word.endMs),
                confidence: word.confidence,
                speakerId: word.speakerId
            )
        }
    }
    
    private func createSession(contentType: String, size: Int64) async throws -> UploadSession {
        var req = try await request(url: uploadsEndpoint, method: "POST", forceRefresh: true)
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let body = CreateUpload(contentType: contentType, size: size)
        req.httpBody = try JSONEncoder().encode(body)
        
        let (data, response) = try await URLSession.shared.data(for: req)
        try requireSuccess(response: response, data: data)
        return try JSONDecoder().decode(UploadSession.self, from: data)
    }
    
    private func uploadChunkWithRetry(id: String, initialOffset: Int64, chunk: Data) async throws -> Int64 {
        var offset = initialOffset
        for attempt in 0..<4 {
            try Task.checkCancellation()
            do {
                var req = try await request(url: "\(uploadsEndpoint)/\(id)", method: "PUT", forceRefresh: attempt > 0)
                req.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
                req.setValue(String(offset), forHTTPHeaderField: "Upload-Offset")
                req.httpBody = chunk
                
                let (data, response) = try await URLSession.shared.data(for: req)
                try requireSuccess(response: response, data: data)
                let uploadOffset = try JSONDecoder().decode(UploadOffset.self, from: data)
                return uploadOffset.offset
            } catch {
                if attempt == 3 { throw error }
                try await Task.sleep(nanoseconds: UInt64(500 * (attempt + 1)) * 1_000_000_000)
                let remOffset = try await remoteOffset(id: id)
                if remOffset == initialOffset + Int64(chunk.count) {
                    return remOffset
                }
                if remOffset != initialOffset {
                    throw NSError(domain: "Transcription", code: 4, userInfo: [NSLocalizedDescriptionKey: "Server returned an unsafe upload offset."])
                }
                offset = remOffset
            }
        }
        throw NSError(domain: "Transcription", code: 5, userInfo: [NSLocalizedDescriptionKey: "Upload retry limit reached."])
    }
    
    private func remoteOffset(id: String) async throws -> Int64 {
        let req = try await request(url: "\(uploadsEndpoint)/\(id)", method: "HEAD", forceRefresh: true)
        let (_, response) = try await URLSession.shared.data(for: req)
        try requireSuccess(response: response, data: nil)
        
        guard let httpResponse = response as? HTTPURLResponse,
              let offsetStr = httpResponse.value(forHTTPHeaderField: "Upload-Offset"),
              let offset = Int64(offsetStr) else {
            throw NSError(domain: "Transcription", code: 6, userInfo: [NSLocalizedDescriptionKey: "Missing upload offset."])
        }
        return offset
    }
    
    private func completeSession(id: String) async throws -> RemoteTranscript {
        var req = try await request(url: "\(uploadsEndpoint)/\(id)/complete", method: "POST", forceRefresh: true)
        req.timeoutInterval = 15 * 60
        let (data, response) = try await URLSession.shared.data(for: req)
        try requireSuccess(response: response, data: data)
        return try JSONDecoder().decode(RemoteTranscript.self, from: data)
    }
    
    private func deleteSession(id: String) async throws {
        let req = try await request(url: "\(uploadsEndpoint)/\(id)", method: "DELETE", forceRefresh: true)
        let (data, response) = try await URLSession.shared.data(for: req)
        if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode != 404 {
            try requireSuccess(response: response, data: data)
        }
    }
    
    private func request(url: String, method: String, forceRefresh: Bool) async throws -> URLRequest {
        guard let parsedUrl = URL(string: url) else {
            throw URLError(.badURL)
        }
        var req = URLRequest(url: parsedUrl)
        req.httpMethod = method
        req.timeoutInterval = 60
        let token = try await accessToken(forceRefresh)
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("no-store", forHTTPHeaderField: "Cache-Control")
        return req
    }
    
    private func requireSuccess(response: URLResponse, data: Data?) throws {
        guard let httpResponse = response as? HTTPURLResponse else { return }
        if !(200...299).contains(httpResponse.statusCode) {
            var msg = ""
            if let data = data, let text = String(data: data, encoding: .utf8) {
                msg = String(text.prefix(500))
            }
            throw NSError(domain: "Transcription", code: httpResponse.statusCode, userInfo: [NSLocalizedDescriptionKey: "Transcription upload failed (\(httpResponse.statusCode))\(msg.isEmpty ? "" : ": \(msg)")"])
        }
    }
    
    private func skipFully(input: InputStream, bytes: Int64) {
        var remaining = bytes
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: 1024)
        defer { buffer.deallocate() }
        
        while remaining > 0 {
            let toRead = min(remaining, 1024)
            let readCount = input.read(buffer, maxLength: Int(toRead))
            if readCount > 0 {
                remaining -= Int64(readCount)
            } else if readCount < 0 {
                break
            } else {
                // EOF
                preconditionFailure("Media ended before upload offset.")
            }
        }
    }
}
