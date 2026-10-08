import Foundation
import AVFoundation

public struct DecodedPcm {
    public let samples: [Float]
    public let sampleRate: Int
    public let channelCount: Int
}

public protocol AudioPcmDecoder {
    func decode(sourceUri: String) async throws -> DecodedPcm
}

public class LocalSoundAnalyzer: EvidenceAnalyzer {
    public let identity = AnalyzerIdentity(name: "trimm scene-aware audio analyzer", version: "2.0.0", execution: .localDevice)
    private let decoder: AudioPcmDecoder
    private let speechRanges: [TimeRange]
    
    public init(decoder: AudioPcmDecoder = AVFoundationAudioPcmDecoder(), speechRanges: [TimeRange] = []) {
        self.decoder = decoder
        self.speechRanges = speechRanges
    }
    
    public func analyze(context: AnalyzerContext) async throws -> [AnyEvidenceObservation] {
        let uri = context.source.resolvedUrl?.absoluteString ?? context.source.uri
        let pcm = try await decoder.decode(sourceUri: uri)
        precondition(pcm.sampleRate > 0 && pcm.channelCount > 0)
        if pcm.samples.isEmpty {
            throw NSError(domain: "LocalSoundAnalyzer", code: 1, userInfo: [NSLocalizedDescriptionKey: "The audio track decoded no PCM samples."])
        }
        
        let mono: [Float]
        if pcm.channelCount == 1 {
            mono = pcm.samples
        } else {
            let frameCount = pcm.samples.count / pcm.channelCount
            var monoSamples = [Float](repeating: 0, count: frameCount)
            for frame in 0..<frameCount {
                var sum: Float = 0
                for channel in 0..<pcm.channelCount {
                    sum += pcm.samples[frame * pcm.channelCount + channel]
                }
                monoSamples[frame] = sum / Float(pcm.channelCount)
            }
            mono = monoSamples
        }
        
        return WaveformEvidence.detect(
            fingerprint: context.source.fingerprint,
            durationMs: context.source.durationMs,
            samples: mono,
            sampleRate: pcm.sampleRate,
            analyzer: identity,
            speechRanges: speechRanges
        )
    }
}

private struct Window {
    let range: TimeRange
    let dbfs: Float
    let peak: Float
    let zeroCrossingRate: Float
    let crestDb: Float
}

