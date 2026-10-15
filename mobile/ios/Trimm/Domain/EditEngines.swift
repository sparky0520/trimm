import Foundation

public struct EditorialProfile: Codable, Equatable {
    // Stub to be expanded later in the Evidence Pipeline phase
    public let policy: EditorialPolicy
    public let distributions: [String: RobustDistribution]
}

public struct EditorialPolicy: Codable, Equatable {
    public let pauseCutPercentile: Float
    // other fields omitted for now
}

public struct RobustDistribution: Codable, Equatable {
    public let count: Int
    public let p75: Float
    public let p90: Float
    // other fields omitted for now
}

public enum EditEngines {
    public struct FumbleDetector {
        public static func detect(words: [TranscriptWord], durationMs: Int64, pace: Pace, profile: EditorialProfile? = nil) -> [FumbleCandidate] {
            if words.isEmpty { return [] }
            var out: [FumbleCandidate] = []
            
            let fallbackPauseLimit: Int64
            switch pace {
            case .tight: fallbackPauseLimit = 1100
            case .natural: fallbackPauseLimit = 1700
            case .relaxed: fallbackPauseLimit = 2400
            }
            
            var pauseLimit: Int64
            if let dist = profile?.distributions["pauseMs"] {
                if dist.count < 3 {
                    pauseLimit = fallbackPauseLimit
                } else if profile!.policy.pauseCutPercentile <= 0.65 {
                    pauseLimit = Int64(dist.p75)
                } else if profile!.policy.pauseCutPercentile <= 0.8 {
                    pauseLimit = Int64((dist.p75 + dist.p90) / 2)
                } else {
                    pauseLimit = Int64(dist.p90)
                }
                pauseLimit = max(700, min(4000, pauseLimit))
            } else {
                pauseLimit = fallbackPauseLimit
            }
            
            for (i, word) in words.enumerated() {
                if i < words.count - 1 {
                    let nextWord = words[i + 1]
                    let gap = nextWord.range.startMs - word.range.endMs
                    if gap >= pauseLimit {
                        let conf = min(0.98, 0.82 + Float(gap) / 20_000.0)
                        out.append(hint(words: words, indexes: [i, i + 1], range: TimeRange(startMs: word.range.endMs + 180, endMs: nextWord.range.startMs - 180), confidence: conf, reason: "pause-candidate"))
                    }
                }
                
                if word.confidence < 0.75 {
                    out.append(hint(words: words, indexes: [i], range: word.range, confidence: word.confidence, reason: "transcript-uncertainty"))
                }
                
                if i >= 2 {
                    let left = Array(words[(i-2)..<i])
                    let right = Array(words[i..<min(i+2, words.count)])
                    let phrase = left.map { $0.text.lowercased() }.joined(separator: " ")
                    let next = right.map { $0.text.lowercased() }.joined(separator: " ")
                    
                    let speakers = Set((left + right).compactMap { $0.speakerId })
                    if phrase == next && !next.isEmpty && speakers.count <= 1 {
                        let minConf = (left + right).map { $0.confidence }.min() ?? 0
                        out.append(hint(words: words, indexes: Array((i-2)..<min(i+2, words.count)), range: TimeRange(startMs: words[i-2].range.startMs, endMs: words[i].range.startMs), confidence: minConf, reason: "repeated-phrase-candidate"))
                    }
                }
            }
            
            // Need to distinct and sort
            var uniqueOut: [FumbleCandidate] = []
            var seen: Set<String> = []
            for candidate in out {
                let key = "\(candidate.range.startMs)-\(candidate.range.endMs)-\(candidate.reason)-\(candidate.context)"
                if !seen.contains(key) {
                    seen.insert(key)
                    uniqueOut.append(candidate)
                }
            }
            
            return uniqueOut.sorted {
                if $0.range.startMs != $1.range.startMs {
                    return $0.range.startMs < $1.range.startMs
                }
                return $0.reason < $1.reason
            }
        }
        
