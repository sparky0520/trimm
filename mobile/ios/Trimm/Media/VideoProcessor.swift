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
        
        return await MainActor.run {
            AVPlayerItem(asset: composition)
        }
    }
}