private enum WaveformEvidence {
    static func detect(
        fingerprint: String,
        durationMs: Int64,
        samples: [Float],
        sampleRate: Int,
        analyzer: AnalyzerIdentity,
        speechRanges: [TimeRange] = []
    ) -> [AnyEvidenceObservation] {
        precondition(durationMs > 0 && sampleRate > 0)
        let windowSamples = max(sampleRate / 4, 1)
        
        let windowCount = (samples.count + windowSamples - 1) / windowSamples
        var windows: [Window] = []
        
        for index in 0..<windowCount {
            let startIndex = index * windowSamples
            let endIndex = min(startIndex + windowSamples, samples.count)
            let values = samples[startIndex..<endIndex]
            
            let startMs = Int64(index) * 250
            let endMs = min(durationMs, startMs + 250)
            
            if startMs < durationMs && endMs > startMs {
                let sumSq = values.reduce(Double(0)) { $0 + Double($1 * $1) }
                let rms = Float(sqrt(sumSq / Double(values.count)))
                let peak = values.map { abs($0) }.max() ?? 0
                
                var crossings = 0
                if values.count > 1 {
                    var prev = values.first!
                    for val in values.dropFirst() {
                        if (prev < 0) != (val < 0) {
                            crossings += 1
                        }
                        prev = val
                    }
                }
                
                let zeroCrossingRate = Float(crossings) / Float(max(values.count, 1))
                let crestDb = dbfs(peak) - dbfs(rms)
                
                windows.append(Window(range: TimeRange(startMs: startMs, endMs: endMs), dbfs: dbfs(rms), peak: peak, zeroCrossingRate: zeroCrossingRate, crestDb: crestDb))
            }
        }
        
        if windows.isEmpty {
            return []
        }
        
        let fullRange = TimeRange(startMs: 0, endMs: durationMs)
        let overallSumSq = samples.reduce(Double(0)) { $0 + Double($1 * $1) }
        let overallRms = Float(sqrt(overallSumSq / Double(samples.count)))
        let clippedRatio = Float(samples.filter { abs($0) >= 0.999 }.count) / Float(samples.count)
        
        let provenance = EvidenceProvenance(analyzer: analyzer, sourceFingerprint: fingerprint)
        
        var observations: [AnyEvidenceObservation] = [
            .sound(sound(fingerprint: fingerprint, analyzer: analyzer, range: fullRange, kind: .loudness, value: dbfs(overallRms), unit: "dBFS", confidence: 0.98, provenance: provenance)),
            .sound(sound(fingerprint: fingerprint, analyzer: analyzer, range: fullRange, kind: .clipping, value: clippedRatio, unit: "ratio", confidence: 0.99, provenance: provenance))
        ]
        
        let sceneRanges = scenes(windows: windows, durationMs: durationMs, speechRanges: speechRanges)
        
        for (sceneIndex, scene) in sceneRanges.enumerated() {
            let sceneWindows = windows.filter { $0.range.overlaps(scene) }
            let suppliedSpeech = !speechRanges.isEmpty
            let energyMedian = median(sceneWindows.map { $0.dbfs })
            
            let speech = sceneWindows.filter { window in
                if suppliedSpeech {
                    return speechRanges.contains { speechRange in window.range.overlaps(speechRange) }
                } else {
                    return window.dbfs >= energyMedian + 6.0 && (0.025...55.0).contains(window.zeroCrossingRate)
                }
            }
            
            let nonSpeech = sceneWindows.filter { w in !speech.contains(where: { $0.range == w.range }) }
            let transient = nonSpeech.filter { $0.crestDb >= 14.0 || $0.peak >= 0.98 }
            let tonal = nonSpeech.filter { $0.dbfs > -50.0 && (0.01...25.0).contains($0.zeroCrossingRate) && $0.crestDb < 5.0 }
            let clean = nonSpeech.filter { w in !transient.contains(where: { $0.range == w.range }) && !tonal.contains(where: { $0.range == w.range }) }
            
            let note = "scene=\(sceneIndex + 1); speechMask=\(suppliedSpeech ? "transcript" : "energy"); cleanNoiseWindows=\(clean.count); rejectedTransient=\(transient.count); rejectedTonal=\(tonal.count)"
            let sceneProvenance = EvidenceProvenance(analyzer: analyzer, sourceFingerprint: fingerprint, note: note)
            
            let sceneClippingCount = sceneWindows.reduce(0) { acc, window in
                let from = max(0, min(Int((window.range.startMs * Int64(sampleRate)) / 1000), samples.count))
                let to = max(from, min(Int((window.range.endMs * Int64(sampleRate)) / 1000), samples.count))
                let slice = samples[from..<to]
                return acc + slice.filter { abs($0) >= 0.999 }.count
            }
            let sceneDurationSecs = Float(max((scene.endMs - scene.startMs) * Int64(sampleRate) / 1000, 1))
            let sceneClipping = Float(sceneClippingCount) / sceneDurationSecs
            
            observations.append(.sound(sound(fingerprint: fingerprint, analyzer: analyzer, range: scene, kind: .clipping, value: sceneClipping, unit: "ratio", confidence: 0.99, provenance: sceneProvenance)))
            
            if !speech.isEmpty {
                observations.append(.sound(sound(fingerprint: fingerprint, analyzer: analyzer, range: scene, kind: .speechLevel, value: median(speech.map { $0.dbfs }), unit: "dBFS", confidence: suppliedSpeech ? 0.95 : 0.65, provenance: sceneProvenance)))
            }
            
            if clean.count >= 2 {
                let noiseFloor = median(clean.map { $0.dbfs })
                let confidence = min(0.65 + Float(min(clean.count, 7)) * 0.05, 0.95)
                observations.append(.sound(sound(fingerprint: fingerprint, analyzer: analyzer, range: scene, kind: .noiseFloor, value: noiseFloor, unit: "dBFS", confidence: confidence, provenance: sceneProvenance)))
                
                if !speech.isEmpty {
                    observations.append(.sound(sound(fingerprint: fingerprint, analyzer: analyzer, range: scene, kind: .snr, value: median(speech.map { $0.dbfs }) - noiseFloor, unit: "dB", confidence: min(confidence, suppliedSpeech ? 0.95 : 0.65), provenance: sceneProvenance)))
                }
                
                let minSpeechDbfs = speech.map { $0.dbfs }.min() ?? -24.0
                let silenceThreshold = min(noiseFloor + 4.0, minSpeechDbfs - 14.0)
                
                for contiguousRange in contiguous(windows: clean.filter { $0.dbfs <= silenceThreshold }) {
                    observations.append(.sound(sound(fingerprint: fingerprint, analyzer: analyzer, range: contiguousRange, kind: .silence, value: nil, unit: nil, confidence: confidence, provenance: sceneProvenance)))
                }
            } else {
                let rejectedRatio = Float(transient.count + tonal.count) / Float(max(nonSpeech.count, 1))
                observations.append(.sound(sound(fingerprint: fingerprint, analyzer: analyzer, range: scene, kind: .measurementUncertainty, value: max(rejectedRatio, nonSpeech.count < 2 ? 1.0 : 0.0), unit: "ratio", confidence: 0.99, provenance: sceneProvenance)))
            }
            
            for window in transient {
                observations.append(.sound(sound(fingerprint: fingerprint, analyzer: analyzer, range: window.range, kind: .transient, value: window.peak, unit: "linear_peak", confidence: 0.8, provenance: sceneProvenance)))
            }
        }
        
        return observations
    }
    
