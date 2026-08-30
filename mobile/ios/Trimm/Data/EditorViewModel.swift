import Foundation
import Observation

@Observable
public class EditorViewModel {
    public var state: EditorState = EditorState()
    
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
            // Simulate network delay
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            
            await MainActor.run {
                self.state.jobMessage = "Analysis complete"
                self.state.isProcessing = false
            }
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
