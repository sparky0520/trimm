import Foundation
import AVFoundation
import CoreGraphics
#if canImport(UIKit)
import UIKit
#else
import CoreImage
#endif

public struct AnalyzerContext {
    public let source: SourceMedia
    public let artifactDirectory: URL
    
    public init(source: SourceMedia, artifactDirectory: URL) {
        self.source = source
        self.artifactDirectory = artifactDirectory
    }
}

public protocol EvidenceAnalyzer {
    var identity: AnalyzerIdentity { get }
    func analyze(context: AnalyzerContext) async throws -> [AnyEvidenceObservation]
}

private struct FrameMetrics {
    let luma: Float
    let edge: Float
    let centerContrast: Float
}

private struct FrameSample {
    let atMs: Int64
    let range: TimeRange
    let id: String
    let file: URL
    let metrics: FrameMetrics
    let width: Int
    let height: Int
}

public class LocalVisualAnalyzer: EvidenceAnalyzer {
    public let identity = AnalyzerIdentity(name: "trimm frame sampler", version: "1.0.0", execution: .localDevice)
    
    public init() {}
    
    public func analyze(context: AnalyzerContext) async throws -> [AnyEvidenceObservation] {
        let source = context.source
        guard let url = URL(string: source.uri) else {
            throw NSError(domain: "LocalVisualAnalyzer", code: 1, userInfo: [NSLocalizedDescriptionKey: "Invalid source URI"])
        }
        
        let sampleTimes = generateSampleTimes(durationMs: source.durationMs)
        let asset = AVURLAsset(url: url)
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.requestedTimeToleranceBefore = .zero
        generator.requestedTimeToleranceAfter = .zero
        
        var frames: [FrameSample] = []
        
        for atMs in sampleTimes {
            let time = CMTime(value: atMs, timescale: 1000)
            let range = TimeRange(startMs: atMs, endMs: max(atMs + 1, min(source.durationMs, atMs + 1)))
            let id = stableEvidenceId(sourceFingerprint: source.fingerprint, analyzer: identity, kind: "frame", range: range)
            let fileURL = context.artifactDirectory.appendingPathComponent("\(id).jpg")
            
            do {
                let cgImage = try await generateImage(generator: generator, at: time)
                
                // Save to file
                // In iOS, we typically use UIImage, but we can do it directly with CoreGraphics / ImageIO if preferred
                // For simplicity, we'll use UIImage (importing UIKit is needed, or CoreImage)
                #if canImport(UIKit)
                let uiImage = UIImage(cgImage: cgImage)
                if let data = uiImage.jpegData(compressionQuality: 0.82) {
                    try data.write(to: fileURL)
                }
                #else
                let ciImage = CIImage(cgImage: cgImage)
                let contextCI = CIContext()
                if let colorSpace = cgImage.colorSpace {
                    try contextCI.writeJPEGRepresentation(of: ciImage, to: fileURL, colorSpace: colorSpace, options: [.compressionQuality: 0.82])
                }
                #endif
                
                let metrics = computeMetrics(from: cgImage)
                frames.append(FrameSample(atMs: atMs, range: range, id: id, file: fileURL, metrics: metrics, width: cgImage.width, height: cgImage.height))
            } catch {
                // Ignore extraction failures for a single frame, but we could log it
            }
        }
        
        if frames.isEmpty {
            throw NSError(domain: "LocalVisualAnalyzer", code: 2, userInfo: [NSLocalizedDescriptionKey: "No representative video frames could be decoded."])
        }
        
        var observations: [AnyEvidenceObservation] = []
        
        for frame in frames {
            let provenance = EvidenceProvenance(analyzer: identity, sourceFingerprint: source.fingerprint, derivedFrom: [frame.id])
            observations.append(.frame(FrameEvidence(id: frame.id, sourceFingerprint: source.fingerprint, range: frame.range, confidence: 0.95, provenance: provenance, artifactPath: frame.file.path, width: frame.width, height: frame.height)))
            
            observations.append(.visual(visual(fingerprint: source.fingerprint, range: frame.range, kind: .exposure, value: frame.metrics.luma, confidence: 0.85, provenance: provenance)))
            observations.append(.visual(visual(fingerprint: source.fingerprint, range: frame.range, kind: .blur, value: frame.metrics.edge, confidence: 0.72, provenance: provenance)))
            observations.append(.visual(visual(fingerprint: source.fingerprint, range: frame.range, kind: .salience, value: frame.metrics.centerContrast, confidence: 0.65, provenance: provenance)))
            observations.append(.visual(visual(fingerprint: source.fingerprint, range: frame.range, kind: .cropSafety, value: frame.metrics.centerContrast, confidence: 0.55, provenance: provenance)))
        }
        
        for (before, after) in zip(frames, frames.dropFirst()) {
            let range = TimeRange(startMs: before.atMs, endMs: after.atMs)
            let delta = abs(after.metrics.luma - before.metrics.luma) + abs(after.metrics.edge - before.metrics.edge)
            
            let motionProvenance = EvidenceProvenance(analyzer: identity, sourceFingerprint: source.fingerprint, derivedFrom: [before.id, after.id])
            observations.append(.visual(visual(fingerprint: source.fingerprint, range: range, kind: .motion, value: min(max(delta, 0), 1), confidence: 0.65, provenance: motionProvenance)))
            
            let shakeProvenance = EvidenceProvenance(analyzer: identity, sourceFingerprint: source.fingerprint, derivedFrom: [before.id, after.id], note: "Frame-difference estimate; no gyroscope data.")
            observations.append(.visual(visual(fingerprint: source.fingerprint, range: range, kind: .shake, value: min(max(delta * 0.7, 0), 1), confidence: 0.45, provenance: shakeProvenance)))
        }
        
        let zipped = Array(zip(frames, frames.dropFirst()))
        let boundaries = zipped.filter { a, b in
            abs(b.metrics.luma - a.metrics.luma) + abs(b.metrics.edge - a.metrics.edge) > 0.38
        }.map { $0.1.atMs }
        
        var starts = [Int64(0)]
        starts.append(contentsOf: boundaries)
        var ends = boundaries
        ends.append(source.durationMs)
        
        for (start, end) in zip(starts, ends) {
            if end > start {
                let range = TimeRange(startMs: start, endMs: end)
                let frameInRange = frames.filter { $0.atMs >= start && $0.atMs < end }
                var reps = frameInRange.map { $0.id }
                if reps.isEmpty, let closest = frames.min(by: { abs($0.atMs - start) < abs($1.atMs - start) }) {
                    reps = [closest.id]
                }
                
                let prov = EvidenceProvenance(analyzer: identity, sourceFingerprint: source.fingerprint, derivedFrom: reps)
                let shotId = stableEvidenceId(sourceFingerprint: source.fingerprint, analyzer: identity, kind: "shot", range: range)
                observations.append(.shot(ShotEvidence(id: shotId, sourceFingerprint: source.fingerprint, range: range, confidence: 0.62, provenance: prov, representativeFrameIds: reps)))
            }
        }
        
        return observations
    }
    
