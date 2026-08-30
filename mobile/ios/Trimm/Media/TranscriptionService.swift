import Foundation
import Speech
import AVFoundation

public class TranscriptionService {
    public static let shared = TranscriptionService()
    
    private init() {}
    
    public func requestAuthorization() async -> Bool {
        return await withCheckedContinuation { continuation in
            SFSpeechRecognizer.requestAuthorization { status in
                continuation.resume(returning: status == .authorized)
            }
        }
    }
    
    public func transcribe(url: URL) async throws -> [TranscriptWord] {
        let isAuthorized = await requestAuthorization()
        guard isAuthorized else {
            throw NSError(domain: "TranscriptionService", code: 1, userInfo: [NSLocalizedDescriptionKey: "Speech recognition not authorized"])
        }
        
        guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "en-US")), recognizer.isAvailable else {
            throw NSError(domain: "TranscriptionService", code: 2, userInfo: [NSLocalizedDescriptionKey: "Speech recognizer not available"])
        }
        
        let request = SFSpeechURLRecognitionRequest(url: url)
        request.shouldReportPartialResults = false
        if #available(iOS 16.0, *) {
            request.addsPunctuation = true
        }
        
        return try await withCheckedThrowingContinuation { continuation in
            var hasResumed = false
            recognizer.recognitionTask(with: request) { result, error in
                if let error = error {
                    if !hasResumed {
                        hasResumed = true
                        continuation.resume(throwing: error)
                    }
                    return
                }
                
                guard let result = result, result.isFinal else {
                    return
                }
                
                if !hasResumed {
                    hasResumed = true
                    let segments = result.bestTranscription.segments
                    let words: [TranscriptWord] = segments.map { segment in
                        let duration = Int64(segment.duration * 1000)
                        let start = Int64(segment.timestamp * 1000)
                        
                        return TranscriptWord(
                            text: segment.substring,
                            range: TimeRange(startMs: start, endMs: start + duration),
                            confidence: segment.confidence,
                            speakerId: nil
                        )
                    }
                    continuation.resume(returning: words)
                }
            }
        }
    }
}
