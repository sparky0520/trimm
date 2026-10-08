import Foundation
import Observation
import AVFoundation

@Observable
@MainActor
public class EditorViewModel {
    public var state: EditorState = EditorState()
    public var player: AVPlayer?
    private var exportSession: AVAssetExportSession?
    
    public init() {
        // Load past chats on init
        Task {
            await loadPastChats()
        }
    }
    
    @MainActor
    public func loadPastChats() async {
        state.isLoadingPastChats = true
        do {
            let chats = try await ChatRepository.shared.getProjects()
            state.pastChats = chats
        } catch {
            print("Failed to load past chats: \(error)")
        }
        state.isLoadingPastChats = false
    }
    
    @MainActor
    public func deleteChat(id: String) async {
        do {
            try await ChatRepository.shared.deleteProject(id: id)
            if state.project?.id == id {
                state.project = nil
                state.sources = []
                state.messages = []
                player?.pause()
                player = nil
            }
            await loadPastChats()
        } catch {
            print("Failed to delete chat: \(error)")
        }
    }
    
    @MainActor
    public func loadProject(id: String) async {
        state.isProcessing = true
        do {
            let project = try await ChatRepository.shared.getProject(id: id)
            self.state.project = project
            self.state.sources = project.sources
            self.state.messages = [] // Alternatively load from project.analysis.chatHistory if available
            await refreshPlayer(project: project)
        } catch {
            print("Failed to load project: \(error)")
        }
        state.isProcessing = false
    }
    
    @MainActor
    public func undo() {
        guard let project = state.project else { return }
        let restored = project.restoreCheckpoint()
        state.project = restored
        Task { await refreshPlayer(project: restored) }
    }
    
    @MainActor
    public func redo() {
        guard let project = state.project else { return }
        let redone = project.redoCheckpoint()
        state.project = redone
        Task { await refreshPlayer(project: redone) }
    }
    
    @MainActor
    public func importSources(urls: [URL]) async {
        var newSources: [SourceMedia] = []
        for url in urls {
            let asset = AVURLAsset(url: url)
            do {
                let duration = try await asset.load(.duration)
                let durationMs = Int64(duration.seconds * 1000)
                let videoTracks = try await asset.loadTracks(withMediaType: .video)
                let audioTracks = try await asset.loadTracks(withMediaType: .audio)
                let hasAudio = audioTracks.count > 0
                
                var width = 1920
                var height = 1080
                var rotation = 0
                if let videoTrack = videoTracks.first {
                    let size = try await videoTrack.load(.naturalSize)
                    let transform = try await videoTrack.load(.preferredTransform)
                    
                    // Determine if the video is portrait based on transform
                    let isPortrait = transform.a == 0 && transform.d == 0
                    if isPortrait {
                        width = Int(size.height)
                        height = Int(size.width)
                        rotation = 90 // Defaulting to 90 for portrait, but can be extracted precisely from transform
                    } else {
                        width = Int(size.width)
                        height = Int(size.height)
                        rotation = 0
                    }
                }
                
                newSources.append(SourceMedia(
                    uri: url.absoluteString,
                    fingerprint: UUID().uuidString,
                    durationMs: durationMs,
                    width: width,
                    height: height,
                    rotation: rotation,
                    hasAudio: hasAudio
                ))
            } catch {
                print("Failed to load asset properties: \(error)")
            }
        }
        self.state.sources = newSources
    }
    
