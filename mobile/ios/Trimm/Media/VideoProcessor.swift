import Foundation
import AVFoundation

public class VideoProcessor {
    public static let shared = VideoProcessor()
    private init() {}
    
    public func createPreviewComposition(project: MobileProject) async throws -> AVPlayerItem {
        guard let url = URL(string: project.source.uri) else {
            throw NSError(domain: "VideoProcessor", code: 1, userInfo: [NSLocalizedDescriptionKey: "Invalid source URI"])
        }
        
        let asset = AVURLAsset(url: url)
        
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
                let playDuration = CMTime(value: CMTimeValue(playMs), timescale: 1000)
                let playTimeRange = CMTimeRange(start: .zero, duration: playDuration)
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
                let playDuration = CMTime(value: CMTimeValue(sfxDurationMs), timescale: 1000)
                let playTimeRange = CMTimeRange(start: .zero, duration: playDuration)
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
        
        return await MainActor.run {
            let item = AVPlayerItem(asset: composition)
            item.audioMix = audioMix
            return item
        }
    }
}
