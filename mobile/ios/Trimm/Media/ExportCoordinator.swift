import Foundation
import AVFoundation
@preconcurrency import CoreImage

enum ExportError: Error {
    case invalidSource
    case exportFailed(Error?)
    case unsupportedOperation
}

@MainActor
public class ExportCoordinator {
    public static let shared = ExportCoordinator()
    
    private init() {}
    
    public func buildComposition(project: MobileProject) async throws -> (AVMutableComposition, AVMutableVideoComposition, AVMutableAudioMix) {
        let composition = AVMutableComposition()
        let audioMix = AVMutableAudioMix()
        
        let videoTrack = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid)!
        let audioTrack = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)!
        
        // 1. Determine Cuts
        let cuts = project.operations.compactMap { op -> [TimeRange]? in
            if case .removeRanges(_, let enabled, let ranges, _, _, _) = op, enabled {
                return ranges
            }
            return nil
        }
        .flatMap { $0 }
        .sorted { $0.startMs < $1.startMs }
        
        var sourceOffsets: [String: Int64] = [:]
        var offset: Int64 = 0
        for (index, source) in project.orderedSources.enumerated() {
            sourceOffsets["\(source.fingerprint)-\(index)"] = offset
            if sourceOffsets[source.fingerprint] == nil {
                sourceOffsets[source.fingerprint] = offset
            }
            offset += source.durationMs
        }
        
        // Find targets that require splitting (e.g., speed, color grade)
        var fineTargets: [TimeRange] = []
        for operation in project.operations {
            var target: OperationTarget?
            if case .setAudioLevels(_, let enabled, _, _, _, _, _, let t) = operation, enabled {
                target = t
            } else if case .setColorGrade(_, let enabled, _, let t, _, _) = operation, enabled {
                target = t
            }
            if let target = target {
                if let srcOffset = sourceOffsets[target.sourceId] ?? sourceOffsets[String(target.sourceId.split(separator: "-").first ?? "")] {
                    fineTargets.append(TimeRange(startMs: srcOffset + target.startMs, endMs: srcOffset + target.endMs))
                }
            }
        }
        
        let retained = splitAtBoundaries(
            ranges: retainedRanges(durationMs: project.durationMs, removals: cuts),
            boundaries: fineTargets.flatMap { [$0.startMs, $0.endMs] }
        )
        
        guard !retained.isEmpty else {
            throw ExportError.unsupportedOperation // Everything removed
        }
        
        var timelineStart: Int64 = 0
        var currentCursor = CMTime.zero
        
        let audioMixParam = AVMutableAudioMixInputParameters(track: audioTrack)
        
        var globalTrackTransform: CGAffineTransform = .identity
        var capturedTransform = false
        
        for source in project.orderedSources {
            let sourceStart = timelineStart
            let sourceEnd = sourceStart + source.durationMs
            timelineStart = sourceEnd
            
            guard let url = source.resolvedUrl else { continue }
            let asset = AVURLAsset(url: url)
            
            guard let assetVideoTrack = try? await asset.loadTracks(withMediaType: .video).first else { continue }
            let assetAudioTrack = try? await asset.loadTracks(withMediaType: .audio).first
            
            if !capturedTransform, let transform = try? await assetVideoTrack.load(.preferredTransform) {
                globalTrackTransform = transform
                videoTrack.preferredTransform = transform
                capturedTransform = true
            }
            
            for range in retained {
                let start = max(range.startMs, sourceStart)
                let end = min(range.endMs, sourceEnd)
                
                if end > start {
                    let localStart = start - sourceStart
                    let localEnd = end - sourceStart
                    
                    let startTime = CMTime(value: CMTimeValue(localStart), timescale: 1000)
                    let duration = CMTime(value: CMTimeValue(localEnd - localStart), timescale: 1000)
                    let timeRange = CMTimeRange(start: startTime, duration: duration)
                    
                    try videoTrack.insertTimeRange(timeRange, of: assetVideoTrack, at: currentCursor)
                    if let aTrack = assetAudioTrack {
                        try audioTrack.insertTimeRange(timeRange, of: aTrack, at: currentCursor)
                    }
                    
                    currentCursor = currentCursor + duration
                    
                    // Apply volume/audio levels
                    // Add audio mix handling later if needed
                }
            }
        }
        
        func sourceToOutput(sourceMs: Int64) -> Int64 {
            var outputMs: Int64 = 0
            for range in retained {
                if sourceMs < range.startMs {
                    return outputMs
                } else if sourceMs < range.endMs {
                    return outputMs + (sourceMs - range.startMs)
                } else {
                    outputMs += (range.endMs - range.startMs)
                }
            }
            return outputMs
        }
        
        let colorCubeFilter = FineColorEffect.createColorCube(operations: project.operations)
        var textOverlays: [TextGraphicOverlay] = []
        for op in project.operations {
            if case let .addTextOverlay(_, enabled, text, target, designBlueprint) = op, enabled {
                let offset = sourceOffsets[target?.sourceId ?? ""] ?? 0
                let start = offset + (target?.startMs ?? 0)
                let end = offset + (target?.endMs ?? project.durationMs)
                textOverlays.append(TextGraphicOverlay(
                    text: text,
                    blueprintJson: designBlueprint,
                    startMs: sourceToOutput(sourceMs: start),
                    endMs: sourceToOutput(sourceMs: end),
                    videoWidth: project.export.width,
                    videoHeight: project.export.height
                ))
            } else if case let .addCaptions(_, enabled, cues, target, speakerId, designBlueprint) = op, enabled {
                let offset = sourceOffsets[target?.sourceId ?? ""] ?? 0
                for cue in cues {
                    if let sid = speakerId, let wordSid = cue.words?.first?.speakerId, sid != wordSid { continue }
                    
                    let start = offset + cue.range.startMs
                    let end = offset + cue.range.endMs
                    
                    let outStart = sourceToOutput(sourceMs: start)
                    let outEnd = sourceToOutput(sourceMs: end)
                    
                    if outEnd > outStart {
                        textOverlays.append(TextGraphicOverlay(
                            text: cue.text,
                            blueprintJson: designBlueprint,
                            startMs: outStart,
                            endMs: outEnd,
                            videoWidth: project.export.width,
                            videoHeight: project.export.height
                        ))
                    }
                }
            }
        }
        
        let videoComposition = AVMutableVideoComposition(asset: composition) { request in
            var image = request.sourceImage
            
            let rotatedExtent = image.extent.applying(globalTrackTransform)
            let transformToOrigin = globalTrackTransform.concatenating(CGAffineTransform(translationX: -rotatedExtent.origin.x, y: -rotatedExtent.origin.y))
            image = image.transformed(by: transformToOrigin)
            
            let timeMs = Int64(request.compositionTime.seconds * 1000)
            
            // 1. Color Grading
            if let colorFilter = colorCubeFilter {
                colorFilter.setValue(image, forKey: kCIInputImageKey)
                if let out = colorFilter.outputImage { image = out }
            }
            
            let widthRatio = request.renderSize.width / image.extent.width
            let heightRatio = request.renderSize.height / image.extent.height
            let scale = max(widthRatio, heightRatio)
            
            let scaledImage = image.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
            
            let dx = (request.renderSize.width - scaledImage.extent.width) / 2.0
            let dy = (request.renderSize.height - scaledImage.extent.height) / 2.0
            let transform = CGAffineTransform(translationX: dx - scaledImage.extent.origin.x, y: dy - scaledImage.extent.origin.y)
            image = scaledImage.transformed(by: transform)
            
            let bg = CIImage(color: .black).cropped(to: CGRect(origin: .zero, size: request.renderSize))
            image = image.composited(over: bg)
            
            // 2. Text Overlays
            for overlay in textOverlays {
                if let overlayImage = overlay.getImage(presentationTimeUs: timeMs * 1000) {
                    image = overlayImage.composited(over: image)
                }
            }
            
            request.finish(with: image, context: nil)
        }
        
        videoComposition.frameDuration = CMTime(value: 1, timescale: 30)
        videoComposition.renderSize = CGSize(width: project.export.width, height: project.export.height)
        
        audioMixParam.trackID = audioTrack.trackID
        audioMix.inputParameters = [audioMixParam]
        
        return (composition, videoComposition, audioMix)
    }
    
    public func export(composition: AVMutableComposition, videoComposition: AVMutableVideoComposition, audioMix: AVMutableAudioMix, outputURL: URL) async throws {
        if FileManager.default.fileExists(atPath: outputURL.path) {
            try FileManager.default.removeItem(at: outputURL)
        }
        
        guard let session = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetHighestQuality) else {
            throw ExportError.exportFailed(nil)
        }
        
        session.outputURL = outputURL
        session.outputFileType = .mp4
        session.videoComposition = videoComposition
        session.audioMix = audioMix
        
        await session.export()
        
        if session.status != .completed {
            throw ExportError.exportFailed(session.error)
        }
    }
    
    private func retainedRanges(durationMs: Int64, removals: [TimeRange]) -> [TimeRange] {
        var cursor: Int64 = 0
        var result: [TimeRange] = []
        
        for cut in removals {
            if cut.startMs > cursor {
                result.append(TimeRange(startMs: cursor, endMs: cut.startMs))
            }
            cursor = max(cursor, cut.endMs)
        }
        if cursor < durationMs {
            result.append(TimeRange(startMs: cursor, endMs: durationMs))
        }
        return result
    }
    
    private func splitAtBoundaries(ranges: [TimeRange], boundaries: [Int64]) -> [TimeRange] {
        var result: [TimeRange] = []
        for range in ranges {
            var points = [range.startMs]
            points.append(contentsOf: boundaries.filter { $0 > range.startMs && $0 < range.endMs })
            points.append(range.endMs)
            points.sort()
            
            for i in 0..<(points.count - 1) {
                result.append(TimeRange(startMs: points[i], endMs: points[i+1]))
            }
        }
        return result
    }
}
