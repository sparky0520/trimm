import Foundation

public struct ColorGrade: Codable, Equatable {
    public let exposure: Float
    public let temperature: Float
    public let contrast: Float
    public let saturation: Float
    public let highlights: Float
    public let shadows: Float
    public let skinProtection: Float
    
    public init(exposure: Float = 0, temperature: Float = 0, contrast: Float = 1, saturation: Float = 1, highlights: Float = 0, shadows: Float = 0, skinProtection: Float = 0.8) {
        self.exposure = exposure
        self.temperature = temperature
        self.contrast = contrast
        self.saturation = saturation
        self.highlights = highlights
        self.shadows = shadows
        self.skinProtection = skinProtection
    }
}

public struct TimingCue: Codable, Equatable {
    public let sourceTimeMs: Int64
    public let description: String
}

public enum EditOperation: Codable, Equatable {
    case removeRanges(id: String, enabled: Bool, ranges: [TimeRange], confidence: Float, reason: String, pacing: String)
    case setColorGrade(id: String, enabled: Bool, grade: ColorGrade?, target: OperationTarget?, parameter: String?, value: Float?)
    case setAudioLevels(id: String, enabled: Bool, parameter: String?, dialogueLufs: Float, musicDuckDb: Float, attackMs: Int64, releaseMs: Int64, target: OperationTarget?)
    case setAspectRatio(id: String, enabled: Bool, ratio: AspectRatio)
    case addTextOverlay(id: String, enabled: Bool, text: String, target: OperationTarget?, designBlueprint: String)
    case addMotionPreset(id: String, enabled: Bool, range: TimeRange?, preset: String, intensity: Float)
    case addCaptions(id: String, enabled: Bool, cues: [CaptionCue], target: OperationTarget?, speakerId: String?, style: String)
    case addSectionTransition(id: String, enabled: Bool, target: OperationTarget?, preset: String, customBlueprint: String)
    case addBgm(id: String, enabled: Bool, target: OperationTarget?, prompt: String, reason: String, url: String)
    case addSfx(id: String, enabled: Bool, target: OperationTarget?, prompt: String, reason: String, url: String, timingCues: [TimingCue])
    
    private enum CodingKeys: String, CodingKey {
        case type, id, enabled, ranges, confidence, reason, pacing
        case grade, target, parameter, value, dialogueLufs, musicDuckDb, attackMs, releaseMs
        case ratio, text, designBlueprint, range, preset, intensity, cues, speakerId, style
        case customBlueprint, prompt, url, timingCues
    }
    
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let type = try container.decode(String.self, forKey: .type)
        let id = try container.decodeIfPresent(String.self, forKey: .id) ?? UUID().uuidString
        let enabled = try container.decodeIfPresent(Bool.self, forKey: .enabled) ?? true
        
