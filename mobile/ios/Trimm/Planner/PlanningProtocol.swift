import Foundation

public let LAYER1_PROTOCOL_VERSION = 1

public struct PlanningProgram: Codable, Equatable {
    public let protocolVersion: Int
    public let programId: String
    public let projectId: String
    public let baseRevision: Int64
    public let commands: [PlanningCommand]
    
    public init(protocolVersion: Int = LAYER1_PROTOCOL_VERSION, programId: String, projectId: String, baseRevision: Int64, commands: [PlanningCommand]) {
        self.protocolVersion = protocolVersion
        self.programId = programId
        self.projectId = projectId
        self.baseRevision = baseRevision
        self.commands = commands
    }
}

public struct NormalizedRect: Codable, Equatable {
    public let x: Float
    public let y: Float
    public let width: Float
    public let height: Float
    
    public init(x: Float, y: Float, width: Float, height: Float) {
        self.x = x
        self.y = y
        self.width = width
        self.height = height
    }
}

public enum PlanningCommand: Codable, Equatable {
    case probeAsset(id: String, sourceUri: String)
    case fingerprintAsset(id: String, sourceUri: String)
    case generateProxy(id: String, sourceUri: String, outputUri: String, maximumWidth: Int, maximumHeight: Int)
    case extractAudio(id: String, sourceUri: String, outputUri: String, range: TimeRange, codec: String, sampleRate: Int, channels: Int)
    case extractFrame(id: String, sourceUri: String, outputUri: String, atMs: Int64, maximumWidth: Int, maximumHeight: Int)
    case cropFrame(id: String, sourceUri: String, outputUri: String, atMs: Int64, crop: NormalizedRect)
    case sampleFrames(id: String, sourceUri: String, outputDirectoryUri: String, range: TimeRange, maximumCount: Int)
    case removeTimelineRanges(id: String, sourceFingerprint: String, ranges: [TimeRange], evidenceIds: [String])
    case insertTimelineRange(id: String, sourceFingerprint: String, sourceRange: TimeRange, targetStartMs: Int64, evidenceIds: [String])
    case moveTimelineRange(id: String, timelineRange: TimeRange, targetStartMs: Int64)
    case transformClip(id: String, operationId: String, scale: Float, rotationDegrees: Float, crop: NormalizedRect?)
    case mixAudio(id: String, operationId: String, gainDb: Float, duckingDb: Float?)
    case applyCaptions(id: String, operationId: String, styleId: String)
    case applyEffect(id: String, operationId: String, effectId: String, intensity: Float)
    case renderPreview(id: String, outputUri: String)
    case renderExport(id: String, outputUri: String, width: Int, height: Int, codec: String, bitrate: Int)
    
    public var id: String {
        switch self {
        case .probeAsset(let id, _): return id
        case .fingerprintAsset(let id, _): return id
        case .generateProxy(let id, _, _, _, _): return id
        case .extractAudio(let id, _, _, _, _, _, _): return id
        case .extractFrame(let id, _, _, _, _, _): return id
        case .cropFrame(let id, _, _, _, _): return id
        case .sampleFrames(let id, _, _, _, _): return id
        case .removeTimelineRanges(let id, _, _, _): return id
        case .insertTimelineRange(let id, _, _, _, _): return id
        case .moveTimelineRange(let id, _, _): return id
        case .transformClip(let id, _, _, _, _): return id
        case .mixAudio(let id, _, _, _): return id
        case .applyCaptions(let id, _, _): return id
        case .applyEffect(let id, _, _, _): return id
        case .renderPreview(let id, _): return id
        case .renderExport(let id, _, _, _, _, _): return id
        }
    }
    
    public var requiredCapability: String {
        switch self {
        case .probeAsset: return "asset.probe"
        case .fingerprintAsset: return "asset.fingerprint"
        case .generateProxy: return "proxy.generate"
        case .extractAudio: return "audio.extract"
        case .extractFrame: return "frame.extract"
        case .cropFrame: return "frame.crop"
        case .sampleFrames: return "frames.sample"
        case .removeTimelineRanges: return "timeline.remove"
        case .insertTimelineRange: return "timeline.insert"
        case .moveTimelineRange: return "timeline.move"
        case .transformClip: return "clip.transform"
        case .mixAudio: return "audio.mix"
        case .applyCaptions: return "caption.apply"
        case .applyEffect: return "effect.apply"
        case .renderPreview: return "preview.render"
        case .renderExport: return "export.render"
        }
    }
}

public struct PlanningCapabilities {
    public let protocolVersions: Set<Int>
    public let commands: Set<String>
    public let codecs: Set<String>
    public let maximumSourceDurationMs: Int64
    public let maximumExportWidth: Int
    public let maximumExportHeight: Int
    
