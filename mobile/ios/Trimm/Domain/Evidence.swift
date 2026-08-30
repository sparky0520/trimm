import Foundation

public struct EvidenceGraphReference: Codable, Equatable {
    public let sourceFingerprint: String
    public let schemaVersion: Int
    public let relativeCachePath: String
    public let analyzerVersions: [String: String]
    public let partial: Bool
    
    public init(sourceFingerprint: String, schemaVersion: Int = 1, relativeCachePath: String, analyzerVersions: [String: String], partial: Bool) {
        self.sourceFingerprint = sourceFingerprint
        self.schemaVersion = schemaVersion
        self.relativeCachePath = relativeCachePath
        self.analyzerVersions = analyzerVersions
        self.partial = partial
    }
}

public struct FumbleCandidate: Codable, Equatable {
    public let range: TimeRange
    public let confidence: Float
    public let reason: String
    public let contextBefore: String
    public let context: String
    public let contextAfter: String
    public let transcriptConfidence: Float?
    public let speakerIds: [String]
    public let provenance: String
    
    public init(range: TimeRange, confidence: Float, reason: String, contextBefore: String = "", context: String = "", contextAfter: String = "", transcriptConfidence: Float? = nil, speakerIds: [String] = [], provenance: String = "local-transcript-timing") {
        self.range = range
        self.confidence = confidence
        self.reason = reason
        self.contextBefore = contextBefore
        self.context = context
        self.contextAfter = contextAfter
        self.transcriptConfidence = transcriptConfidence
        self.speakerIds = speakerIds
        self.provenance = provenance
    }
}

public struct TakeGroup: Codable, Equatable {
    public let id: String
    public let bestTakeId: String
    public let takes: [Take]
}

public struct Take: Codable, Equatable {
    public let id: String
    public let range: TimeRange
    public let transcript: String
    public let rating: Float
    public let faults: [String]
}
