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
    
    public init(width: Int, height: Int, codec: String = "video/avc", bitrate: Int = 16_000_000) {
        self.width = width
        self.height = height
        self.codec = codec
        self.bitrate = bitrate
    }
    
    public static func resolveDimensions(source: SourceMedia, operations: [EditOperation], current: ExportSettings? = nil) -> ExportSettings {
        let effectiveWidth = source.width
        let effectiveHeight = source.height
        
        let shortSide = min(effectiveWidth, effectiveHeight)
        let longSide = max(effectiveWidth, effectiveHeight)
        
        let ratio = Float(effectiveWidth) / Float(effectiveHeight)
        var targetRatio: AspectRatio
        if ratio >= 0.9 && ratio <= 1.1 {
            targetRatio = .square
        } else if effectiveWidth > effectiveHeight {
            targetRatio = .landscape
        } else {
            targetRatio = .portrait
        }
        
        for op in operations {
            if case let .setAspectRatio(_, enabled, ratio) = op, enabled {
                targetRatio = ratio
            }
        }
        
        let outW: Int
        let outH: Int
        switch targetRatio {
        case .portrait:
            outW = shortSide
            outH = longSide
        case .square:
            outW = shortSide
            outH = shortSide
        case .landscape:
            outW = longSide
            outH = shortSide
        }
        
        return ExportSettings(
            width: outW,
            height: outH,
            codec: current?.codec ?? "video/avc",
            bitrate: current?.bitrate ?? 16_000_000
        )
    }
}

public struct VisualUsableRange: Codable, Equatable {
    public let startMs: Int64
    public let endMs: Int64
    
    public init(startMs: Int64 = 0, endMs: Int64 = 0) {
        self.startMs = startMs
        self.endMs = endMs
    }
}

public struct VisualSourceProfile: Codable, Equatable {
    public let summary: String
    public let setting: String
    public let shotScale: String
    public let subjects: [String]
    public let activity: String
    public let cameraMotion: String
    public let usableRange: VisualUsableRange?
    public let narrativeTags: [String]
    
    public init(
        summary: String = "",
        setting: String = "other",
        shotScale: String = "medium",
        subjects: [String] = [],
        activity: String = "",
        cameraMotion: String = "static",
        usableRange: VisualUsableRange? = nil,
        narrativeTags: [String] = []
    ) {
        self.summary = summary
        self.setting = setting
        self.shotScale = shotScale
        self.subjects = subjects
        self.activity = activity
        self.cameraMotion = cameraMotion
        self.usableRange = usableRange
        self.narrativeTags = narrativeTags
    }
}

public struct Analysis: Codable, Equatable {
    public let words: [TranscriptWord]
    public let captions: [CaptionCue]
    public let speechHints: [FumbleCandidate]
    public let visualProfiles: [String: VisualSourceProfile]
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
    
    public init(words: [TranscriptWord] = [], captions: [CaptionCue] = [], speechHints: [FumbleCandidate] = [], visualProfiles: [String: VisualSourceProfile] = [:], lowConfidenceFumbles: [FumbleCandidate] = [], takeGroups: [TakeGroup] = [], warnings: [String] = [], completedAtEpochMs: Int64? = nil, activeTurnId: String? = nil, activeCheckpointId: String? = nil, editorialPlan: StagedEditorialPlan? = nil, pendingProposal: PendingEditorialProposal? = nil, chatHistory: [EditorialConversationTurn] = [], settledConstraints: [SettledEditorialConstraint] = []) {
        self.words = words
        self.captions = captions
        self.speechHints = speechHints
        self.visualProfiles = visualProfiles
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
    public let schema: CompositionSchema?
    
    public init(operations: [EditOperation], sources: [SourceMedia], analysis: Analysis?, schema: CompositionSchema? = nil) {
        self.operations = operations
        self.sources = sources
        self.analysis = analysis
        self.schema = schema
    }
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
            self.export = ExportSettings.resolveDimensions(source: source, operations: operations)
        }
        
