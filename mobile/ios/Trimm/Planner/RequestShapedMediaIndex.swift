import Foundation

public enum MediaIndexScope: String, Codable {
    case exact = "exact"
    case broadEditorial = "broad_editorial"
    case specialist = "specialist"
}

public enum SelectionReason: String, Codable {
    case userRequested = "user_requested"
    case representativeCoverage = "representative_coverage"
    case measuredCandidate = "measured_candidate"
    case uncertainty = "uncertainty"
    case agentRequested = "agent_requested"
}

public enum DerivativeKind: String, Codable {
    case audio = "audio"
    case frame = "frame"
    case crop = "crop"
    case text = "text"
    case evidence = "evidence"
}

public enum SignalKind: String, Codable {
    case speech = "speech"
    case sound = "sound"
    case visual = "visual"
    case motion = "motion"
    case text = "text"
    case technical = "technical"
}

public struct MediaIndexSegment: Codable, Equatable {
    public let id: String
    public let sourceId: String
    public let startMs: Int64
    public let endMs: Int64
    public let signalKinds: [SignalKind]
    public let observationIds: [String]
    
    public init(id: String, sourceId: String, startMs: Int64, endMs: Int64, signalKinds: [SignalKind], observationIds: [String]) {
        self.id = id
        self.sourceId = sourceId
        self.startMs = startMs
        self.endMs = endMs
        self.signalKinds = signalKinds
        self.observationIds = observationIds
    }
}

public struct MediaIndexSelection: Codable, Equatable {
    public let id: String
    public let sourceId: String
    public let startMs: Int64
    public let endMs: Int64
    public let reasons: [SelectionReason]
    public let observationIds: [String]
    public let derivativeKinds: [DerivativeKind]
    
    public init(id: String, sourceId: String, startMs: Int64, endMs: Int64, reasons: [SelectionReason], observationIds: [String] = [], derivativeKinds: [DerivativeKind]) {
        self.id = id
        self.sourceId = sourceId
        self.startMs = startMs
        self.endMs = endMs
        self.reasons = reasons
        self.observationIds = observationIds
        self.derivativeKinds = derivativeKinds
    }
}

public struct RequestShapedMediaIndex: Codable, Equatable {
    public let schemaVersion: Int
    public let indexId: String
    public let requestScope: MediaIndexScope
    public let generatedAtEpochMs: Int64
    public let segments: [MediaIndexSegment]
    public let selections: [MediaIndexSelection]
    
    public init(schemaVersion: Int = 1, indexId: String = UUID().uuidString, requestScope: MediaIndexScope, generatedAtEpochMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000), segments: [MediaIndexSegment], selections: [MediaIndexSelection]) {
        self.schemaVersion = schemaVersion
        self.indexId = indexId
        self.requestScope = requestScope
        self.generatedAtEpochMs = generatedAtEpochMs
        self.segments = segments
        self.selections = selections
    }
}

public struct RequestedRange {
    public let sourceId: String
    public let range: TimeRange
    public let reason: SelectionReason
    public let derivativeKinds: [DerivativeKind]
    
    public init(sourceId: String, range: TimeRange, reason: SelectionReason, derivativeKinds: [DerivativeKind] = [.audio, .frame]) {
        self.sourceId = sourceId
        self.range = range
        self.reason = reason
        self.derivativeKinds = derivativeKinds
    }
}

public struct MediaIndexRequirements {
    public let scope: MediaIndexScope
    public let requestedRanges: [RequestedRange]
    public let candidateRanges: [RequestedRange]
    public let uncertaintyRanges: [RequestedRange]
    public let maximumSelections: Int
    
    public init(scope: MediaIndexScope, requestedRanges: [RequestedRange] = [], candidateRanges: [RequestedRange] = [], uncertaintyRanges: [RequestedRange] = [], maximumSelections: Int = 24) {
        self.scope = scope
        self.requestedRanges = requestedRanges
        self.candidateRanges = candidateRanges
        self.uncertaintyRanges = uncertaintyRanges
        self.maximumSelections = maximumSelections
    }
}

public enum MediaIndexRequestPlanner {
    private static let broadTerms = ["publishable", "polish", "first cut", "best version", "shorten", "story"]
    private static let audioTerms = ["audio", "speech", "pause", "silence", "noise", "music", "loud", "volume"]
    private static let visualTerms = ["face", "frame", "screen", "ui", "text", "caption", "visual", "shot", "crop"]
    private static let timestamp = try! NSRegularExpression(pattern: "(?<!\\d)(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})(?!\\d)")

