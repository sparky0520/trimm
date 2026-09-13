import Foundation

public enum InstructionValidator {
    public static func isRendered(operation: EditOperation) -> Bool {
        switch operation {
        case .addMotionPreset: return false
        default: return true
        }
    }
    
    public static func isPlannerAvailable(operation: EditOperation) -> Bool {
        return isRendered(operation: operation)
    }
    
    public static func visible(operations: [EditOperation]) -> [EditOperation] {
        return operations.filter(isRendered)
    }
    
    public static func renderFingerprint(operations: [EditOperation]) -> [String] {
        return operations.filter {
            switch $0 {
            case .removeRanges(_, let enabled, _, _, _, _): return enabled
            case .setColorGrade(_, let enabled, _, _, _, _): return enabled
            case .setAudioLevels(_, let enabled, _, _, _, _, _, _): return enabled
            case .setAspectRatio(_, let enabled, _): return enabled
            case .addTextOverlay(_, let enabled, _, _, _): return enabled
            case .addMotionPreset(_, let enabled, _, _, _): return enabled
            case .addCaptions(_, let enabled, _, _, _, _): return enabled
            case .addSectionTransition(_, let enabled, _, _, _): return enabled
            case .addBgm(_, let enabled, _, _, _, _): return enabled
            case .addBgmEffect(_, let enabled, _, _, _, _): return enabled
            case .addSfx(_, let enabled, _, _, _, _): return enabled
            }
        }.filter(isRendered).map { operation in
            switch operation {
            case .removeRanges(_, _, let ranges, _, _, _):
                let rangesStr = ranges.map { "\($0.startMs)-\($0.endMs)" }.joined(separator: ",")
                return "timeline-range:[\(rangesStr)]"
            case .setColorGrade(_, _, let grade, let target, let parameter, let value):
                let targetStr = target?.sourceId ?? "nil"
                return "video-color:\(String(describing: grade)):\(targetStr):\(parameter ?? "nil"):\(String(describing: value))"
            case .setAudioLevels(_, _, let parameter, let dialogueLufs, let musicDuckDb, _, _, _):
                return "dialogue-levels:\(dialogueLufs):\(musicDuckDb):\(parameter ?? "")"
            case .setAspectRatio(_, _, let ratio):
                return "video-aspect-ratio:\(ratio.rawValue)"
            case .addTextOverlay(let id, _, let text, _, _):
                return "video-text-overlay:\(id):\(text)"
            case .addCaptions(_, _, let cues, _, _, let style):
                return "video-captions:\(cues.count):\(style)"
            case .addSectionTransition(let id, _, _, let preset, _):
                return "video-transition:\(id):\(preset)"
            case .addBgm(_, _, _, let prompt, _, let url):
                return "audio-bgm:\(prompt):\(url)"
            case .addBgmEffect(_, _, let target, let prompt, _, let url):
                let targetStr = "\(target?.startMs ?? 0):\(target?.endMs ?? 0)"
                return "audio-bgm-effect:\(targetStr):\(prompt):\(url)"
            case .addSfx(_, _, let target, let prompt, _, let url):
                let targetStr = "\(target?.startMs ?? 0):\(target?.endMs ?? 0)"
                return "audio-sfx:\(targetStr):\(prompt):\(url)"
            case .addMotionPreset:
                fatalError("Unrendered operation reached render contract")
            }
        }
    }
    
    public static func basis(operation: EditOperation) -> String {
        switch operation {
        case .removeRanges(_, _, _, _, let reason, _):
            return "Transcript evidence • \(reason.isEmpty ? "Range removal" : reason)"
        case .setColorGrade: return "Frame evidence"
        case .setAudioLevels(_, _, _, let dialogueLufs, _, _, _, _):
            return "Default • \(Int(dialogueLufs)) LUFS dialogue target"
        case .setAspectRatio(_, _, let ratio):
            return "Aspect ratio: \(ratio.rawValue)"
        case .addTextOverlay: return "Custom Text Overlay Graphics"
        case .addBgm(_, _, _, let prompt, _, _): return "Generated • \(prompt)"
        case .addBgmEffect(_, _, _, let prompt, _, _): return "Generated • \(prompt)"
        case .addSfx(_, _, _, let prompt, _, _): return "Generated • \(prompt)"
        case .addSectionTransition(_, _, _, let preset, _): return "Transition • \(preset)"
        case .addMotionPreset: return "Unavailable • not rendered"
        case .addCaptions(_, _, let cues, _, _, _): return "Transcript evidence • \(cues.count) timed cues"
        }
    }
    
