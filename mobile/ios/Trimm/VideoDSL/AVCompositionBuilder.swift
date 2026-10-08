import Foundation
import AVFoundation
import CoreImage

class AVCompositionBuilder {
    enum BuilderError: Error {
        case missingSourceURL
        case assetNotFound(String)
    }
    
    func build(from dsl: VideoDSL) async throws -> AVPlayerItem {
        let composition = AVMutableComposition()
        var layerInstructions: [AVVideoCompositionLayerInstruction] = []
        let audioMix = AVMutableAudioMix()
        var audioMixParams: [AVMutableAudioMixInputParameters] = []
        var hasEffects = false
        
        for layer in dsl.timeline.layers {
            if layer.type == "media" {
                guard let sourceURLString = layer.source?.url else {
                    continue // Skip if no URL
                }
                
                guard let assetURL = resolveURL(sourceURLString) else {
                    print("Could not resolve URL for: \(sourceURLString)")
                    continue
                }
                
                let asset = AVURLAsset(url: assetURL)
                
                guard let videoTrack = try? await asset.loadTracks(withMediaType: .video).first else {
                    print("Could not load video track for asset: \(assetURL)")
                    continue
                }
                
                guard let compositionVideoTrack = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid) else {
                    continue
                }
                
                let startCMTime = cmTime(from: layer.startMs)
                let durationCMTime = cmTime(from: layer.durationMs)
                let mediaStartCMTime = cmTime(from: layer.source?.mediaStartTimeMs ?? 0)
                
                let timeRange = CMTimeRange(start: mediaStartCMTime, duration: durationCMTime)
                
                do {
                    try compositionVideoTrack.insertTimeRange(timeRange, of: videoTrack, at: startCMTime)
                    
                    let layerInstruction = AVMutableVideoCompositionLayerInstruction(assetTrack: compositionVideoTrack)
                    let preferredTransform = videoTrack.preferredTransform
                    
                    if let spatial = layer.spatial, spatial.keyframes.count > 0 {
                        let sortedKeyframes = spatial.keyframes.sorted { $0.timeMs < $1.timeMs }
                        
                        if sortedKeyframes.count == 1 {
                            let kf = sortedKeyframes[0]
                            let t = preferredTransform.concatenating(self.transform(for: kf))
                            layerInstruction.setTransform(t, at: .zero)
                        } else {
                            for i in 0..<(sortedKeyframes.count - 1) {
                                let startKf = sortedKeyframes[i]
                                let endKf = sortedKeyframes[i+1]
                                
                                let startCMTimeRamp = cmTime(from: layer.startMs + startKf.timeMs)
                                let endCMTimeRamp = cmTime(from: layer.startMs + endKf.timeMs)
                                let rampRange = CMTimeRange(start: startCMTimeRamp, end: endCMTimeRamp)
                                
                                let startTransform = preferredTransform.concatenating(self.transform(for: startKf))
                                let endTransform = preferredTransform.concatenating(self.transform(for: endKf))
                                
                                layerInstruction.setTransformRamp(fromStart: startTransform, toEnd: endTransform, timeRange: rampRange)
                            }
                        }
                    } else {
                        layerInstruction.setTransform(preferredTransform, at: .zero)
                    }
                    
                    if let compositing = layer.compositing, let opacity = compositing.opacity, opacity.keyframes.count > 0 {
                        let sortedOpacities = opacity.keyframes.sorted { $0.timeMs < $1.timeMs }
                        if sortedOpacities.count == 1 {
                            layerInstruction.setOpacity(sortedOpacities[0].value, at: .zero)
                        } else {
                            for i in 0..<(sortedOpacities.count - 1) {
                                let startKf = sortedOpacities[i]
                                let endKf = sortedOpacities[i+1]
                                let rampRange = CMTimeRange(start: cmTime(from: layer.startMs + startKf.timeMs),
                                                            end: cmTime(from: layer.startMs + endKf.timeMs))
                                layerInstruction.setOpacityRamp(fromStartOpacity: startKf.value, toEndOpacity: endKf.value, timeRange: rampRange)
                            }
                        }
                    }
                    if let blendMode = layer.compositing?.blendMode {
                        print("Warning: blendMode \(blendMode) requires CoreImage or custom compositor, skipping in basic instruction.")
                    }
                    
                    layerInstructions.append(layerInstruction)
                    
                    if let audio = layer.audio, let volume = audio.volume, volume.keyframes.count > 0 {
                        if let audioTrack = try? await asset.loadTracks(withMediaType: .audio).first,
                           let compositionAudioTrack = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) {
                            try? compositionAudioTrack.insertTimeRange(timeRange, of: audioTrack, at: startCMTime)
                            
                            let audioParams = AVMutableAudioMixInputParameters(track: compositionAudioTrack)
                            let sortedVolumes = volume.keyframes.sorted { $0.timeMs < $1.timeMs }
                            if sortedVolumes.count == 1 {
                                audioParams.setVolume(sortedVolumes[0].value, at: .zero)
                            } else {
                                for i in 0..<(sortedVolumes.count - 1) {
                                    let startKf = sortedVolumes[i]
                                    let endKf = sortedVolumes[i+1]
                                    let rampRange = CMTimeRange(start: cmTime(from: layer.startMs + startKf.timeMs),
                                                                end: cmTime(from: layer.startMs + endKf.timeMs))
                                    audioParams.setVolumeRamp(fromStartVolume: startKf.value, toEndVolume: endKf.value, timeRange: rampRange)
                                }
                            }
                            audioMixParams.append(audioParams)
                        }
                    }
                    
                    if let effects = layer.effects, !effects.isEmpty {
                        hasEffects = true
                    }
                    
                } catch {
                    print("Failed to insert time range for layer \(layer.id): \(error)")
                }
            }
        }
        
        audioMix.inputParameters = audioMixParams
        
        let playerItem = AVPlayerItem(asset: composition)
        playerItem.audioMix = audioMix
        
        if hasEffects {
            let videoComposition = AVMutableVideoComposition(asset: composition, applyingCIFiltersWithHandler: { request in
                var outputImage = request.sourceImage
                // Apply effects logic here.
                // Warning: using this overrides the layerInstructions (transforms/opacity).
                // A complete solution requires mapping transformations and opacity to CIFilters
                // or building a custom AVVideoCompositing class.
                for layer in dsl.timeline.layers {
                    if let effects = layer.effects {
                        for effect in effects {
                            if effect.type == "colorMatrix", let matrix = effect.matrix, matrix.count == 20 {
                                if let filter = CIFilter(name: "CIColorMatrix") {
                                    filter.setValue(outputImage, forKey: kCIInputImageKey)
                                    filter.setValue(CIVector(x: CGFloat(matrix[0]), y: CGFloat(matrix[1]), z: CGFloat(matrix[2]), w: CGFloat(matrix[3])), forKey: "inputRVector")
                                    filter.setValue(CIVector(x: CGFloat(matrix[5]), y: CGFloat(matrix[6]), z: CGFloat(matrix[7]), w: CGFloat(matrix[8])), forKey: "inputGVector")
                                    filter.setValue(CIVector(x: CGFloat(matrix[10]), y: CGFloat(matrix[11]), z: CGFloat(matrix[12]), w: CGFloat(matrix[13])), forKey: "inputBVector")
                                    filter.setValue(CIVector(x: CGFloat(matrix[15]), y: CGFloat(matrix[16]), z: CGFloat(matrix[17]), w: CGFloat(matrix[18])), forKey: "inputAVector")
                                    
                                    if let outImage = filter.outputImage {
                                        outputImage = outImage
                                    }
                                }
                            } else {
                                print("Warning: unknown or malformed effect type: \(effect.type)")
                            }
                        }
                    }
                }
                request.finish(with: outputImage, context: nil)
            })
            playerItem.videoComposition = videoComposition
        } else {
            let videoComposition = AVMutableVideoComposition()
            videoComposition.renderSize = CGSize(width: dsl.project.dimensions.width, height: dsl.project.dimensions.height)
            videoComposition.frameDuration = CMTime(value: 1, timescale: CMTimeScale(dsl.project.fps))
            
            let mainInstruction = AVMutableVideoCompositionInstruction()
            mainInstruction.timeRange = CMTimeRange(start: .zero, duration: cmTime(from: dsl.project.durationMs))
            mainInstruction.layerInstructions = layerInstructions
            videoComposition.instructions = [mainInstruction]
            playerItem.videoComposition = videoComposition
        }
        
        return playerItem
    }
    
    private func transform(for keyframe: SpatialKeyframe) -> CGAffineTransform {
        let scale = max(keyframe.scale ?? 1.0, 0.01) // Clamp to prevent 0 scale
        let rotation = keyframe.rotation ?? 0.0
        let x = keyframe.x ?? 0.0
        let y = keyframe.y ?? 0.0
        
        return CGAffineTransform(translationX: x, y: y)
            .scaledBy(x: scale, y: scale)
            .rotated(by: rotation)
    }
    
    private func cmTime(from ms: Int) -> CMTime {
        return CMTime(value: CMTimeValue(ms), timescale: 1000)
    }
    
    private func resolveURL(_ urlString: String) -> URL? {
        if urlString.hasPrefix("local://") {
            let filename = urlString.replacingOccurrences(of: "local://", with: "")
            let components = filename.components(separatedBy: ".")
            if components.count == 2 {
                return Bundle.main.url(forResource: components[0], withExtension: components[1])
            }
        }
        return URL(string: urlString)
    }
}