    public static func plan(request: String, sources: [(String, Int64)], maximumSelections: Int = 24) -> MediaIndexRequirements {
        precondition(!request.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        precondition(!sources.isEmpty && sources.allSatisfy { !$0.0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && $0.1 > 0 })
        
        let normalized = request.lowercased()
        let nsString = normalized as NSString
        let match = timestamp.firstMatch(in: normalized, range: NSRange(location: 0, length: nsString.length))
        
        var kinds = [DerivativeKind]()
        if audioTerms.contains(where: normalized.contains) { kinds.append(.audio) }
        if visualTerms.contains(where: normalized.contains) { kinds.append(contentsOf: [.frame, .crop, .text]) }
        if kinds.isEmpty { kinds.append(contentsOf: [.audio, .frame]) }
        kinds.append(.evidence)
        let uniqueKinds = Array(NSOrderedSet(array: kinds)) as! [DerivativeKind]
        
        if let match = match {
            let hStr = match.range(at: 1).location != NSNotFound ? nsString.substring(with: match.range(at: 1)) : "0"
            let mStr = nsString.substring(with: match.range(at: 2))
            let sStr = nsString.substring(with: match.range(at: 3))
            
            let h = Int64(hStr) ?? 0
            let m = Int64(mStr) ?? 0
            let s = Int64(sStr) ?? 0
            
            let requestedMs = (h * 3600 + m * 60 + s) * 1000
            let firstSource = sources[0]
            let sourceId = firstSource.0
            let duration = firstSource.1
            
            let start = max(requestedMs - 2000, 0)
            let end = min(requestedMs + 2000, duration)
            precondition(start < end, "Requested timestamp is outside the source")
            
            return MediaIndexRequirements(
                scope: .exact,
                requestedRanges: [RequestedRange(sourceId: sourceId, range: TimeRange(startMs: start, endMs: end), reason: .userRequested, derivativeKinds: uniqueKinds)],
                maximumSelections: maximumSelections
            )
        }
        
        let isBroad = broadTerms.contains(where: normalized.contains)
        let scope: MediaIndexScope = isBroad ? .broadEditorial : .specialist
        let ranges = isBroad ? [] : sources.map { RequestedRange(sourceId: $0.0, range: TimeRange(startMs: 0, endMs: $0.1), reason: .userRequested, derivativeKinds: uniqueKinds) }
        
        return MediaIndexRequirements(
            scope: scope,
            requestedRanges: ranges,
            maximumSelections: maximumSelections
        )
    }
}

public enum RequestShapedMediaIndexBuilder {
    public static func build(graphs: [EvidenceGraph], requirements: MediaIndexRequirements) -> RequestShapedMediaIndex {
        precondition(!graphs.isEmpty)
        precondition(requirements.maximumSelections > 0)
        
        var bySource = [String: EvidenceGraph]()
        for graph in graphs { bySource[graph.sourceFingerprint] = graph }
        
        let explicit = requirements.requestedRanges + requirements.candidateRanges + requirements.uncertaintyRanges
        for item in explicit {
            guard let duration = bySource[item.sourceId]?.sourceDurationMs else {
                preconditionFailure("Unknown source \(item.sourceId)")
            }
            precondition(item.range.startMs >= 0 && item.range.endMs <= duration)
        }
        
        let inferredCandidates = requirements.scope == .broadEditorial ? measuredCandidateRanges(graphs: graphs) : []
        let inferredUncertainty = requirements.scope == .broadEditorial ? uncertaintyRanges(graphs: graphs) : []
        
        let representativeBudget = max(requirements.maximumSelections - explicit.count - inferredCandidates.count - inferredUncertainty.count, graphs.count)
        let representative = requirements.scope == .broadEditorial ? representativeRanges(graphs: graphs, budget: representativeBudget) : []
        
        let combined = explicit + roundRobin(groups: [representative, inferredCandidates, inferredUncertainty])
        let requested = Array(distinctRanges(ranges: combined).prefix(requirements.maximumSelections))
        
        let selections = requested.enumerated().map { (index, item) -> MediaIndexSelection in
            let observations = bySource[item.sourceId]?.observations.filter { $0.base.range.startMs < item.range.endMs && $0.base.range.endMs > item.range.startMs } ?? []
            return MediaIndexSelection(
                id: "selection-\(index + 1)",
                sourceId: item.sourceId,
                startMs: item.range.startMs,
                endMs: item.range.endMs,
                reasons: [item.reason],
                observationIds: observations.map { $0.base.id },
                derivativeKinds: item.derivativeKinds
            )
        }
        
        precondition(!selections.isEmpty)
        
        return RequestShapedMediaIndex(
            requestScope: requirements.scope,
            segments: graphs.flatMap { graph in graph.observations.map { toSegment(obs: $0.base, sourceFingerprint: graph.sourceFingerprint) } },
            selections: selections
        )
    }
    
    private static func roundRobin(groups: [[RequestedRange]]) -> [RequestedRange] {
        let maximum = groups.map { $0.count }.max() ?? 0
        var result = [RequestedRange]()
        for index in 0..<maximum {
            for group in groups {
                if index < group.count {
                    result.append(group[index])
                }
            }
        }
        return result
    }
    
