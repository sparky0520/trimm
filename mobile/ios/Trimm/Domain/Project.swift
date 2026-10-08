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
    public let words: [TranscriptWord]?
}

public struct SourceMedia: Codable, Equatable {
    public let uri: String
    public let fingerprint: String
    public let durationMs: Int64
    public let width: Int
    public let height: Int
    public let rotation: Int
    public let hasAudio: Bool
    
    public var resolvedUrl: URL? {
        guard let url = URL(string: uri) else { return nil }
        if url.isFileURL {
            let filename = url.lastPathComponent
            if let documentDirectory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first {
                let possibleUrl = documentDirectory.appendingPathComponent(filename)
                if FileManager.default.fileExists(atPath: possibleUrl.path) {
                    return possibleUrl
                }
            }
        }
        return url
    }
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
    public let activeTurnId: String?
    public let activeCheckpointId: String?
    public let editorialPlan: StagedEditorialPlan?
    public let pendingProposal: PendingEditorialProposal?
    public let chatHistory: [EditorialConversationTurn]
    public let settledConstraints: [SettledEditorialConstraint]
    
    public init(words: [TranscriptWord] = [], captions: [CaptionCue] = [], speechHints: [FumbleCandidate] = [], lowConfidenceFumbles: [FumbleCandidate] = [], takeGroups: [TakeGroup] = [], warnings: [String] = [], completedAtEpochMs: Int64? = nil, activeTurnId: String? = nil, activeCheckpointId: String? = nil, editorialPlan: StagedEditorialPlan? = nil, pendingProposal: PendingEditorialProposal? = nil, chatHistory: [EditorialConversationTurn] = [], settledConstraints: [SettledEditorialConstraint] = []) {
        self.words = words
        self.captions = captions
        self.speechHints = speechHints
        self.lowConfidenceFumbles = lowConfidenceFumbles
        self.takeGroups = takeGroups
        self.warnings = warnings
        self.completedAtEpochMs = completedAtEpochMs
        self.activeTurnId = activeTurnId
        self.activeCheckpointId = activeCheckpointId
        self.editorialPlan = editorialPlan
        self.pendingProposal = pendingProposal
        self.chatHistory = chatHistory
        self.settledConstraints = settledConstraints
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
    public let redo: [ProjectSnapshot]
    public let export: ExportSettings
    public let revision: Int64
    
    public init(schemaVersion: Int = 1, id: String = UUID().uuidString, title: String, source: SourceMedia, sources: [SourceMedia] = [], proxyUri: String? = nil, preferences: WizardPreferences = WizardPreferences(), analysis: Analysis = Analysis(), evidenceGraphs: [EvidenceGraphReference] = [], operations: [EditOperation] = [], undo: [ProjectSnapshot] = [], redo: [ProjectSnapshot] = [], export: ExportSettings? = nil, revision: Int64 = 0) {
        self.schemaVersion = schemaVersion
        self.id = id
        self.title = title
        self.source = source
        self.sources = sources
        self.proxyUri = proxyUri
        self.preferences = preferences
        self.analysis = analysis
        self.evidenceGraphs = evidenceGraphs
        self.operations = operations
        self.undo = undo
        self.redo = redo
        
        if let providedExport = export {
            self.export = providedExport
        } else {
            self.export = ExportSettings(width: source.width > 0 ? source.width : 1920, height: source.height > 0 ? source.height : 1080)
        }
        
        self.revision = revision
    }
    
    public var orderedSources: [SourceMedia] {
        sources.isEmpty ? [source] : sources
    }
    
    public var durationMs: Int64 {
        orderedSources.reduce(0) { $0 + $1.durationMs }
    }
    
    public func snapshot() -> ProjectSnapshot {
        return ProjectSnapshot(operations: operations, sources: orderedSources, analysis: analysis)
    }
    
    public func applyEditInstructions(next: [EditOperation], removedOperations: [String] = [], activeTurnId: String? = nil, request: String? = nil, response: String? = nil) -> MobileProject {
        var newHistory = analysis.chatHistory
        if let req = request, let res = response {
            let turn = EditorialConversationTurn(request: req, response: res, evidenceIds: [])
            newHistory.append(turn)
            if newHistory.count > 100 { newHistory = Array(newHistory.suffix(100)) }
        }
        
        let newAnalysis = Analysis(
            words: analysis.words,
            captions: analysis.captions,
            speechHints: analysis.speechHints,
            lowConfidenceFumbles: analysis.lowConfidenceFumbles,
            takeGroups: analysis.takeGroups,
            warnings: analysis.warnings,
            completedAtEpochMs: analysis.completedAtEpochMs,
            activeTurnId: activeTurnId,
            activeCheckpointId: analysis.activeCheckpointId,
            editorialPlan: analysis.editorialPlan,
            pendingProposal: analysis.pendingProposal,
            chatHistory: newHistory,
            settledConstraints: analysis.settledConstraints
        )
        var processedNext = next
        for (index, op) in processedNext.enumerated() {
            if case let .addCaptions(id, enabled, cues, target, speakerId, designBlueprint) = op, cues.isEmpty {
                let generatedCues = EditEngines.CaptionSegmenter.segment(words: analysis.words)
                processedNext[index] = .addCaptions(id: id, enabled: enabled, cues: generatedCues, target: target, speakerId: speakerId, designBlueprint: designBlueprint)
            }
        }
        
        let newUndo = Array((undo + [snapshot()]).suffix(30))
        return MobileProject(
            schemaVersion: schemaVersion,
            id: id,
            title: title,
            source: source,
            sources: sources,
            proxyUri: proxyUri,
            preferences: preferences,
            analysis: newAnalysis,
            evidenceGraphs: evidenceGraphs,
            operations: self.operations.filter { !removedOperations.contains($0.id) } + processedNext,
            undo: newUndo,
            redo: [],
            export: export,
            revision: revision + 1
        )
    }
    
    public func restoreCheckpoint() -> MobileProject {
        guard let lastSnapshot = undo.last else { return self }
        let restoredSources = lastSnapshot.sources.isEmpty ? orderedSources : lastSnapshot.sources
        let newUndo = undo.dropLast()
        let newRedo = Array((redo + [snapshot()]).suffix(30))
        
        return MobileProject(
            schemaVersion: schemaVersion,
            id: id,
            title: title,
            source: restoredSources.first ?? source,
            sources: restoredSources,
            proxyUri: proxyUri,
            preferences: preferences,
            analysis: lastSnapshot.analysis ?? analysis,
            evidenceGraphs: evidenceGraphs,
            operations: lastSnapshot.operations,
            undo: Array(newUndo),
            redo: newRedo,
            export: export,
            revision: revision + 1
        )
    }
    
    public func redoCheckpoint() -> MobileProject {
        guard let nextSnapshot = redo.last else { return self }
        let restoredSources = nextSnapshot.sources.isEmpty ? orderedSources : nextSnapshot.sources
        let newRedo = redo.dropLast()
        let newUndo = Array((undo + [snapshot()]).suffix(30))
        
        return MobileProject(
            schemaVersion: schemaVersion,
            id: id,
            title: title,
            source: restoredSources.first ?? source,
            sources: restoredSources,
            proxyUri: proxyUri,
            preferences: preferences,
            analysis: nextSnapshot.analysis ?? analysis,
            evidenceGraphs: evidenceGraphs,
            operations: nextSnapshot.operations,
            undo: newUndo,
            redo: Array(newRedo),
            export: export,
            revision: revision + 1
        )
    }
}
