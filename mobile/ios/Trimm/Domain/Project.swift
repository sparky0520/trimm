import Foundation

public struct TimeRange: Codable, Equatable {
    public let startMs: Int64
    public let endMs: Int64
    public var durationMs: Int64 { endMs - startMs }
}

public struct TranscriptWord: Codable, Equatable {
    public let text: String
    public let range: TimeRange
    public let confidence: Float
    public let speakerId: String?
}

public struct CaptionCue: Codable, Equatable {
    public let text: String
    public let range: TimeRange
    public let words: [TranscriptWord]
}

public struct SourceMedia: Codable, Equatable {
    public let uri: String
    public let fingerprint: String
    public let durationMs: Int64
    public let width: Int
    public let height: Int
    public let rotation: Int
    public let hasAudio: Bool
}

public enum Platform: String, Codable { case youtube = "YOUTUBE", instagram = "INSTAGRAM", tiktok = "TIKTOK", linkedin = "LINKEDIN" }
public enum AspectRatio: String, Codable { case landscape = "LANDSCAPE", portrait = "PORTRAIT", square = "SQUARE" }
public enum Pace: String, Codable { case relaxed = "RELAXED", natural = "NATURAL", tight = "TIGHT" }
public enum VisualStyle: String, Codable { case natural = "NATURAL", warm = "WARM", clean = "CLEAN", punchy = "PUNCHY" }
public enum MusicChoice: String, Codable { case automatic = "AUTOMATIC", userFile = "USER_FILE", none = "NONE" }

public struct WizardPreferences: Codable, Equatable {
    public let platform: Platform
    public let aspectRatio: AspectRatio
    public let pace: Pace
    public let style: VisualStyle
    public let captions: Bool
    public let music: MusicChoice
    public let mustKeep: String
    
    public init(platform: Platform = .youtube, aspectRatio: AspectRatio = .landscape, pace: Pace = .natural, style: VisualStyle = .natural, captions: Bool = true, music: MusicChoice = .none, mustKeep: String = "") {
        self.platform = platform
        self.aspectRatio = aspectRatio
        self.pace = pace
        self.style = style
        self.captions = captions
        self.music = music
        self.mustKeep = mustKeep
    }
}

public struct OperationTarget: Codable, Equatable {
    public let sourceId: String
    public let startMs: Int64
    public let endMs: Int64
}

// EditOperation is defined in EditOperation.swift

public struct ExportSettings: Codable, Equatable {
    public let width: Int
    public let height: Int
    public let codec: String
    public let bitrate: Int
    
    public init(width: Int = 1920, height: Int = 1080, codec: String = "video/avc", bitrate: Int = 16_000_000) {
        self.width = width
        self.height = height
        self.codec = codec
        self.bitrate = bitrate
    }
}

public struct Analysis: Codable, Equatable {
    public let words: [TranscriptWord]
    public let captions: [CaptionCue]
    public let speechHints: [FumbleCandidate]
    public let lowConfidenceFumbles: [FumbleCandidate]
    public let takeGroups: [TakeGroup]
    public let warnings: [String]
    public let completedAtEpochMs: Int64?
    
    public init(words: [TranscriptWord] = [], captions: [CaptionCue] = [], speechHints: [FumbleCandidate] = [], lowConfidenceFumbles: [FumbleCandidate] = [], takeGroups: [TakeGroup] = [], warnings: [String] = [], completedAtEpochMs: Int64? = nil) {
        self.words = words
        self.captions = captions
        self.speechHints = speechHints
        self.lowConfidenceFumbles = lowConfidenceFumbles
        self.takeGroups = takeGroups
        self.warnings = warnings
        self.completedAtEpochMs = completedAtEpochMs
    }
}

public struct ProjectSnapshot: Codable, Equatable {
    public let operations: [EditOperation]
    public let sources: [SourceMedia]
    public let analysis: Analysis?
}

public struct MobileProject: Codable, Equatable {
    public let schemaVersion: Int
    public let id: String
    public let title: String
    public let source: SourceMedia
    public let sources: [SourceMedia]
    public let proxyUri: String?
    public let preferences: WizardPreferences
    public let analysis: Analysis
    public let evidenceGraphs: [EvidenceGraphReference]
    public let operations: [EditOperation]
    public let undo: [ProjectSnapshot]
    public let export: ExportSettings
    public let revision: Int64
    
    public var orderedSources: [SourceMedia] {
        sources.isEmpty ? [source] : sources
    }
}