    public func initializeProject() {
        guard let firstSource = state.sources.first else {
            state.errorMessage = "Please import a video first."
            return
        }
        
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
        self.state.messages = []
        
        Task {
            try? await ChatRepository.shared.syncProject(project: project)
            await loadPastChats()
        }
        
        Task {
            await refreshPlayer(project: project)
            
            if let url = URL(string: firstSource.uri) {
                do {
                    let words = try await TranscriptionService.shared.transcribe(url: url)
                    await MainActor.run {
                        if let currentProject = self.state.project {
                            let updatedAnalysis = Analysis(
                                words: words,
                                captions: currentProject.analysis.captions,
                                speechHints: currentProject.analysis.speechHints,
                                lowConfidenceFumbles: currentProject.analysis.lowConfidenceFumbles,
                                takeGroups: currentProject.analysis.takeGroups,
                                warnings: currentProject.analysis.warnings,
                                completedAtEpochMs: Int64(Date().timeIntervalSince1970 * 1000)
                            )
                            
                            let updatedProject = MobileProject(
                                schemaVersion: currentProject.schemaVersion,
                                id: currentProject.id,
                                title: currentProject.title,
                                source: currentProject.source,
                                sources: currentProject.sources,
                                proxyUri: currentProject.proxyUri,
                                preferences: currentProject.preferences,
                                analysis: updatedAnalysis,
                                evidenceGraphs: currentProject.evidenceGraphs,
                                operations: currentProject.operations,
                                undo: currentProject.undo,
                                export: currentProject.export,
                                revision: currentProject.revision
                            )
                            self.state.project = updatedProject
                        }
                    }
                } catch {
                    print("Transcription failed: \(error)")
                }
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
        guard !request.isEmpty, !state.isProcessing, let project = state.project else { return }
        
        state.isProcessing = true
        state.messages.append(ChatMessage(text: request, isUser: true))
        state.jobMessage = "Continuing the conversation…"
        state.messages.append(ChatMessage(text: state.jobMessage!, isUser: false))
        
        Task {
            let agentSources = state.sources.map {
                AgentSourceInventory(id: $0.uri, fingerprint: $0.fingerprint, durationMs: $0.durationMs, width: $0.width, height: $0.height, hasAudio: $0.hasAudio)
            }
            let transcriptWords = project.analysis.words.map {
                AgentTranscriptWord(
                    sourceId: project.source.uri,
                    text: $0.text,
                    startMs: $0.range.startMs,
                    endMs: $0.range.endMs,
                    confidence: $0.confidence,
                    speakerId: $0.speakerId
                )
            }
            
            let agentRequest = AgentRunRequest(
                projectId: project.id,
                deviceSessionId: UUID().uuidString,
                projectRevision: project.revision,
                intent: request,
                sources: agentSources,
                transcript: transcriptWords,
                evidenceJobIds: [],
                existingOperations: project.operations,
                uploadInitialVideoContext: false,
                budgets: AgentBudgets()
            )
            
            do {
                let response = try await ChatRepository.shared.executeAgentTask(request: agentRequest)
                await MainActor.run {
                    if response.type == "edit_program", let operations = response.operations {
                        let updatedProject = project.applyEditInstructions(next: operations)
                        self.state.project = updatedProject
                        self.state.jobMessage = "Revised proposal ready to review"
                        self.state.messages.append(ChatMessage(text: "Revised proposal ready to review", isUser: false))
                        
                        Task {
                            try? await ChatRepository.shared.syncProject(project: updatedProject)
                            await self.loadPastChats()
                        }
                    }
                    self.state.isProcessing = false
                }
                
                if let updatedProject = await MainActor.run(resultType: MobileProject?.self, body: { self.state.project }) {
                    await refreshPlayer(project: updatedProject)
                }
                
            } catch {
                await MainActor.run {
                    self.state.jobMessage = "Failed to revise"
                    self.state.isProcessing = false
                }
            }
        }
    }
    
    @MainActor
    public func export() {
        guard let project = state.project else { return }
        state.isExporting = true
        state.exportProgress = 0.0
        
        Task {
            do {
                let (composition, videoComp, audioMix) = try await ExportCoordinator.shared.buildComposition(project: project)
                
                let outputURL = FileManager.default.temporaryDirectory.appendingPathComponent("exported_\(UUID().uuidString).mp4")
                if FileManager.default.fileExists(atPath: outputURL.path) {
                    try FileManager.default.removeItem(at: outputURL)
                }
                
                guard let session = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetHighestQuality) else {
                    throw ExportError.exportFailed(nil)
                }
                
                session.outputURL = outputURL
                session.outputFileType = .mp4
                session.videoComposition = videoComp
                session.audioMix = audioMix
                
                self.exportSession = session
                
                // Track progress
                let progressTask = Task { @MainActor in
                    while let s = self.exportSession {
                        self.state.exportProgress = s.progress
                        if s.status != .exporting && s.status != .waiting { break }
                        try? await Task.sleep(nanoseconds: 100_000_000)
                    }
                }
                
                await session.export()
                
                progressTask.cancel()
                self.exportSession = nil
                
                await MainActor.run {
                    if session.status == .completed {
                        // Save to Photos or share sheet implementation deferred
                        print("Export completed at: \(outputURL)")
                        self.state.jobMessage = "Export complete!"
                    } else if session.status == .cancelled {
                        self.state.jobMessage = "Export cancelled."
                    } else {
                        self.state.errorMessage = "Export failed: \(session.error?.localizedDescription ?? "Unknown error")"
                    }
                    self.state.isExporting = false
                }
                
            } catch {
                await MainActor.run {
                    self.state.errorMessage = "Export failed: \(error.localizedDescription)"
                    self.state.isExporting = false
                }
            }
        }
    }
    
    @MainActor
    public func cancelExport() {
        exportSession?.cancelExport()
        exportSession = nil
        state.isExporting = false
        state.jobMessage = "Export cancelled."
    }
}