        self.revision = revision
    }
    
    public var orderedSources: [SourceMedia] {
        sources.isEmpty ? [source] : sources
    }
    
    public var durationMs: Int64 {
        orderedSources.reduce(0) { $0 + $1.durationMs }
    }
    
    public func snapshot(schema: CompositionSchema? = nil) -> ProjectSnapshot {
        return ProjectSnapshot(operations: operations, sources: orderedSources, analysis: analysis, schema: schema)
    }
    
    public func applyEditInstructions(next: [EditOperation], removedOperations: [String] = [], activeTurnId: String? = nil, request: String? = nil, response: String? = nil, currentSchema: CompositionSchema? = nil) -> MobileProject {
        var newHistory = analysis.chatHistory
        let currentTurnId = activeTurnId ?? UUID().uuidString
        if let req = request, let res = response {
            let turn = EditorialConversationTurn(id: currentTurnId, request: req, response: res, evidenceIds: [])
            newHistory.append(turn)
            if newHistory.count > 100 { newHistory = Array(newHistory.suffix(100)) }
        }
        
        let newAnalysis = Analysis(
            words: analysis.words,
            captions: analysis.captions,
            speechHints: analysis.speechHints,
            visualProfiles: analysis.visualProfiles,
            lowConfidenceFumbles: analysis.lowConfidenceFumbles,
            takeGroups: analysis.takeGroups,
            warnings: analysis.warnings,
            completedAtEpochMs: analysis.completedAtEpochMs,
            activeTurnId: currentTurnId,
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
        
        let finalOperations = self.operations.filter { !removedOperations.contains($0.id) } + processedNext
        let newExport = ExportSettings.resolveDimensions(source: source, operations: finalOperations, current: export)
        
        let newUndo = Array((undo + [snapshot(schema: currentSchema)]).suffix(30))
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
            operations: finalOperations,
            undo: newUndo,
            redo: [],
            export: newExport,
            revision: revision + 1
        )
    }
    
    public func restoreCheckpoint() -> MobileProject {
        guard let lastSnapshot = undo.last else { return self }
        let restoredSources = lastSnapshot.sources.isEmpty ? orderedSources : lastSnapshot.sources
        let newUndo = undo.dropLast()
        let newRedo = Array((redo + [snapshot()]).suffix(30))
        
        let newExport = ExportSettings.resolveDimensions(source: restoredSources.first ?? source, operations: lastSnapshot.operations, current: export)
        
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
            redo: Array(newRedo),
            export: newExport,
            revision: revision + 1
        )
    }
    
    public func redoCheckpoint() -> MobileProject {
        guard let nextSnapshot = redo.last else { return self }
        let restoredSources = nextSnapshot.sources.isEmpty ? orderedSources : nextSnapshot.sources
        let newRedo = redo.dropLast()
        let newUndo = Array((undo + [snapshot()]).suffix(30))
        
        let newExport = ExportSettings.resolveDimensions(source: restoredSources.first ?? source, operations: nextSnapshot.operations, current: export)
        
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
            export: newExport,
            revision: revision + 1
        )
    }
}
import Foundation

public struct CompositionSchema: Codable, Equatable {
    public var settings: CompositionSettings
    public var metadata: CompositionMetadata
    public var timeline: [TimelineNode]

    public init(settings: CompositionSettings, metadata: CompositionMetadata, timeline: [TimelineNode]) {
        self.settings = settings
        self.metadata = metadata
        self.timeline = timeline
    }
}

public struct CompositionSettings: Codable, Equatable {
    public var aspectRatio: String
    public var backgroundColor: String
    public var autoEnhance: Bool

    public init(aspectRatio: String = "16:9", backgroundColor: String = "#000000", autoEnhance: Bool = false) {
        self.aspectRatio = aspectRatio
        self.backgroundColor = backgroundColor
        self.autoEnhance = autoEnhance
    }
}

public struct CompositionMetadata: Codable, Equatable {
    public let transcripts: [TranscriptWordNode]
    public let silences: [TimeRangeNode]
    
    public init(transcripts: [TranscriptWordNode], silences: [TimeRangeNode]) {
        self.transcripts = transcripts
        self.silences = silences
    }
}

public struct TranscriptWordNode: Codable, Equatable {
    public let word: String
    public let startMs: Int64
    public let endMs: Int64
}

public struct TimeRangeNode: Codable, Equatable {
    public let startMs: Int64
    public let endMs: Int64
}

// Nodes
public enum TimelineNode: Codable, Equatable {
    case video(VideoNode)
    case text(TextNode)
    case effect(EffectNode)
    case audio(AudioNode)
    
    private enum CodingKeys: String, CodingKey {
        case type
    }
    
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let type = try container.decode(String.self, forKey: .type)
        
        switch type {
        case "video":
            self = .video(try VideoNode(from: decoder))
        case "text":
            self = .text(try TextNode(from: decoder))
        case "effect":
            self = .effect(try EffectNode(from: decoder))
        case "audio":
            self = .audio(try AudioNode(from: decoder))
        default:
            throw DecodingError.dataCorruptedError(forKey: .type, in: container, debugDescription: "Unknown node type: \(type)")
        }
    }
    
    public func encode(to encoder: Encoder) throws {
        switch self {
        case .video(let node):
            try node.encode(to: encoder)
        case .text(let node):
            try node.encode(to: encoder)
        case .effect(let node):
            try node.encode(to: encoder)
        case .audio(let node):
            try node.encode(to: encoder)
        }
    }
}

public struct VideoNode: Codable, Equatable {
    public let type: String = "video"
    public var id: String
    public var src: String
    public var startMs: Int64
    public var endMs: Int64
    public var sourceStartMs: Int64
    public var transition: String?