        private static func hint(words: [TranscriptWord], indexes: [Int], range: TimeRange, confidence: Float, reason: String) -> FumbleCandidate {
            let first = indexes.min() ?? 0
            let last = indexes.max() ?? first
            let selected = indexes.compactMap { $0 < words.count ? words[$0] : nil }
            
            let beforeWords = words[max(0, first - 4)..<first]
            let contextBefore = beforeWords.map { $0.text }.joined(separator: " ")
            let context = selected.map { $0.text }.joined(separator: " ")
            let afterWords = words[min(words.count, last + 1)..<min(words.count, last + 5)]
            let contextAfter = afterWords.map { $0.text }.joined(separator: " ")
            
            let minConf = selected.map { $0.confidence }.min()
            let speakerIds = Array(Set(selected.compactMap { $0.speakerId }))
            
            return FumbleCandidate(
                range: range,
                confidence: max(0, min(1, confidence)),
                reason: reason,
                contextBefore: contextBefore,
                context: context,
                contextAfter: contextAfter,
                transcriptConfidence: minConf,
                speakerIds: speakerIds
            )
        }
    }
    
    public struct TimelineMapper {
        public let removals: [TimeRange]
        public let speeds: [(TimeRange, Float)]
        
        public init(removals: [TimeRange], speedChanges: [(TimeRange, Float)] = []) {
            var mergedRemovals: [TimeRange] = []
            for range in removals.sorted(by: { $0.startMs < $1.startMs }) {
                if let last = mergedRemovals.last, range.startMs <= last.endMs {
                    mergedRemovals[mergedRemovals.count - 1] = TimeRange(startMs: last.startMs, endMs: max(last.endMs, range.endMs))
                } else {
                    mergedRemovals.append(range)
                }
            }
            self.removals = mergedRemovals
            self.speeds = speedChanges.sorted { $0.0.startMs < $1.0.startMs }
        }
        
        public func sourceToOutput(_ sourceMs: Int64) -> Int64 {
            var outputMs: Int64 = 0
            var currentSourceMs: Int64 = 0
            
            while currentSourceMs < sourceMs {
                if let removal = removals.first(where: { $0.startMs <= currentSourceMs && $0.endMs > currentSourceMs }) {
                    let advance = min(removal.endMs, sourceMs) - currentSourceMs
                    currentSourceMs += advance
                    continue
                }
                
                let nextRemoval = removals.first(where: { $0.startMs > currentSourceMs })?.startMs ?? sourceMs
                let speedChange = speeds.first(where: { $0.0.startMs <= currentSourceMs && $0.0.endMs > currentSourceMs })
                let nextSpeedChange = speeds.first(where: { $0.0.startMs > currentSourceMs })?.0.startMs ?? sourceMs
                let currentSpeedEnd = speedChange?.0.endMs ?? Int64.max
                
                let boundary = min(nextRemoval, min(nextSpeedChange, min(currentSpeedEnd, sourceMs)))
                let advance = boundary - currentSourceMs
                let speed = speedChange?.1 ?? 1.0
                
                outputMs += Int64(Float(advance) / speed)
                currentSourceMs += advance
            }
            return outputMs
        }
        
        public func outputToSource(_ outputMs: Int64) -> Int64 {
            var sourceMs: Int64 = 0
            var currentOutputMs: Int64 = 0
            
            while currentOutputMs < outputMs {
                if let removal = removals.first(where: { $0.startMs <= sourceMs && $0.endMs > sourceMs }) {
                    sourceMs = removal.endMs
                    continue
                }
                
                let speedChange = speeds.first(where: { $0.0.startMs <= sourceMs && $0.0.endMs > sourceMs })
                let nextSpeedChange = speeds.first(where: { $0.0.startMs > sourceMs })?.0.startMs ?? Int64.max
                let nextRemoval = removals.first(where: { $0.startMs > sourceMs })?.startMs ?? Int64.max
                let currentSpeedEnd = speedChange?.0.endMs ?? Int64.max
                
                let speed = speedChange?.1 ?? 1.0
                let maxAdvanceSource = min(nextRemoval, min(nextSpeedChange, currentSpeedEnd)) - sourceMs
                let maxAdvanceOutput = Int64(Float(maxAdvanceSource) / speed)
                
                let remainingOutput = outputMs - currentOutputMs
                if remainingOutput <= maxAdvanceOutput {
                    sourceMs += Int64(Float(remainingOutput) * speed)
                    currentOutputMs += remainingOutput
                } else {
                    sourceMs += maxAdvanceSource
                    currentOutputMs += maxAdvanceOutput
                }
            }
            return sourceMs
        }
    }
    
