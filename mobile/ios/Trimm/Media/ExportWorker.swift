import Foundation
import AVFoundation

@MainActor
public class ExportWorker: ObservableObject {
    @Published public var progress: Double = 0.0
    @Published public var isExporting: Bool = false
    @Published public var exportError: Error?
    @Published public var exportURL: URL?
    
    private var exportSession: AVAssetExportSession?
    private var progressTimer: Timer?
    
    public init() {
        self.isExporting = false
    }
    
    public func startExport(project: MobileProject) async {
        self.isExporting = true
        self.progress = 0.0
        self.exportError = nil
        self.exportURL = nil
        
        do {
            let (composition, videoComposition, audioMix) = try await ExportCoordinator.shared.buildComposition(project: project)
            
            let outputDir = FileManager.default.temporaryDirectory
            let fileName = "export_\(UUID().uuidString).mp4"
            let outputURL = outputDir.appendingPathComponent(fileName)
            
            guard let session = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetHighestQuality) else {
                throw ExportError.exportFailed(nil)
            }
            
            self.exportSession = session
            session.videoComposition = videoComposition
            session.audioMix = audioMix
            session.outputURL = outputURL
            session.outputFileType = .mp4
            session.shouldOptimizeForNetworkUse = true
            
            // Start progress tracking timer
            progressTimer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
                DispatchQueue.main.async {
                    if let self = self, let session = self.exportSession {
                        self.progress = Double(session.progress)
                    }
                }
            }
            
            await session.export()
            
            progressTimer?.invalidate()
            progressTimer = nil
            
            if session.status == .completed {
                self.progress = 1.0
                self.exportURL = outputURL
            } else {
                throw session.error ?? ExportError.exportFailed(nil)
            }
            
        } catch {
            self.exportError = error
        }
        
        self.isExporting = false
    }
    
    public func cancelExport() {
        exportSession?.cancelExport()
        progressTimer?.invalidate()
        progressTimer = nil
        isExporting = false
        progress = 0.0
    }
}