    public init(id: String, src: String, startMs: Int64, endMs: Int64, sourceStartMs: Int64, transition: String? = nil) {
        self.id = id
        self.src = src
        self.startMs = startMs
        self.endMs = endMs
        self.sourceStartMs = sourceStartMs
        self.transition = transition
    }
}

public struct TextSegment: Codable, Equatable {
    public var text: String
    public var color: String?
    
    public init(text: String, color: String? = nil) {
        self.text = text
        self.color = color
    }
}

public struct TextNodeStyle: Codable, Equatable {
    public var color: String?
    public var fontSize: CGFloat?
    public var posY: CGFloat?
    public var posX: CGFloat?
    public var scale: CGFloat?
    public var strokeColor: String?
    public var strokeWidth: CGFloat?
    public var backgroundColor: String?
    public var backgroundPadding: CGFloat?
    public var backgroundCornerRadius: CGFloat?
    
    public init(
        color: String? = nil,
        fontSize: CGFloat? = nil,
        posY: CGFloat? = nil,
        posX: CGFloat? = nil,
        scale: CGFloat? = nil,
        strokeColor: String? = nil,
        strokeWidth: CGFloat? = nil,
        backgroundColor: String? = nil,
        backgroundPadding: CGFloat? = nil,
        backgroundCornerRadius: CGFloat? = nil
    ) {
        self.color = color
        self.fontSize = fontSize
        self.posY = posY
        self.posX = posX
        self.scale = scale
        self.strokeColor = strokeColor
        self.strokeWidth = strokeWidth
        self.backgroundColor = backgroundColor
        self.backgroundPadding = backgroundPadding
        self.backgroundCornerRadius = backgroundCornerRadius
    }
}

public struct TextNode: Codable, Equatable {
    public let type: String = "text"
    public var id: String
    public var text: String
    public var startMs: Int64
    public var endMs: Int64
    public var style: TextNodeStyle?
    public var segments: [TextSegment]?
    public var visible: Bool?

    public var isVisible: Bool {
        return visible ?? true
    }

    private enum CodingKeys: String, CodingKey {
        case type, id, text, startMs, endMs, style, segments, visible, show, hidden
    }

    public init(id: String, text: String, startMs: Int64, endMs: Int64, style: TextNodeStyle? = nil, segments: [TextSegment]? = nil, visible: Bool? = nil) {
        self.id = id
        self.text = text
        self.startMs = startMs
        self.endMs = endMs
        self.style = style
        self.segments = segments
        self.visible = visible
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.id = try container.decode(String.self, forKey: .id)
        self.text = try container.decode(String.self, forKey: .text)
        self.startMs = try container.decode(Int64.self, forKey: .startMs)
        self.endMs = try container.decode(Int64.self, forKey: .endMs)
        self.style = try container.decodeIfPresent(TextNodeStyle.self, forKey: .style)
        self.segments = try container.decodeIfPresent([TextSegment].self, forKey: .segments)
        
        if let v = try container.decodeIfPresent(Bool.self, forKey: .visible) {
            self.visible = v
        } else if let s = try container.decodeIfPresent(Bool.self, forKey: .show) {
            self.visible = s
        } else if let h = try container.decodeIfPresent(Bool.self, forKey: .hidden) {
            self.visible = !h
        } else {
            self.visible = nil
        }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode("text", forKey: .type)
        try container.encode(id, forKey: .id)
        try container.encode(text, forKey: .text)
        try container.encode(startMs, forKey: .startMs)
        try container.encode(endMs, forKey: .endMs)
        try container.encodeIfPresent(style, forKey: .style)
        try container.encodeIfPresent(segments, forKey: .segments)
        try container.encodeIfPresent(visible, forKey: .visible)
    }
}

public struct EffectNode: Codable, Equatable {
    public let type: String = "effect"
    public var id: String
    public var effectName: String
    public var startMs: Int64
    public var endMs: Int64
    public var intensity: Float?

    public init(id: String, effectName: String, startMs: Int64, endMs: Int64, intensity: Float? = nil) {
        self.id = id
        self.effectName = effectName
        self.startMs = startMs
        self.endMs = endMs
        self.intensity = intensity
    }
}

public struct AudioNode: Codable, Equatable {
    public let type: String = "audio"
    public var id: String
    public var src: String
    public var startMs: Int64
    public var endMs: Int64
    public var autoDuck: Bool?
    public var volume: Float?

    public init(id: String, src: String, startMs: Int64, endMs: Int64, autoDuck: Bool? = nil, volume: Float? = nil) {
        self.id = id
        self.src = src
        self.startMs = startMs
        self.endMs = endMs
        self.autoDuck = autoDuck
        self.volume = volume
    }
}