    private static func scenes(windows: [Window], durationMs: Int64, speechRanges: [TimeRange]) -> [TimeRange] {
        if windows.count < 8 {
            return [TimeRange(startMs: 0, endMs: durationMs)]
        }
        var boundaries: [Int64] = []
        var lastBoundary: Int64 = 0
        
        for index in 4..<(windows.count - 4) {
            let beforeWindows = Array(windows[(index - 4)..<index])
            let afterWindows = Array(windows[index..<(index + 4)])
            
            let combined = beforeWindows + afterWindows
            if speechRanges.contains(where: { speech in combined.contains(where: { $0.range.overlaps(speech) }) }) {
                continue
            }
            
            let before = beforeWindows.map { Double($0.dbfs) }.reduce(0, +) / Double(beforeWindows.count)
            let after = afterWindows.map { Double($0.dbfs) }.reduce(0, +) / Double(afterWindows.count)
            let boundary = windows[index].range.startMs
            
            if abs(after - before) >= 8.0 && boundary - lastBoundary >= 2000 && durationMs - boundary >= 2000 {
                boundaries.append(boundary)
                lastBoundary = boundary
            }
        }
        
        var points = [Int64(0)]
        points.append(contentsOf: boundaries)
        points.append(durationMs)
        
        var ranges: [TimeRange] = []
        for (start, end) in zip(points, points.dropFirst()) {
            ranges.append(TimeRange(startMs: start, endMs: end))
        }
        return ranges
    }
    
    private static func contiguous(windows: [Window]) -> [TimeRange] {
        guard let first = windows.first else { return [] }
        var result: [TimeRange] = []
        var start = first.range.startMs
        var end = first.range.endMs
        
        for window in windows.dropFirst() {
            if window.range.startMs == end {
                end = window.range.endMs
            } else {
                if end - start >= 500 {
                    result.append(TimeRange(startMs: start, endMs: end))
                }
                start = window.range.startMs
                end = window.range.endMs
            }
        }
        if end - start >= 500 {
            result.append(TimeRange(startMs: start, endMs: end))
        }
        return result
    }
    
