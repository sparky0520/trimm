import Foundation
import AVFoundation

class DerivativeWriter {
    enum DerivativeError: Error {
        case exportFailed
        case invalidAsset
    }
    
    static func createProxy(for inputURL: URL, outputURL: URL, resolution: String = AVAssetExportPreset1280x720) async throws {
        let asset = AVURLAsset(url: inputURL)
        
        guard try await asset.load(.isExportable) else {
            throw DerivativeError.invalidAsset
        }
        
        if FileManager.default.fileExists(atPath: outputURL.path) {
            try FileManager.default.removeItem(at: outputURL)
        }
        
        guard let exportSession = AVAssetExportSession(asset: asset, presetName: resolution) else {
            throw DerivativeError.exportFailed
        }
        
        exportSession.outputURL = outputURL
        exportSession.outputFileType = .mp4
        exportSession.shouldOptimizeForNetworkUse = true
        
        await exportSession.export()
        
        if exportSession.status != .completed {
            throw exportSession.error ?? DerivativeError.exportFailed
        }
    }
}
