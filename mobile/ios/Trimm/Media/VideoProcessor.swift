import Foundation
import AVFoundation
@preconcurrency import CoreImage

public class VideoProcessor {
    public static let shared = VideoProcessor()
    private init() {}
    
    public func createPreviewComposition(project: MobileProject, schema: CompositionSchema? = nil) async throws -> AVPlayerItem {
        guard let url = project.source.resolvedUrl else {
            throw NSError(domain: "VideoProcessor", code: 1, userInfo: [NSLocalizedDescriptionKey: "Invalid source URI"])
        }
        
        if url.isFileURL && !FileManager.default.fileExists(atPath: url.path) {
            throw NSError(domain: "VideoProcessor", code: 404, userInfo: [NSLocalizedDescriptionKey: "Source video file is missing. If you reinstalled the app or changed devices, please start a new project."])
        }
        
        let asset = AVURLAsset(url: url)
        
        if let schema = schema {
            let (comp, videoComp, audioMix) = try await buildRawComposition(schema: schema, asset: asset, project: project)
            return await MainActor.run {
                let item = AVPlayerItem(asset: comp)
                item.videoComposition = videoComp
                item.audioMix = audioMix
                return item
            }
        }
        
        // Load duration
        let duration = try await asset.load(.duration)
        let durationMs = Int64(duration.seconds * 1000)
        
        // Find remove operations
        var removedRanges: [TimeRange] = []
        for operation in project.operations {
            if case let .removeRanges(_, enabled, ranges, _, _, _) = operation, enabled {
                removedRanges.append(contentsOf: ranges)
            }
        }
        
        // Sort and merge removed ranges
        let sortedRemovals = removedRanges.sorted { $0.startMs < $1.startMs }
        var mergedRemovals: [TimeRange] = []
        for range in sortedRemovals {
            if let last = mergedRemovals.last, last.endMs >= range.startMs {
                mergedRemovals[mergedRemovals.count - 1] = TimeRange(startMs: last.startMs, endMs: max(last.endMs, range.endMs))
            } else {
                mergedRemovals.append(range)
            }
        }
        
        // Calculate kept ranges
        var keptRanges: [TimeRange] = []
        var currentStart: Int64 = 0
        for removal in mergedRemovals {
            if currentStart < removal.startMs {
                keptRanges.append(TimeRange(startMs: currentStart, endMs: removal.startMs))
            }
            currentStart = max(currentStart, removal.endMs)
        }
        if currentStart < durationMs {
            keptRanges.append(TimeRange(startMs: currentStart, endMs: durationMs))
        }
        
        // Create composition
        let composition = AVMutableComposition()
        guard let videoTrack = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid),
              let audioTrack = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) else {
            throw NSError(domain: "VideoProcessor", code: 2, userInfo: [NSLocalizedDescriptionKey: "Failed to create composition tracks"])
        }
        
        let sourceVideoTrack = try await asset.loadTracks(withMediaType: .video).first
        if let sourceVideo = sourceVideoTrack {
            videoTrack.preferredTransform = try await sourceVideo.load(.preferredTransform)
        }
        let sourceAudioTrack = try await asset.loadTracks(withMediaType: .audio).first
        
        var insertTime = CMTime.zero
        for range in keptRanges {
            let start = CMTime(value: CMTimeValue(range.startMs), timescale: 1000)
            let duration = CMTime(value: CMTimeValue(range.durationMs), timescale: 1000)
            let timeRange = CMTimeRange(start: start, duration: duration)
            
            if let sourceVideo = sourceVideoTrack {
                try videoTrack.insertTimeRange(timeRange, of: sourceVideo, at: insertTime)
            }
            if let sourceAudio = sourceAudioTrack {
                try audioTrack.insertTimeRange(timeRange, of: sourceAudio, at: insertTime)
            }
            
            insertTime = CMTimeAdd(insertTime, duration)
        }
        
        let totalVideoDuration = insertTime
        let totalVideoDurationMs = Int64(totalVideoDuration.seconds * 1000)
        
        // --- BGM and SFX ---
        let audioMix = AVMutableAudioMix()
        var inputParameters = [AVMutableAudioMixInputParameters]()
        
        func sourceToOutput(sourceMs: Int64) -> Int64 {
            var outputMs: Int64 = 0
            for range in keptRanges {
                if sourceMs < range.startMs {
                    return outputMs
                } else if sourceMs < range.endMs {
                    return outputMs + (sourceMs - range.startMs)
                } else {
                    outputMs += range.durationMs
                }
            }
            return outputMs
        }
        
        var duckingRanges: [TimeRange] = []
        let bufferMs: Int64 = 500
        for word in project.analysis.words {
            let outStart = sourceToOutput(sourceMs: word.range.startMs)
            let outEnd = sourceToOutput(sourceMs: word.range.endMs)
            if outEnd > outStart {
                duckingRanges.append(TimeRange(startMs: max(0, outStart - bufferMs), endMs: outEnd + bufferMs))
            }
        }
        // Merge ducking ranges
        let sortedDucking = duckingRanges.sorted { $0.startMs < $1.startMs }
        var mergedDucking: [TimeRange] = []
        for range in sortedDucking {
            if let last = mergedDucking.last, last.endMs >= range.startMs {
                mergedDucking[mergedDucking.count - 1] = TimeRange(startMs: last.startMs, endMs: max(last.endMs, range.endMs))
            } else {
                mergedDucking.append(range)
            }
        }
        
        for operation in project.operations {
            if case let .addBgm(_, enabled, _, _, _, urlString) = operation, enabled, let bgmUrl = URL(string: urlString) {
                let bgmAsset = AVURLAsset(url: bgmUrl)
                
                guard let bgmTrack = try await bgmAsset.loadTracks(withMediaType: .audio).first,
                      let compBgmTrack = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) else {
                    continue
                }
                
                let bgmDuration = try await bgmAsset.load(.duration)
                let bgmDurationMs = Int64(bgmDuration.seconds * 1000)
                if bgmDurationMs <= 0 { continue }
                
                var currentStartMs: Int64 = 0
                while currentStartMs < totalVideoDurationMs {
                    let remainingMs = totalVideoDurationMs - currentStartMs
                    let playMs = min(bgmDurationMs, remainingMs)
                    let playDuration = CMTime(value: CMTimeValue(playMs), timescale: 1000)
                    let playTimeRange = CMTimeRange(start: .zero, duration: playDuration)
                    let atTime = CMTime(value: CMTimeValue(currentStartMs), timescale: 1000)
                    try compBgmTrack.insertTimeRange(playTimeRange, of: bgmTrack, at: atTime)
                    currentStartMs += playMs
                }
                
                // Ducking
                let params = AVMutableAudioMixInputParameters(track: compBgmTrack)
                let normalVolume: Float = 1.0
                let duckedVolume: Float = 0.2 // Roughly -14dB
                params.setVolume(normalVolume, at: .zero)
                
                for range in mergedDucking {
                    let duckStart = CMTime(value: CMTimeValue(range.startMs), timescale: 1000)
                    let duckEnd = CMTime(value: CMTimeValue(range.endMs), timescale: 1000)
                    
                    let attackDuration = CMTime(value: 150, timescale: 1000)
                    let releaseDuration = CMTime(value: 350, timescale: 1000)
                    
                    var attackStart = CMTimeSubtract(duckStart, attackDuration)
                    if attackStart.seconds < 0 { attackStart = .zero }
                    let actualAttackDuration = CMTimeSubtract(duckStart, attackStart)
                    let attackRange = CMTimeRange(start: attackStart, duration: actualAttackDuration)
                    
                    let releaseRange = CMTimeRange(start: duckEnd, duration: releaseDuration)
                    
                    params.setVolumeRamp(fromStartVolume: normalVolume, toEndVolume: duckedVolume, timeRange: attackRange)
                    params.setVolume(duckedVolume, at: duckStart)
                    params.setVolumeRamp(fromStartVolume: duckedVolume, toEndVolume: normalVolume, timeRange: releaseRange)
                }
                inputParameters.append(params)
                
            } else if case let .addBgmEffect(_, enabled, target, _, _, urlString) = operation, enabled, let effectUrl = URL(string: urlString) {
                let effectAsset = AVURLAsset(url: effectUrl)
                guard let effectTrack = try await effectAsset.loadTracks(withMediaType: .audio).first,
                      let compEffectTrack = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) else {
                    continue
                }
                let effectDuration = try await effectAsset.load(.duration)
                let effectDurationMs = Int64(effectDuration.seconds * 1000)
                if effectDurationMs <= 0 { continue }
                
                let targetMs = sourceToOutput(sourceMs: target?.startMs ?? 0)
                let playMs = effectDurationMs
                let atTime = CMTime(value: CMTimeValue(targetMs), timescale: 1000)
                
                if targetMs < totalVideoDurationMs {
                    let clampedMs = min(playMs, totalVideoDurationMs - targetMs)
                    let clampedDuration = CMTime(value: CMTimeValue(clampedMs), timescale: 1000)
                    let clampedTimeRange = CMTimeRange(start: .zero, duration: clampedDuration)
                    try compEffectTrack.insertTimeRange(clampedTimeRange, of: effectTrack, at: atTime)
                }
            } else if case let .addSfx(_, enabled, target, _, _, urlString) = operation, enabled, let sfxUrl = URL(string: urlString) {
                let sfxAsset = AVURLAsset(url: sfxUrl)
                guard let sfxTrack = try await sfxAsset.loadTracks(withMediaType: .audio).first,
                      let compSfxTrack = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) else {
                    continue
                }
                let sfxDuration = try await sfxAsset.load(.duration)
                let sfxDurationMs = Int64(sfxDuration.seconds * 1000)
                if sfxDurationMs <= 0 { continue }
                
                let targetMs = sourceToOutput(sourceMs: target?.startMs ?? 0)
                let atTime = CMTime(value: CMTimeValue(targetMs), timescale: 1000)
                
                if targetMs < totalVideoDurationMs {
                    let clampedMs = min(sfxDurationMs, totalVideoDurationMs - targetMs)
                    let clampedDuration = CMTime(value: CMTimeValue(clampedMs), timescale: 1000)
                    let clampedTimeRange = CMTimeRange(start: .zero, duration: clampedDuration)
                    try compSfxTrack.insertTimeRange(clampedTimeRange, of: sfxTrack, at: atTime)
                }
            }
        }
        
        audioMix.inputParameters = inputParameters
        
        let colorCubeFilter = FineColorEffect.createColorCube(operations: project.operations)
        var textOverlays: [TextGraphicOverlay] = []
        for op in project.operations {
            if case let .addTextOverlay(_, enabled, text, target, designBlueprint) = op, enabled {
                let offset: Int64 = 0 // Multi-source preview source offsets could be calculated if needed, assuming 0 for simplicity if single source
                let start = offset + (target?.startMs ?? 0)
                let end = offset + (target?.endMs ?? durationMs)
                textOverlays.append(TextGraphicOverlay(
                    text: text,
                    blueprintJson: designBlueprint,
                    startMs: sourceToOutput(sourceMs: start),
                    endMs: sourceToOutput(sourceMs: end),
                    videoWidth: project.export.width,
                    videoHeight: project.export.height
                ))
            } else if case let .addCaptions(_, enabled, cues, target, speakerId, designBlueprint) = op, enabled {
                let offset: Int64 = 0
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
        
        var videoComposition: AVMutableVideoComposition? = nil
        if colorCubeFilter != nil || !textOverlays.isEmpty {
            videoComposition = AVMutableVideoComposition(asset: composition) { request in
                var image = request.sourceImage
                let timeMs = Int64(request.compositionTime.seconds * 1000)
                
                let extent = image.extent
                if extent.isNull || extent.isInfinite || extent.width.isNaN || extent.height.isNaN || extent.width <= 0 || extent.height <= 0 {
                    let safeRect = CGRect(x: 0, y: 0, width: max(1.0, request.renderSize.width), height: max(1.0, request.renderSize.height))
                    let bg = CIImage(color: .black).cropped(to: safeRect)
                    request.finish(with: bg, context: nil)
                    return
                }
                
                if let colorFilter = colorCubeFilter {
                    colorFilter.setValue(image, forKey: kCIInputImageKey)
                    if let out = colorFilter.outputImage { image = out }
                }
                
                // Aspect-fill the video frame into the target render size
                let scaleX = request.renderSize.width / extent.width
                let scaleY = request.renderSize.height / extent.height
                let scale = max(scaleX, scaleY)
                
                let tx = (request.renderSize.width - extent.width * scale) / 2.0
                let ty = (request.renderSize.height - extent.height * scale) / 2.0
                
                if !tx.isNaN && !ty.isNaN && !scale.isNaN && !tx.isInfinite && !ty.isInfinite && !scale.isInfinite {
                    let transform = CGAffineTransform(translationX: -extent.minX, y: -extent.minY)
                        .concatenating(CGAffineTransform(scaleX: scale, y: scale))
                        .concatenating(CGAffineTransform(translationX: tx, y: ty))
                    image = image.transformed(by: transform)
                }
                
                let bg = CIImage(color: .black).cropped(to: CGRect(origin: .zero, size: request.renderSize))
                image = image.composited(over: bg)
                
                for overlay in textOverlays {
                    if let overlayImage = overlay.getImage(presentationTimeUs: timeMs * 1000) {
                        image = overlayImage.composited(over: image)
                    }
                }
                
                request.finish(with: image, context: nil)
            }
            videoComposition?.frameDuration = CMTime(value: 1, timescale: 30)
            videoComposition?.renderSize = CGSize(width: project.export.width, height: project.export.height)
        }
        
        nonisolated(unsafe) let safeComposition = composition
        nonisolated(unsafe) let safeAudioMix = audioMix
        nonisolated(unsafe) let safeVideoComposition = videoComposition
        
        return await MainActor.run {
            let item = AVPlayerItem(asset: safeComposition)
            item.audioMix = safeAudioMix
            item.videoComposition = safeVideoComposition
            return item
        }
    }
    
    public func buildRawComposition(schema: CompositionSchema, asset: AVURLAsset, project: MobileProject) async throws -> (AVMutableComposition, AVMutableVideoComposition?, AVMutableAudioMix?) {
        let composition = AVMutableComposition()
        guard let videoTrack = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid),
              let audioTrack = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) else {
            throw NSError(domain: "VideoProcessor", code: 2, userInfo: [NSLocalizedDescriptionKey: "Failed to create composition tracks"])
        }
        
        let sourceVideoTrack = try await asset.loadTracks(withMediaType: .video).first
        if let sourceVideo = sourceVideoTrack {
            videoTrack.preferredTransform = try await sourceVideo.load(.preferredTransform)
        }
        let sourceAudioTrack = try await asset.loadTracks(withMediaType: .audio).first
        
        var textOverlays: [TextGraphicOverlay] = []
        var activeEffects: [EffectNode] = []
        var audioNodes: [AudioNode] = []
        var videoNodes: [VideoNode] = []
        
        for node in schema.timeline {
            switch node {
            case .video(let v):
                let durationMs = v.endMs - v.startMs
                if durationMs <= 0 { continue }
                videoNodes.append(v)
                
                let start = CMTime(value: CMTimeValue(v.sourceStartMs), timescale: 1000)
                let duration = CMTime(value: CMTimeValue(durationMs), timescale: 1000)
                let timeRange = CMTimeRange(start: start, duration: duration)
                let insertTime = CMTime(value: CMTimeValue(v.startMs), timescale: 1000)
                
                if let sourceVideo = sourceVideoTrack {
                    try videoTrack.insertTimeRange(timeRange, of: sourceVideo, at: insertTime)
                }
                if let sourceAudio = sourceAudioTrack {
                    try audioTrack.insertTimeRange(timeRange, of: sourceAudio, at: insertTime)
                }
                
            case .text(let t):
                guard t.isVisible else { continue }
                var blueprint = [String: Any]()
                var layout = [String: Any]()
                var textStyle = [String: Any]()
                if let style = t.style {
                    if let color = style.color { textStyle["color"] = color }
                    if let fontSize = style.fontSize { textStyle["fontSize"] = fontSize }
                    if let strokeColor = style.strokeColor { textStyle["strokeColor"] = strokeColor }
                    if let strokeWidth = style.strokeWidth { textStyle["strokeWidth"] = strokeWidth }
                    
                    if let posX = style.posX { layout["posX"] = posX }
                    if let posY = style.posY { layout["posY"] = posY }
                    if let scale = style.scale { layout["scale"] = scale }
                }
                if !textStyle.isEmpty { blueprint["textStyle"] = textStyle }
                if !layout.isEmpty { blueprint["layout"] = layout }
                if let segments = t.segments {
                    var segmentDicts = [[String: Any]]()
                    for seg in segments {
                        var segDict: [String: Any] = ["text": seg.text]
                        if let c = seg.color { segDict["color"] = c }
                        segmentDicts.append(segDict)
                    }
                    blueprint["segments"] = segmentDicts
                }
                
                let jsonString = (try? String(data: JSONSerialization.data(withJSONObject: blueprint), encoding: .utf8)) ?? "{}"
                
                textOverlays.append(TextGraphicOverlay(
                    text: t.text,
                    blueprintJson: jsonString,
                    startMs: t.startMs,
                    endMs: t.endMs,
                    videoWidth: project.export.width,
                    videoHeight: project.export.height
                ))
            case .effect(let e):
                activeEffects.append(e)
            case .audio(let a):
                audioNodes.append(a)
                // Add the extra audio track
                if let bgAudioTrack = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid),
                   let bgUrl = URL(string: a.src), // In a real app this would map to bundled assets
                   let bgAssetTrack = try? await AVURLAsset(url: bgUrl).loadTracks(withMediaType: .audio).first {
                    let start = CMTime(value: 0, timescale: 1000)
                    let duration = CMTime(value: CMTimeValue(a.endMs - a.startMs), timescale: 1000)
                    let timeRange = CMTimeRange(start: start, duration: duration)
                    let insertTime = CMTime(value: CMTimeValue(a.startMs), timescale: 1000)
                    try? bgAudioTrack.insertTimeRange(timeRange, of: bgAssetTrack, at: insertTime)
                }
            }
        }
        
        let audioMix = AVMutableAudioMix()
        var inputParams = [AVMutableAudioMixInputParameters]()
        
        if let audioTrack = composition.tracks(withMediaType: .audio).first {
            let param = AVMutableAudioMixInputParameters(track: audioTrack)
            
            // AutoEnhance: Basic normalization/boost
            if schema.settings.autoEnhance == true {
                // Boost main dialogue volume
                param.setVolume(1.5, at: .zero)
            } else {
                param.setVolume(1.0, at: .zero)
            }
            
            // AutoDuck: Lower main track volume when bg music plays
            for a in audioNodes where a.autoDuck == true {
                let duckStart = CMTime(value: CMTimeValue(a.startMs), timescale: 1000)
                let duckEnd = CMTime(value: CMTimeValue(a.endMs), timescale: 1000)
                let rampDuration = CMTime(value: 500, timescale: 1000) // 500ms fade
                
                param.setVolumeRamp(fromStartVolume: 1.0, toEndVolume: 0.1, timeRange: CMTimeRange(start: duckStart, duration: rampDuration))
                param.setVolume(0.1, at: duckStart + rampDuration)
                param.setVolumeRamp(fromStartVolume: 0.1, toEndVolume: 1.0, timeRange: CMTimeRange(start: duckEnd - rampDuration, duration: rampDuration))
            }
            
            inputParams.append(param)
        }
        audioMix.inputParameters = inputParams
        
        var videoComposition: AVMutableVideoComposition? = nil
        if !textOverlays.isEmpty || !activeEffects.isEmpty {
            videoComposition = AVMutableVideoComposition(asset: composition) { request in
                var image = request.sourceImage
                let timeMs = Int64(request.compositionTime.seconds * 1000)
                
                // Apply visual effects
                for effect in activeEffects where timeMs >= effect.startMs && timeMs <= effect.endMs {
                    switch effect.effectName {
                    case "black_and_white":
                        let filter = CIFilter(name: "CIColorControls")
                        filter?.setValue(image, forKey: kCIInputImageKey)
                        filter?.setValue(0.0, forKey: kCIInputSaturationKey)
                        if let out = filter?.outputImage { image = out }
                    case "vintage":
                        let filter = CIFilter(name: "CIPhotoEffectProcess")
                        filter?.setValue(image, forKey: kCIInputImageKey)
                        if let out = filter?.outputImage { image = out }
                    case "pixelate":
                        let filter = CIFilter(name: "CIPixellate")
                        filter?.setValue(image, forKey: kCIInputImageKey)
                        let scale = max(1, (effect.intensity ?? 0.5) * 50.0)
                        filter?.setValue(scale, forKey: kCIInputScaleKey)
                        if let out = filter?.outputImage { image = out }
                    case "zoom_blur":
                        let filter = CIFilter(name: "CIZoomBlur")
                        filter?.setValue(image, forKey: kCIInputImageKey)
                        let center = CIVector(x: image.extent.midX, y: image.extent.midY)
                        filter?.setValue(center, forKey: kCIInputCenterKey)
                        let amount = (effect.intensity ?? 0.5) * 20.0
                        filter?.setValue(amount, forKey: kCIInputAmountKey)
                        if let out = filter?.outputImage { image = out }
                    default:
                        break
                    }
                }
                
                let extent = image.extent
                if extent.isNull || extent.isInfinite || extent.width.isNaN || extent.height.isNaN || extent.width <= 0 || extent.height <= 0 {
                    let safeRect = CGRect(x: 0, y: 0, width: max(1.0, request.renderSize.width), height: max(1.0, request.renderSize.height))
                    let bg = CIImage(color: .black).cropped(to: safeRect)
                    request.finish(with: bg, context: nil)
                    return
                }
                
                let scaleX = request.renderSize.width / extent.width
                let scaleY = request.renderSize.height / extent.height
                let scale = max(scaleX, scaleY)
                
                let tx = (request.renderSize.width - extent.width * scale) / 2.0
                let ty = (request.renderSize.height - extent.height * scale) / 2.0
                
                if !tx.isNaN && !ty.isNaN && !scale.isNaN && !tx.isInfinite && !ty.isInfinite && !scale.isInfinite {
                    let transform = CGAffineTransform(translationX: -extent.minX, y: -extent.minY)
                        .concatenating(CGAffineTransform(scaleX: scale, y: scale))
                        .concatenating(CGAffineTransform(translationX: tx, y: ty))
                    image = image.transformed(by: transform)
                }
                var opacity: CGFloat = 1.0
                var bgColor = CIColor.black
                var translationX: CGFloat = 0.0
                var cropRatio: CGFloat = 1.0
                
                for v in videoNodes where timeMs >= v.startMs && timeMs <= v.endMs {
                    if let transition = v.transition {
                        let fadeMs: Int64 = 500
                        var progress: CGFloat = 1.0
                        
                        if timeMs < v.startMs + fadeMs {
                            progress = CGFloat(timeMs - v.startMs) / CGFloat(fadeMs)
                        } else if timeMs > v.endMs - fadeMs {
                            progress = CGFloat(v.endMs - timeMs) / CGFloat(fadeMs)
                        }
                        
                        if progress < 1.0 {
                            switch transition {
                            case "crossfade", "dip_to_black":
                                opacity = progress
                            case "dip_to_white":
                                opacity = progress
                                bgColor = .white
                            case "wipe":
                                cropRatio = progress
                            case "slide":
                                translationX = (timeMs < v.startMs + fadeMs) ? request.renderSize.width * (1.0 - progress) : -request.renderSize.width * (1.0 - progress)
                            default:
                                break
                            }
                        }
                    }
                }
                
                if translationX != 0 {
                    image = image.transformed(by: CGAffineTransform(translationX: translationX, y: 0))
                }
                
                if cropRatio < 1.0 {
                    let cropWidth = request.renderSize.width * cropRatio
                    let cropX = (request.renderSize.width - cropWidth) / 2.0
                    let cropRect = CGRect(x: cropX, y: 0, width: cropWidth, height: request.renderSize.height)
                    image = image.cropped(to: cropRect)
                }
                
                let bg = CIImage(color: bgColor).cropped(to: CGRect(origin: .zero, size: request.renderSize))
                if opacity < 1.0 {
                    let alphaFilter = CIFilter(name: "CIColorMatrix")
                    alphaFilter?.setValue(image, forKey: kCIInputImageKey)
                    alphaFilter?.setValue(CIVector(x: 0, y: 0, z: 0, w: opacity), forKey: "inputAVector")
                    if let out = alphaFilter?.outputImage { image = out }
                }
                
                image = image.composited(over: bg)
                
                for overlay in textOverlays {
                    if let overlayImage = overlay.getImage(presentationTimeUs: timeMs * 1000) {
                        image = overlayImage.composited(over: image)
                    }
                }
                
                request.finish(with: image, context: nil)
            }
            videoComposition?.frameDuration = CMTime(value: 1, timescale: 30)
            videoComposition?.renderSize = CGSize(width: project.export.width, height: project.export.height)
        }
        
        return (composition, videoComposition, audioMix)
    }
}