    private static func sound(fingerprint: String, analyzer: AnalyzerIdentity, range: TimeRange, kind: SoundKind, value: Float?, unit: String?, confidence: Float, provenance: EvidenceProvenance) -> SoundEvidence {
        return SoundEvidence(
            id: stableEvidenceId(sourceFingerprint: fingerprint, analyzer: analyzer, kind: kind.rawValue, range: range),
            sourceFingerprint: fingerprint,
            range: range,
            confidence: confidence,
            provenance: provenance,
            kind: kind,
            value: value,
            unit: unit
        )
    }
    
    private static func dbfs(_ value: Float) -> Float {
        return value <= 0.000001 ? -120.0 : max(20.0 * log10(value), -120.0)
    }
    
    private static func median(_ values: [Float]) -> Float {
        if values.isEmpty { return 0 }
        let sorted = values.sorted()
        if sorted.count % 2 == 1 {
            return sorted[sorted.count / 2]
        } else {
            return (sorted[sorted.count / 2 - 1] + sorted[sorted.count / 2]) / 2.0
        }
    }
}

extension TimeRange {
    func overlaps(_ other: TimeRange) -> Bool {
        return startMs < other.endMs && other.startMs < endMs
    }
}

public class AVFoundationAudioPcmDecoder: AudioPcmDecoder {
    public init() {}
    
    public func decode(sourceUri: String) async throws -> DecodedPcm {
        guard let url = URL(string: sourceUri) else {
            throw URLError(.badURL)
        }
        let asset = AVURLAsset(url: url)
        let reader = try AVAssetReader(asset: asset)
        guard let audioTrack = try await asset.loadTracks(withMediaType: .audio).first else {
            throw NSError(domain: "AVFoundationAudioPcmDecoder", code: 1, userInfo: [NSLocalizedDescriptionKey: "Source has no decodable audio track."])
        }
        
        let outputSettings: [String: Any] = [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVLinearPCMBitDepthKey: 32,
            AVLinearPCMIsFloatKey: true,
            AVLinearPCMIsBigEndianKey: false,
            AVLinearPCMIsNonInterleaved: false
        ]
        
        let trackOutput = AVAssetReaderTrackOutput(track: audioTrack, outputSettings: outputSettings)
        reader.add(trackOutput)
        reader.startReading()
        
        var values: [Float] = []
        var sampleRate: Int = 44100
        var channels: Int = 2
        
        while let sampleBuffer = trackOutput.copyNextSampleBuffer() {
            guard let blockBuffer = CMSampleBufferGetDataBuffer(sampleBuffer) else { continue }
            
            if let formatDesc = CMSampleBufferGetFormatDescription(sampleBuffer),
               let streamBasicDesc = CMAudioFormatDescriptionGetStreamBasicDescription(formatDesc) {
                sampleRate = Int(streamBasicDesc.pointee.mSampleRate)
                channels = Int(streamBasicDesc.pointee.mChannelsPerFrame)
            }
            
            let length = CMBlockBufferGetDataLength(blockBuffer)
            var bytes = [UInt8](repeating: 0, count: length)
            CMBlockBufferCopyDataBytes(blockBuffer, atOffset: 0, dataLength: length, destination: &bytes)
            
            let floatCount = length / MemoryLayout<Float>.size
            bytes.withUnsafeBytes { ptr in
                if let floatPtr = ptr.baseAddress?.assumingMemoryBound(to: Float.self) {
                    let bufferValues = Array(UnsafeBufferPointer(start: floatPtr, count: floatCount))
                    values.append(contentsOf: bufferValues)
                }
            }
        }
        
        return DecodedPcm(samples: values, sampleRate: sampleRate, channelCount: channels)
    }
}