        switch type {
        case "remove_ranges":
            self = .removeRanges(
                id: id, enabled: enabled,
                ranges: try container.decodeIfPresent([TimeRange].self, forKey: .ranges) ?? [],
                confidence: try container.decodeIfPresent(Float.self, forKey: .confidence) ?? 1.0,
                reason: try container.decodeIfPresent(String.self, forKey: .reason) ?? "",
                pacing: try container.decodeIfPresent(String.self, forKey: .pacing) ?? "natural"
            )
        case "set_color_grade":
            self = .setColorGrade(
                id: id, enabled: enabled,
                grade: try container.decodeIfPresent(ColorGrade.self, forKey: .grade),
                target: try container.decodeIfPresent(OperationTarget.self, forKey: .target),
                parameter: try container.decodeIfPresent(String.self, forKey: .parameter),
                value: try container.decodeIfPresent(Float.self, forKey: .value)
            )
        case "set_audio_levels":
            self = .setAudioLevels(
                id: id, enabled: enabled,
                parameter: try container.decodeIfPresent(String.self, forKey: .parameter),
                dialogueLufs: try container.decodeIfPresent(Float.self, forKey: .dialogueLufs) ?? -16.0,
                musicDuckDb: try container.decodeIfPresent(Float.self, forKey: .musicDuckDb) ?? -9.0,
                attackMs: try container.decodeIfPresent(Int64.self, forKey: .attackMs) ?? 120,
                releaseMs: try container.decodeIfPresent(Int64.self, forKey: .releaseMs) ?? 450,
                target: try container.decodeIfPresent(OperationTarget.self, forKey: .target)
            )
        case "set_aspect_ratio":
            self = .setAspectRatio(
                id: id, enabled: enabled,
                ratio: try container.decodeIfPresent(AspectRatio.self, forKey: .ratio) ?? .portrait
            )
        case "add_text_overlay":
            self = .addTextOverlay(
                id: id, enabled: enabled,
                text: try container.decode(String.self, forKey: .text),
                target: try container.decodeIfPresent(OperationTarget.self, forKey: .target),
                designBlueprint: try container.decodeIfPresent(String.self, forKey: .designBlueprint) ?? "{}"
            )
        case "add_motion_preset":
            self = .addMotionPreset(
                id: id, enabled: enabled,
                range: try container.decodeIfPresent(TimeRange.self, forKey: .range),
                preset: try container.decode(String.self, forKey: .preset),
                intensity: try container.decodeIfPresent(Float.self, forKey: .intensity) ?? 0.5
            )
        case "add_captions":
            self = .addCaptions(
                id: id, enabled: enabled,
                cues: try container.decodeIfPresent([CaptionCue].self, forKey: .cues) ?? [],
                target: try container.decodeIfPresent(OperationTarget.self, forKey: .target),
                speakerId: try container.decodeIfPresent(String.self, forKey: .speakerId),
                style: try container.decodeIfPresent(String.self, forKey: .style) ?? "bold-bottom"
            )
        case "add_section_transition":
            self = .addSectionTransition(
                id: id, enabled: enabled,
                target: try container.decodeIfPresent(OperationTarget.self, forKey: .target),
                preset: try container.decodeIfPresent(String.self, forKey: .preset) ?? "fade-in",
                customBlueprint: try container.decodeIfPresent(String.self, forKey: .customBlueprint) ?? "{}"
            )
        case "add_bgm":
            self = .addBgm(
                id: id, enabled: enabled,
                target: try container.decodeIfPresent(OperationTarget.self, forKey: .target),
                prompt: try container.decode(String.self, forKey: .prompt),
                reason: try container.decodeIfPresent(String.self, forKey: .reason) ?? "",
                url: try container.decodeIfPresent(String.self, forKey: .url) ?? ""
            )
        case "add_sfx":
            self = .addSfx(
                id: id, enabled: enabled,
                target: try container.decodeIfPresent(OperationTarget.self, forKey: .target),
                prompt: try container.decode(String.self, forKey: .prompt),
                reason: try container.decodeIfPresent(String.self, forKey: .reason) ?? "",
                url: try container.decodeIfPresent(String.self, forKey: .url) ?? "",
                timingCues: try container.decodeIfPresent([TimingCue].self, forKey: .timingCues) ?? []
            )
        default:
            throw DecodingError.dataCorruptedError(forKey: .type, in: container, debugDescription: "Unknown edit operation type: \(type)")
        }
    }
    
    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        switch self {
        case .removeRanges(let id, let enabled, let ranges, let confidence, let reason, let pacing):
            try container.encode("remove_ranges", forKey: .type)
            try container.encode(id, forKey: .id)
            try container.encode(enabled, forKey: .enabled)
            try container.encode(ranges, forKey: .ranges)
            try container.encode(confidence, forKey: .confidence)
            try container.encode(reason, forKey: .reason)
            try container.encode(pacing, forKey: .pacing)
        case .setColorGrade(let id, let enabled, let grade, let target, let parameter, let value):
            try container.encode("set_color_grade", forKey: .type)
            try container.encode(id, forKey: .id)
            try container.encode(enabled, forKey: .enabled)
            try container.encodeIfPresent(grade, forKey: .grade)
            try container.encodeIfPresent(target, forKey: .target)
            try container.encodeIfPresent(parameter, forKey: .parameter)
            try container.encodeIfPresent(value, forKey: .value)
        case .setAudioLevels(let id, let enabled, let parameter, let dialogueLufs, let musicDuckDb, let attackMs, let releaseMs, let target):
            try container.encode("set_audio_levels", forKey: .type)
            try container.encode(id, forKey: .id)
            try container.encode(enabled, forKey: .enabled)
            try container.encodeIfPresent(parameter, forKey: .parameter)
            try container.encode(dialogueLufs, forKey: .dialogueLufs)
            try container.encode(musicDuckDb, forKey: .musicDuckDb)
            try container.encode(attackMs, forKey: .attackMs)
            try container.encode(releaseMs, forKey: .releaseMs)
            try container.encodeIfPresent(target, forKey: .target)
        case .setAspectRatio(let id, let enabled, let ratio):
            try container.encode("set_aspect_ratio", forKey: .type)
            try container.encode(id, forKey: .id)
            try container.encode(enabled, forKey: .enabled)
            try container.encode(ratio, forKey: .ratio)
        case .addTextOverlay(let id, let enabled, let text, let target, let designBlueprint):
            try container.encode("add_text_overlay", forKey: .type)
            try container.encode(id, forKey: .id)
            try container.encode(enabled, forKey: .enabled)
            try container.encode(text, forKey: .text)
            try container.encodeIfPresent(target, forKey: .target)
            try container.encode(designBlueprint, forKey: .designBlueprint)
        case .addMotionPreset(let id, let enabled, let range, let preset, let intensity):
            try container.encode("add_motion_preset", forKey: .type)
            try container.encode(id, forKey: .id)
            try container.encode(enabled, forKey: .enabled)
            try container.encodeIfPresent(range, forKey: .range)
            try container.encode(preset, forKey: .preset)
            try container.encode(intensity, forKey: .intensity)
        case .addCaptions(let id, let enabled, let cues, let target, let speakerId, let style):
            try container.encode("add_captions", forKey: .type)
            try container.encode(id, forKey: .id)
            try container.encode(enabled, forKey: .enabled)
            try container.encode(cues, forKey: .cues)
            try container.encodeIfPresent(target, forKey: .target)
            try container.encodeIfPresent(speakerId, forKey: .speakerId)
            try container.encode(style, forKey: .style)
        case .addSectionTransition(let id, let enabled, let target, let preset, let customBlueprint):
            try container.encode("add_section_transition", forKey: .type)
            try container.encode(id, forKey: .id)
            try container.encode(enabled, forKey: .enabled)
            try container.encodeIfPresent(target, forKey: .target)
            try container.encode(preset, forKey: .preset)
            try container.encode(customBlueprint, forKey: .customBlueprint)
        case .addBgm(let id, let enabled, let target, let prompt, let reason, let url):
            try container.encode("add_bgm", forKey: .type)
            try container.encode(id, forKey: .id)
            try container.encode(enabled, forKey: .enabled)
            try container.encodeIfPresent(target, forKey: .target)
            try container.encode(prompt, forKey: .prompt)
            try container.encode(reason, forKey: .reason)
            try container.encode(url, forKey: .url)
        case .addSfx(let id, let enabled, let target, let prompt, let reason, let url, let timingCues):
            try container.encode("add_sfx", forKey: .type)
            try container.encode(id, forKey: .id)
            try container.encode(enabled, forKey: .enabled)
            try container.encodeIfPresent(target, forKey: .target)
            try container.encode(prompt, forKey: .prompt)
            try container.encode(reason, forKey: .reason)
            try container.encode(url, forKey: .url)
            try container.encode(timingCues, forKey: .timingCues)
        }
    }
}
