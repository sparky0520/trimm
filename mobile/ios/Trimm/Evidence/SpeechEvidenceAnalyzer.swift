import Foundation

public class SpeechEvidenceAnalyzer: EvidenceAnalyzer {
    public let identity = AnalyzerIdentity(name: "trimm speech structure", version: "1.0.0", execution: .externalService)
    
    private let words: [TranscriptWord]
    
    private static let disfluencies: Set<String> = ["um", "uh", "erm", "hmm"]
    
    public init(words: [TranscriptWord]) {
        self.words = words
    }
    
    public func analyze(context: AnalyzerContext) async throws -> [AnyEvidenceObservation] {
        let source = context.source
        
        // Filter valid words and cap their endMs to the source duration
        let valid = words.filter { $0.range.startMs < source.durationMs }.map { word -> TranscriptWord in
            TranscriptWord(
                text: word.text,
                range: TimeRange(startMs: word.range.startMs, endMs: min(word.range.endMs, source.durationMs)),
                confidence: word.confidence,
                speakerId: word.speakerId
            )
        }
        
        var result: [AnyEvidenceObservation] = []
        
        for word in valid {
            result.append(.speech(speech(source: source, range: word.range, kind: .word, confidence: word.confidence, text: word.text, speakerId: nil)))
        }
        
        for (range, sentenceWords) in sentenceRanges(words: valid) {
            let minConf = sentenceWords.map { $0.confidence }.min() ?? 0
            let text = sentenceWords.map { $0.text }.joined(separator: " ")
            result.append(.speech(speech(source: source, range: range, kind: .sentence, confidence: minConf, text: text, speakerId: nil)))
        }
        
        for (a, b) in zip(valid, valid.dropFirst()) {
            if b.range.startMs - a.range.endMs >= 350 {
                let range = TimeRange(startMs: a.range.endMs, endMs: b.range.startMs)
                result.append(.speech(speech(source: source, range: range, kind: .pause, confidence: 0.98, text: nil, speakerId: nil)))
            }
        }
        
        for word in valid {
            let normalized = word.text.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: ",.!?"))
            if Self.disfluencies.contains(normalized) {
                result.append(.speech(speech(source: source, range: word.range, kind: .disfluency, confidence: word.confidence, text: word.text, speakerId: nil)))
            }
        }
        
        for (range, regionWords) in speakerRegions(words: valid) {
            if let speakerId = regionWords.first?.speakerId {
                let minConf = regionWords.map { $0.confidence }.min() ?? 0
                result.append(.speech(speech(source: source, range: range, kind: .speakerRegion, confidence: minConf, text: nil, speakerId: speakerId)))
            }
        }
        
        return result
    }
    
    private func speech(source: SourceMedia, range: TimeRange, kind: SpeechKind, confidence: Float, text: String?, speakerId: String?) -> SpeechEvidence {
        let textPart = text ?? ""
        let id = stableEvidenceId(sourceFingerprint: source.fingerprint, analyzer: identity, kind: kind.rawValue, range: range, discriminator: textPart)
        let prov = EvidenceProvenance(analyzer: identity, sourceFingerprint: source.fingerprint)
        return SpeechEvidence(
            id: id,
            sourceFingerprint: source.fingerprint,
            range: range,
            confidence: min(max(confidence, 0), 1),
            provenance: prov,
            kind: kind,
            text: text,
            speakerId: speakerId
        )
    }
    
    private func sentenceRanges(words: [TranscriptWord]) -> [(TimeRange, [TranscriptWord])] {
        var groups: [[TranscriptWord]] = []
        var current: [TranscriptWord] = []
        
        for (index, word) in words.enumerated() {
            current.append(word)
            let next: TranscriptWord? = index + 1 < words.count ? words[index + 1] : nil
            
            if word.text.hasSuffix(".") || word.text.hasSuffix("!") || word.text.hasSuffix("?") ||
                next == nil ||
                (next!.range.startMs - word.range.endMs > 900) {
                groups.append(current)
                current = []
            }
        }
        
        return groups.filter { !$0.isEmpty }.map {
            (TimeRange(startMs: $0.first!.range.startMs, endMs: $0.last!.range.endMs), $0)
        }
    }
    
    private func speakerRegions(words: [TranscriptWord]) -> [(TimeRange, [TranscriptWord])] {
        var regions: [[TranscriptWord]] = []
        var current: [TranscriptWord] = []
        
        for word in words {
            if word.speakerId == nil {
                if !current.isEmpty {
                    regions.append(current)
                }
                current = []
            } else if !current.isEmpty, current.last?.speakerId != word.speakerId {
                regions.append(current)
                current = [word]
            } else {
                current.append(word)
            }
        }
        if !current.isEmpty {
            regions.append(current)
        }
        
        return regions.map {
            (TimeRange(startMs: $0.first!.range.startMs, endMs: $0.last!.range.endMs), $0)
        }
    }
}
