import Foundation
import Observation
import AVFoundation

@Observable
public class EditorViewModel {
    public var state: EditorState = EditorState()
    public var player: AVPlayer?
    
    public init() {
        // Load past chats on init if SwiftData is integrated
    }
    
    public func importSources(urls: [URL]) {
        // Mocking the video property extraction for now
        let sources = urls.map { url in
            SourceMedia(
                uri: url.absoluteString,
                fingerprint: UUID().uuidString,
                durationMs: 15000,
                width: 1920,
                height: 1080,
                rotation: 0,
                hasAudio: true
            )
        }
        state.sources = sources
    }
    
    public func generateDraft(prompt: String) {
        state.initialRequest = prompt
        state.isProcessing = true
        state.jobMessage = "Reading your clips and preparing a proposal…"
        
        guard let firstSource = state.sources.first else {
            state.errorMessage = "Please import a video first."
            state.isProcessing = false
            return
        }
        
        // Mock project generation
        let project = MobileProject(
            schemaVersion: 1,
            id: UUID().uuidString,
            title: "New Edit",
            source: firstSource,
            sources: state.sources,
            proxyUri: nil,
            preferences: state.preferences,
            analysis: Analysis(),
            evidenceGraphs: [],
            operations: [],
            undo: [],
            export: ExportSettings(),
            revision: 1
        )
        
        self.state.project = project
        
        Task {
            await refreshPlayer(project: project)
            
            // Simulate network delay
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            
            await MainActor.run {
                self.state.jobMessage = "Analysis complete"
                self.state.isProcessing = false
            }
        }
    }
    
    private func refreshPlayer(project: MobileProject) async {
        do {
            let item = try await VideoProcessor.shared.createPreviewComposition(project: project)
            await MainActor.run {
                if let p = self.player {
                    p.replaceCurrentItem(with: item)
                } else {
                    self.player = AVPlayer(playerItem: item)
                }
                self.player?.play()
            }
        } catch {
            print("Failed to create preview composition: \(error)")
        }
    }
    
    public func reviseStory(request: String) {
        guard !request.isEmpty, !state.isProcessing, let _ = state.project else { return }
        
        state.isProcessing = true
        state.jobMessage = "Continuing the conversation…"
        
        Task {
            // Simulate network/agent run delay
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            
            await MainActor.run {
                self.state.jobMessage = "Revised proposal ready to review"
                self.state.isProcessing = false
            }
        }
    }
}