    private func generateImage(generator: AVAssetImageGenerator, at time: CMTime) async throws -> CGImage {
        if #available(iOS 16.0, macOS 13.0, *) {
            let (image, _) = try await generator.image(at: time)
            return image
        } else {
            return try await withCheckedThrowingContinuation { continuation in
                generator.generateCGImagesAsynchronously(forTimes: [NSValue(time: time)]) { _, image, _, result, error in
                    if let error = error {
                        continuation.resume(throwing: error)
                    } else if let image = image {
                        continuation.resume(returning: image)
                    } else {
                        continuation.resume(throwing: NSError(domain: "LocalVisualAnalyzer", code: 3, userInfo: nil))
                    }
                }
            }
        }
    }
    
    private func visual(fingerprint: String, range: TimeRange, kind: VisualKind, value: Float, confidence: Float, provenance: EvidenceProvenance) -> VisualEvidence {
        return VisualEvidence(
            id: stableEvidenceId(sourceFingerprint: fingerprint, analyzer: identity, kind: kind.rawValue, range: range),
            sourceFingerprint: fingerprint,
            range: range,
            confidence: confidence,
            provenance: provenance,
            kind: kind,
            value: value
        )
    }
    
    private func generateSampleTimes(durationMs: Int64) -> [Int64] {
        let interval = max(500, min(durationMs / 12, 3000))
        var current: Int64 = 0
        var times: [Int64] = []
        while current < durationMs && times.count < 60 {
            times.append(current)
            current += interval
        }
        return times
    }
    
    private func computeMetrics(from cgImage: CGImage) -> FrameMetrics {
        let width = 32
        let height = 32
        var pixels = [UInt32](repeating: 0, count: width * height)
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let context = CGContext(
            data: &pixels,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: width * 4,
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue
        )
        
        if let context = context {
            context.interpolationQuality = .low
            context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))
        }
        
        var lumaSum: Float = 0
        var lumas = [Float](repeating: 0, count: width * height)
        
        for i in 0..<(width * height) {
            let pixel = pixels[i]
            let r = Float((pixel >> 24) & 255)
            let g = Float((pixel >> 16) & 255)
            let b = Float((pixel >> 8) & 255)
            // Android uses AARRGGBB for getPixels, but we used pre-multiplied last (RGBA) and big-endian, so:
            // pixel is actually R G B A in memory if byteOrder32Big + premultipliedLast
            // Wait, byteOrder32Big means the bytes are in the exact order: R, G, B, A
            // So pixel >> 24 is R, >> 16 is G, >> 8 is B
            
            let luma = ((r * 0.2126) + (g * 0.7152) + (b * 0.0722)) / 255.0
            lumas[i] = luma
            lumaSum += luma
        }
        
        var edge: Float = 0
        for y in 1..<height {
            for x in 1..<width {
                let current = lumas[y * width + x]
                let left = lumas[y * width + x - 1]
                let up = lumas[(y - 1) * width + x]
                edge += abs(current - left) + abs(current - up)
            }
        }
        
        var centerSum: Float = 0
        var centerCount = 0
        for y in 10...21 {
            for x in 10...21 {
                centerSum += lumas[y * width + x]
                centerCount += 1
            }
        }
        let center = centerSum / Float(centerCount)
        
        let mean = lumaSum / Float(width * height)
        let edgeMetric = min(max(edge / 1984.0, 0), 1)
        let contrastMetric = min(max(abs(center - mean), 0), 1)
        
        return FrameMetrics(luma: mean, edge: edgeMetric, centerContrast: contrastMetric)
    }
}