    public static func validate(project: MobileProject, proposed: [EditOperation], expectedRevision: Int64? = nil) throws -> [EditOperation] {
        let rev = expectedRevision ?? project.revision
        guard rev == project.revision else { throw NSError(domain: "InstructionValidator", code: 1, userInfo: [NSLocalizedDescriptionKey: "Stale edit proposal"]) }
        
        let ids = proposed.compactMap { op -> String? in
            switch op {
            case .removeRanges(let id, _, _, _, _, _): return id
            case .setColorGrade(let id, _, _, _, _, _): return id
            case .setAudioLevels(let id, _, _, _, _, _, _, _): return id
            case .setAspectRatio(let id, _, _): return id
            case .addTextOverlay(let id, _, _, _, _): return id
            case .addMotionPreset(let id, _, _, _, _): return id
            case .addCaptions(let id, _, _, _, _, _): return id
            case .addSectionTransition(let id, _, _, _, _): return id
            case .addBgm(let id, _, _, _, _, _): return id
            case .addBgmEffect(let id, _, _, _, _, _): return id
            case .addSfx(let id, _, _, _, _, _): return id
            }
        }
        guard Set(ids).count == ids.count else { throw NSError(domain: "InstructionValidator", code: 2, userInfo: [NSLocalizedDescriptionKey: "Duplicate operation id"]) }
        guard proposed.allSatisfy(isPlannerAvailable) else { throw NSError(domain: "InstructionValidator", code: 3, userInfo: [NSLocalizedDescriptionKey: "Operation is unavailable without render support or frame evidence"]) }
        
        let projectDuration = project.orderedSources.reduce(0) { $0 + $1.durationMs }
        var sourceOffsets: [String: Int64] = [:]
        var offset: Int64 = 0
        for (index, source) in project.orderedSources.enumerated() {
            sourceOffsets["\(source.fingerprint)-\(index)"] = offset
            if sourceOffsets[source.fingerprint] == nil {
                sourceOffsets[source.fingerprint] = offset
            }
            offset += source.durationMs
        }
        
        var resolved = [EditOperation]()
        for op in proposed {
            switch op {
            case .addCaptions(let id, let enabled, let cues, let target, let speakerId, let style):
                if cues.isEmpty {
                    let words = project.analysis.words.filter { word in
                        let matchesSpeaker = speakerId == nil || word.speakerId == speakerId
                        var matchesTarget = target == nil
                        if let tgt = target, let targetOffset = sourceOffsets[tgt.sourceId] {
                            matchesTarget = word.range.startMs >= targetOffset + tgt.startMs && word.range.endMs <= targetOffset + tgt.endMs
                        }
                        return matchesSpeaker && matchesTarget
                    }
                    resolved.append(.addCaptions(id: id, enabled: enabled, cues: EditEngines.CaptionSegmenter.segment(words: words), target: target, speakerId: speakerId, style: style))
                } else {
                    resolved.append(op)
                }
            case .addMotionPreset(let id, let enabled, let range, let preset, let intensity):
                if range == nil {
                    resolved.append(.addMotionPreset(id: id, enabled: enabled, range: TimeRange(startMs: 0, endMs: projectDuration), preset: preset, intensity: intensity))
                } else {
                    resolved.append(op)
                }
            default:
                resolved.append(op)
            }
        }
        
        for op in resolved {
            switch op {
            case .removeRanges(_, _, let ranges, let confidence, _, _):
                guard confidence >= 0 && confidence <= 1.0 else { throw NSError(domain: "InstructionValidator", code: 4) }
                guard !ranges.isEmpty else { throw NSError(domain: "InstructionValidator", code: 4) }
                for r in ranges { guard r.endMs <= projectDuration else { throw NSError(domain: "InstructionValidator", code: 4) } }
            case .setColorGrade(_, _, let grade, let target, let parameter, let value):
                guard grade != nil || (parameter != nil && value != nil) else { throw NSError(domain: "InstructionValidator", code: 4) }
                try validateTarget(project: project, target: target)
            case .setAudioLevels(_, _, _, _, _, let attackMs, let releaseMs, let target):
                guard attackMs >= 0 && releaseMs >= 0 else { throw NSError(domain: "InstructionValidator", code: 4) }
                try validateTarget(project: project, target: target)
            case .addMotionPreset(_, _, let range, _, let intensity):
                guard let r = range, r.endMs <= projectDuration, intensity >= 0 && intensity <= 1.0 else { throw NSError(domain: "InstructionValidator", code: 4) }
            case .addCaptions(_, _, let cues, _, _, _):
                guard !cues.isEmpty else { throw NSError(domain: "InstructionValidator", code: 4) }
                for cue in cues {
                    guard cue.range.endMs <= projectDuration, !cue.text.isEmpty, cue.text.count <= 120 else { throw NSError(domain: "InstructionValidator", code: 4) }
                }
            case .addTextOverlay(_, _, let text, let target, _):
                guard !text.isEmpty else { throw NSError(domain: "InstructionValidator", code: 4) }
                try validateTarget(project: project, target: target)
            case .addBgm(_, _, let target, let prompt, _, _),
                 .addBgmEffect(_, _, let target, let prompt, _, _),
                 .addSfx(_, _, let target, let prompt, _, _):
                guard !prompt.isEmpty else { throw NSError(domain: "InstructionValidator", code: 4) }
                try validateTarget(project: project, target: target)
            default: break
            }
        }
        
        return resolved
    }
    
    private static func validateTarget(project: MobileProject, target: OperationTarget?) throws {
        guard let target = target else { return }
        let fingerprint = String(target.sourceId.split(separator: "-").first ?? "")
        let source = project.orderedSources.first { $0.fingerprint == fingerprint }
        guard let source = source, target.endMs <= source.durationMs else {
            throw NSError(domain: "InstructionValidator", code: 5, userInfo: [NSLocalizedDescriptionKey: "Operation target is outside its source"])
        }
    }
}