    public init(protocolVersions: Set<Int> = [LAYER1_PROTOCOL_VERSION], commands: Set<String>, codecs: Set<String>, maximumSourceDurationMs: Int64, maximumExportWidth: Int, maximumExportHeight: Int) {
        self.protocolVersions = protocolVersions
        self.commands = commands
        self.codecs = codecs
        self.maximumSourceDurationMs = maximumSourceDurationMs
        self.maximumExportWidth = maximumExportWidth
        self.maximumExportHeight = maximumExportHeight
    }
    
    public func supports(_ command: PlanningCommand) -> Bool {
        return commands.contains(command.requiredCapability)
    }
}

public struct PlanningPathPolicy {
    public let selectedMediaUris: Set<String>
    public let appOwnedRoots: Set<String>
    
    public init(selectedMediaUris: Set<String>, appOwnedRoots: Set<String>) {
        self.selectedMediaUris = selectedMediaUris
        self.appOwnedRoots = appOwnedRoots
    }
    
    public func permitsInput(_ value: String) -> Bool {
        return selectedMediaUris.contains(value) || permitsAppOwnedFile(value)
    }
    
    public func permitsOutput(_ value: String) -> Bool {
        return permitsAppOwnedFile(value)
    }
    
    private func permitsAppOwnedFile(_ value: String) -> Bool {
        var path = value
        if value.hasPrefix("file://") {
            path = String(value.dropFirst(7))
        } else if !value.hasPrefix("/") {
            return false
        }
        
        let candidate = URL(fileURLWithPath: path).standardizedFileURL.path
        return appOwnedRoots.contains { root in
            let canonicalRoot = URL(fileURLWithPath: root).standardizedFileURL.path
            return candidate == canonicalRoot || candidate.hasPrefix(canonicalRoot + "/")
        }
    }
}

public enum PlanningProtocolValidator {
    public enum Error: Swift.Error {
        case validationFailed(String)
    }
    
    public static func validate(program: PlanningProgram, project: MobileProject, capabilities: PlanningCapabilities, paths: PlanningPathPolicy) throws {
        try check(capabilities.protocolVersions.contains(program.protocolVersion), "Unsupported Layer 1 protocol version")
        try check(program.projectId == project.id, "Program targets another project")
        try check(program.baseRevision == project.revision, "Stale project revision")
        try check(!program.programId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, "Program id is required")
        try check(!program.commands.isEmpty, "Program must contain commands")
        try check(program.commands.allSatisfy { !$0.id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }, "Command id is required")
        try check(Set(program.commands.map { $0.id }).count == program.commands.count, "Duplicate command id")
        try check(program.commands.allSatisfy(capabilities.supports), "Unsupported Layer 1 capability")
        
        let projectDuration = project.orderedSources.reduce(0) { $0 + $1.durationMs }
        try check(projectDuration <= capabilities.maximumSourceDurationMs, "Project exceeds device duration capability")
        
        for command in program.commands {
            switch command {
            case .probeAsset(_, let sourceUri):
                try requireInput(sourceUri, paths)
            case .fingerprintAsset(_, let sourceUri):
                try requireInput(sourceUri, paths)
            case .generateProxy(_, let sourceUri, let outputUri, let width, let height):
                try requireInput(sourceUri, paths); try requireOutput(outputUri, paths)
                try check(width > 0 && height > 0, "Invalid proxy dimensions")
            case .extractAudio(_, let sourceUri, let outputUri, let range, let codec, let rate, let ch):
                try requireInput(sourceUri, paths); try requireOutput(outputUri, paths)
                try check(range.endMs <= projectDuration, "Audio range exceeds project")
                try check(capabilities.codecs.contains(codec) && rate >= 8000 && rate <= 48000 && ch >= 1 && ch <= 2, "Unsupported audio settings")
            case .extractFrame(_, let sourceUri, let outputUri, let atMs, let width, let height):
                try requireInput(sourceUri, paths); try requireOutput(outputUri, paths)
                try check(atMs >= 0 && atMs < projectDuration, "Frame timestamp exceeds project")
                try check(width > 0 && height > 0, "Invalid frame dimensions")
            case .cropFrame(_, let sourceUri, let outputUri, let atMs, let crop):
                try requireInput(sourceUri, paths); try requireOutput(outputUri, paths)
                try check(atMs >= 0 && atMs < projectDuration && validRect(crop), "Invalid frame crop")
            case .sampleFrames(_, let sourceUri, let outDir, let range, let maxCount):
                try requireInput(sourceUri, paths); try requireOutput(outDir, paths)
                try check(range.endMs <= projectDuration && maxCount >= 1 && maxCount <= 120, "Invalid frame sampling request")
            case .removeTimelineRanges(_, let fp, let ranges, _):
                try requireSource(fp, project)
                try check(!ranges.isEmpty && ranges.allSatisfy { $0.endMs <= projectDuration }, "Invalid removal range")
            case .insertTimelineRange(_, let fp, let sourceRange, let targetStartMs, _):
                try requireSource(fp, project)
                try check(sourceRange.endMs <= sourceDuration(fp, project), "Insert range exceeds source")
                try check(targetStartMs >= 0, "Invalid insert target")
            case .moveTimelineRange(_, let range, let targetStartMs):
                try check(range.endMs <= projectDuration && targetStartMs >= 0, "Invalid move range")
            case .transformClip(_, let opId, let scale, let rot, let crop):
                try check(!opId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && scale >= 0.1 && scale <= 4.0 && rot >= -360 && rot <= 360 && (crop == nil || validRect(crop!)), "Invalid clip transform")
            case .mixAudio(_, let opId, let gain, let duck):
                try check(!opId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && gain >= -60 && gain <= 24 && (duck == nil || (duck! >= 0 && duck! <= 30)), "Invalid audio mix")
            case .applyCaptions(_, let opId, let styleId):
                try check(!opId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && styleId.range(of: "^[a-z0-9-]{1,40}$", options: .regularExpression) != nil, "Invalid caption command")
            case .applyEffect(_, let opId, let effectId, let intensity):
                try check(!opId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && effectId.range(of: "^[a-z0-9-]{1,40}$", options: .regularExpression) != nil && intensity >= 0 && intensity <= 1, "Invalid effect command")
            case .renderPreview(_, let outputUri):
                try requireOutput(outputUri, paths)
            case .renderExport(_, let outputUri, let width, let height, let codec, let bitrate):
                try requireOutput(outputUri, paths)
                try check(width >= 1 && width <= capabilities.maximumExportWidth && height >= 1 && height <= capabilities.maximumExportHeight, "Unsupported export dimensions")
                try check(capabilities.codecs.contains(codec) && bitrate > 0, "Unsupported export settings")
            }
        }
    }
    