    private static func distinctRanges(ranges: [RequestedRange]) -> [RequestedRange] {
        var merged = [String: RequestedRange]()
        var orderedKeys = [String]()
        
        for range in ranges {
            let key = "\(range.sourceId)|\(range.range.startMs)|\(range.range.endMs)|\(range.reason.rawValue)"
            if let previous = merged[key] {
                let newKinds = Array(NSOrderedSet(array: previous.derivativeKinds + range.derivativeKinds)) as! [DerivativeKind]
                merged[key] = RequestedRange(sourceId: range.sourceId, range: range.range, reason: range.reason, derivativeKinds: newKinds)
            } else {
                merged[key] = range
                orderedKeys.append(key)
            }
        }
        
        return orderedKeys.map { merged[$0]! }
    }
    
    private static func representativeRanges(graphs: [EvidenceGraph], budget: Int) -> [RequestedRange] {
        let perSource = max(budget / graphs.count, 1)
        return graphs.flatMap { graph -> [RequestedRange] in
            (0..<perSource).map { index in
                let start = graph.sourceDurationMs * Int64(index) / Int64(perSource)
                let end = graph.sourceDurationMs * Int64(index + 1) / Int64(perSource)
                return RequestedRange(sourceId: graph.sourceFingerprint, range: TimeRange(startMs: start, endMs: end), reason: .representativeCoverage, derivativeKinds: [.audio, .frame, .evidence])
            }
        }
    }
    
    private static func measuredCandidateRanges(graphs: [EvidenceGraph]) -> [RequestedRange] {
        return graphs.flatMap { graph -> [RequestedRange] in
            let sorted = graph.observations
                .filter { 
                    guard $0.base.confidence >= 0.7 else { return false }
                    if case .frame = $0 { return false }
                    return true
                }
                .sorted {
                    if $0.base.confidence != $1.base.confidence { return $0.base.confidence > $1.base.confidence }
                    if $0.base.range.startMs != $1.base.range.startMs { return $0.base.range.startMs < $1.base.range.startMs }
                    return $0.base.id < $1.base.id
                }
            
            return Array(sorted.prefix(4)).map { obs in
                RequestedRange(sourceId: graph.sourceFingerprint, range: obs.base.range, reason: .measuredCandidate, derivativeKinds: derivativeKindsFor(obs: obs.base))
            }
        }
    }
    
    private static func uncertaintyRanges(graphs: [EvidenceGraph]) -> [RequestedRange] {
        return graphs.flatMap { graph -> [RequestedRange] in
            let weak = graph.observations
                .filter { $0.base.confidence < 0.7 }
                .sorted {
                    if $0.base.confidence != $1.base.confidence { return $0.base.confidence < $1.base.confidence }
                    if $0.base.range.startMs != $1.base.range.startMs { return $0.base.range.startMs < $1.base.range.startMs }
                    return $0.base.id < $1.base.id
                }
            
            return Array(weak.prefix(4)).map { obs in
                let center = (obs.base.range.startMs + obs.base.range.endMs) / 2
                let start = max(center - 1500, 0)
                let end = min(center + 1500, graph.sourceDurationMs)
                return RequestedRange(sourceId: graph.sourceFingerprint, range: TimeRange(startMs: start, endMs: end), reason: .uncertainty, derivativeKinds: derivativeKindsFor(obs: obs.base))
            }
        }
    }
    
    private static func derivativeKindsFor(obs: EvidenceObservation) -> [DerivativeKind] {
        if obs is SpeechEvidence || obs is SoundEvidence {
            return [.audio, .evidence]
        }
        if let v = obs as? VisualEvidence {
            if v.kind == .ocrText {
                return [.frame, .crop, .text, .evidence]
            }
            return [.frame, .crop, .evidence]
        }
        if obs is FrameEvidence || obs is ShotEvidence {
            return [.frame, .evidence]
        }
        return []
    }
    
    private static func toSegment(obs: EvidenceObservation, sourceFingerprint: String) -> MediaIndexSegment {
        var signals = [SignalKind]()
        if obs is SpeechEvidence { signals = [.speech] }
        else if obs is SoundEvidence { signals = [.sound] }
        else if obs is FrameEvidence || obs is ShotEvidence { signals = [.visual] }
        else if let v = obs as? VisualEvidence {
            if v.kind == .motion || v.kind == .shake { signals = [.visual, .motion] }
            else if v.kind == .ocrText { signals = [.visual, .text] }
            else { signals = [.visual] }
        }
        
        return MediaIndexSegment(id: obs.id, sourceId: sourceFingerprint, startMs: obs.range.startMs, endMs: obs.range.endMs, signalKinds: signals, observationIds: [obs.id])
    }
}