    public struct GradeEngine {
        public struct FrameStats {
            public let luma: Float
            public let warmth: Float
            public let contrast: Float
            public let skinConfidence: Float
            
            public init(luma: Float, warmth: Float, contrast: Float, skinConfidence: Float) {
                self.luma = luma
                self.warmth = warmth
                self.contrast = contrast
                self.skinConfidence = skinConfidence
            }
        }
        
        public static func natural(stats: [FrameStats], style: VisualStyle) -> ColorGrade {
            if stats.isEmpty { return ColorGrade() }
            
            let luma = stats.map { $0.luma }.reduce(0, +) / Float(stats.count)
            let warmth = stats.map { $0.warmth }.reduce(0, +) / Float(stats.count)
            let contrast = stats.map { $0.contrast }.reduce(0, +) / Float(stats.count)
            
            let styleWarmth: Float
            switch style {
            case .warm: styleWarmth = 0.16
            case .clean: styleWarmth = -0.04
            default: styleWarmth = 0
            }
            
            let punch: Float = style == .punchy ? 0.12 : 0
            
            return ColorGrade(
                exposure: max(-0.45, min(0.45, (0.52 - luma) * 0.9)),
                temperature: max(-0.3, min(0.3, styleWarmth - warmth * 0.35)),
                contrast: max(0.9, min(1.25, 1.08 - contrast * 0.12 + punch)),
                saturation: max(0.9, min(1.2, 1.04 + punch * 0.5)),
                highlights: -0.08,
                shadows: 0.08,
                skinProtection: 0.8
            )
        }
    }
    
    public struct CaptionSegmenter {
        private static let maxCharacters = 42
        private static let maxDurationMs: Int64 = 2800
        private static let gapBreakMs: Int64 = 650
        
        public static func segment(words: [TranscriptWord]) -> [CaptionCue] {
            if words.isEmpty { return [] }
            var result: [CaptionCue] = []
            var group: [TranscriptWord] = []
            
            func flush() {
                if group.isEmpty { return }
                var text = group.map { $0.text.trimmingCharacters(in: .whitespacesAndNewlines) }.joined(separator: " ")
                text = text.replacingOccurrences(of: "\\s+([,.!?])", with: "$1", options: .regularExpression)
                
                result.append(CaptionCue(text: text, range: TimeRange(startMs: group.first!.range.startMs, endMs: group.last!.range.endMs), words: group))
                group = []
            }
            
            let sortedWords = words.sorted { $0.range.startMs < $1.range.startMs }
            for word in sortedWords {
                let proposedLength = group.map { $0.text.count + 1 }.reduce(0, +) + word.text.count
                let proposedDuration = group.isEmpty ? 0 : word.range.endMs - group.first!.range.startMs
                let gap = group.last.map { word.range.startMs - $0.range.endMs } ?? 0
                
                if !group.isEmpty && (proposedLength > maxCharacters || proposedDuration > maxDurationMs || gap > gapBreakMs) {
                    flush()
                }
                
                group.append(word)
                if let lastChar = word.text.trimmingCharacters(in: .whitespacesAndNewlines).last,
                   [".", "!", "?"].contains(lastChar) && group.count >= 3 {
                    flush()
                }
            }
            flush()
            return result
        }
    }
}