    private static func requireInput(_ uri: String, _ paths: PlanningPathPolicy) throws {
        try check(paths.permitsInput(uri), "Unsafe input path")
    }
    
    private static func requireOutput(_ uri: String, _ paths: PlanningPathPolicy) throws {
        try check(paths.permitsOutput(uri), "Unsafe output path")
    }
    
    private static func requireSource(_ fingerprint: String, _ project: MobileProject) throws {
        try check(project.orderedSources.contains { $0.fingerprint == fingerprint }, "Unknown source fingerprint")
    }
    
    private static func sourceDuration(_ fingerprint: String, _ project: MobileProject) throws -> Int64 {
        guard let source = project.orderedSources.first(where: { $0.fingerprint == fingerprint }) else {
            throw Error.validationFailed("Unknown source fingerprint")
        }
        return source.durationMs
    }
    
    private static func validRect(_ rect: NormalizedRect) -> Bool {
        return rect.x >= 0 && rect.y >= 0 && rect.width > 0 && rect.height > 0 && rect.x + rect.width <= 1 && rect.y + rect.height <= 1
    }
    
    private static func check(_ condition: Bool, _ message: String) throws {
        if !condition { throw Error.validationFailed(message) }
    }
}

public enum PlanningProgramApplier {
    public static func apply(program: PlanningProgram, project: MobileProject) -> MobileProject {
        if project.appliedProgramIds.contains(program.programId) { return project }
        
        let removals = program.commands.compactMap { command -> EditOperation? in
            if case .removeTimelineRanges(let id, _, let ranges, let evidenceIds) = command {
                return .removeRanges(
                    id: "layer1:\(id)",
                    enabled: true,
                    ranges: ranges,
                    confidence: 1.0,
                    reason: evidenceIds.first ?? "Layer 1 edit program",
                    pacing: "natural"
                )
            }
            return nil
        }
        
        if removals.isEmpty {
            // Need a way to mark program applied on project in iOS model
            // Not adding `appliedProgramIds` yet, assume no-op if no removals for now
            return project
        }
        
        let newOperations = project.operations + removals
        return project.applyEditInstructions(programId: program.programId, operations: newOperations)
    }
}

extension MobileProject {
    // Basic mock logic for `applyEditInstructions`
    func applyEditInstructions(programId: String, operations: [EditOperation]) -> MobileProject {
        // Here we would actually use InstructionValidator etc.
        // For now, return a new MobileProject with new operations
        return MobileProject(
            schemaVersion: self.schemaVersion,
            id: self.id,
            title: self.title,
            source: self.source,
            sources: self.sources,
            proxyUri: self.proxyUri,
            preferences: self.preferences,
            analysis: self.analysis,
            evidenceGraphs: self.evidenceGraphs,
            operations: operations,
            undo: self.undo,
            redo: self.redo,
            export: self.export,
            revision: self.revision + 1
        )
    }
    
    // Fallback for empty applied program list if not modified above
    var appliedProgramIds: [String] {
        return []
    }
}
