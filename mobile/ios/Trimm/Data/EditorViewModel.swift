import Foundation
import Observation
import AVFoundation
import Photos

@Observable
@MainActor
public class EditorViewModel {
    public var state: EditorState = EditorState()
    public var player: AVPlayer?
    private var exportSession: AVAssetExportSession?
    
    public init() {
        // Initialization without fetching to avoid AuthError.sessionMissing
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
            self.state.messages = project.analysis.chatHistory.flatMap { turn in
                [
                    ChatMessage(text: turn.request, isUser: true),
                    ChatMessage(text: turn.response, isUser: false)
                ]
            }
            
            if let docDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first {
                let fileUrl = docDir.appendingPathComponent("\(id)_composition.json")
                if let data = try? Data(contentsOf: fileUrl) {
                    let decoder = JSONDecoder()
                    if let schema = try? decoder.decode(CompositionSchema.self, from: data) {
                        self.state.compositionSchema = schema
                    }
                }
            }
            
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
        state.isProcessing = true
        var newSources: [SourceMedia] = []
        
        let fileManager = FileManager.default
        guard let documentDirectory = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first else {
            state.isProcessing = false
            return
        }
        
        for url in urls {
            var finalUrl = url
            if url.isFileURL {
                let filename = UUID().uuidString + "-" + url.lastPathComponent
                let destURL = documentDirectory.appendingPathComponent(filename)
                let startedAccess = url.startAccessingSecurityScopedResource()
                
                // Move file instead of copying to avoid delay
                do {
                    try await Task.detached {
                        if fileManager.fileExists(atPath: destURL.path) {
                            try fileManager.removeItem(at: destURL)
                        }
                        try fileManager.moveItem(at: url, to: destURL)
                    }.value
                    finalUrl = destURL
                } catch {
                    print("Failed to move file to documents: \(error)")
                }
                
                if startedAccess { url.stopAccessingSecurityScopedResource() }
            }
            
            let asset = AVURLAsset(url: finalUrl)
            do {
                let duration = try await asset.load(.duration)
                let durationMs = Int64(duration.seconds * 1000)
                let videoTracks = try await asset.loadTracks(withMediaType: .video)
                let audioTracks = try await asset.loadTracks(withMediaType: .audio)
                let hasAudio = audioTracks.count > 0
                
                var width = 0
                var height = 0
                var rotation = 0
                if let videoTrack = videoTracks.first {
                    let size = try await videoTrack.load(.naturalSize)
                    let transform = try await videoTrack.load(.preferredTransform)
                    
                    let radians = atan2(transform.b, transform.a)
                    var degrees = Int(radians * 180 / .pi)
                    if degrees < 0 { degrees += 360 }
                    rotation = degrees
                    
                    let isPortrait = abs(degrees) == 90 || abs(degrees) == 270
                    if isPortrait {
                        width = Int(size.height)
                        height = Int(size.width)
                    } else {
                        width = Int(size.width)
                        height = Int(size.height)
                    }
                }
                
                newSources.append(SourceMedia(
                    uri: finalUrl.absoluteString,
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
        state.isProcessing = false
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
            revision: 1
        )
        
        self.state.project = project
        self.state.messages = [ChatMessage(text: "Extracting audio and transcribing video. This may take a few moments...", isUser: false)]
        
        
        Task {
            await MainActor.run { state.isProcessing = true }
            await refreshPlayer(project: project)
            
            if let url = firstSource.resolvedUrl {
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
                                completedAtEpochMs: Int64(Date().timeIntervalSince1970 * 1000),
                                activeTurnId: currentProject.analysis.activeTurnId,
                                activeCheckpointId: currentProject.analysis.activeCheckpointId,
                                editorialPlan: currentProject.analysis.editorialPlan,
                                pendingProposal: currentProject.analysis.pendingProposal,
                                chatHistory: currentProject.analysis.chatHistory,
                                settledConstraints: currentProject.analysis.settledConstraints
                            )
                            
                            self.state.messages.removeAll { $0.text == "Extracting audio and transcribing video. This may take a few moments..." }
                            self.state.messages.append(ChatMessage(text: "Transcription complete. What would you like to edit?", isUser: false))
                            
                            let newTitle: String
                            if words.isEmpty {
                                newTitle = "Untitled Chat"
                            } else {
                                let prefix = words.prefix(4).map { $0.text }.joined(separator: " ")
                                newTitle = prefix.isEmpty ? "Untitled Chat" : prefix
                            }
                            
                            let updatedProject = MobileProject(
                                schemaVersion: currentProject.schemaVersion,
                                id: currentProject.id,
                                title: newTitle,
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
                            
                            // Initialize CompositionSchema
                            let transcripts = words.map { TranscriptWordNode(word: $0.text, startMs: $0.range.startMs, endMs: $0.range.endMs) }
                            
                            var silences: [TimeRangeNode] = []
                            var lastEnd: Int64 = 0
                            for word in words {
                                if word.range.startMs - lastEnd > 500 {
                                    silences.append(TimeRangeNode(startMs: lastEnd, endMs: word.range.startMs))
                                }
                                lastEnd = word.range.endMs
                            }
                            let durationMs = firstSource.durationMs
                            if durationMs - lastEnd > 500 {
                                silences.append(TimeRangeNode(startMs: lastEnd, endMs: durationMs))
                            }
                            
                            let metadata = CompositionMetadata(transcripts: transcripts, silences: silences)
                            var timeline: [TimelineNode] = []
                            timeline.append(.video(VideoNode(id: UUID().uuidString, src: firstSource.uri, startMs: 0, endMs: durationMs, sourceStartMs: 0)))
                            
                            var currentCaptionText = ""
                            var currentCaptionStart: Int64 = 0
                            var currentCaptionEnd: Int64 = 0
                            var wordCount = 0
                            
                            for i in 0..<words.count {
                                let word = words[i]
                                if wordCount == 0 {
                                    currentCaptionStart = word.range.startMs
                                }
                                currentCaptionText += (wordCount == 0 ? "" : " ") + word.text
                                currentCaptionEnd = word.range.endMs
                                wordCount += 1
                                
                                let isPause = (i < words.count - 1) && (words[i+1].range.startMs - currentCaptionEnd > 400)
                                if wordCount >= 4 || isPause || i == words.count - 1 {
                                    let textNode = TextNode(id: UUID().uuidString, text: currentCaptionText, startMs: currentCaptionStart, endMs: currentCaptionEnd, style: TextNodeStyle(color: "#FFFFFF", fontSize: 100, posY: -0.35))
                                    timeline.append(.text(textNode))
                                    currentCaptionText = ""
                                    wordCount = 0
                                }
                            }
                            
                            let schema = CompositionSchema(settings: CompositionSettings(), metadata: metadata, timeline: timeline)
                            self.state.compositionSchema = schema
                            
                            if let docDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first {
                                let fileUrl = docDir.appendingPathComponent("\(updatedProject.id)_composition.json")
                                let encoder = JSONEncoder()
                                encoder.outputFormatting = .prettyPrinted
                                if let data = try? encoder.encode(schema) {
                                    try? data.write(to: fileUrl)
                                }
                            }
                        }
                    }
                    
                    if let currentProject = await MainActor.run(resultType: MobileProject?.self, body: { self.state.project }) {
                        do {
                            try await ChatRepository.shared.syncProject(project: currentProject)
                        } catch {
                            await MainActor.run { self.state.errorMessage = "Sync failed: \(error.localizedDescription)" }
                        }
                        await loadPastChats()
                    }
                } catch {
                    print("Transcription failed: \(error)")
                    await MainActor.run { 
                        self.state.errorMessage = "Transcription failed: \(error.localizedDescription)"
                        self.state.messages.removeAll { $0.text == "Extracting audio and transcribing video. This may take a few moments..." }
                    }
                }
            }
            await MainActor.run { state.isProcessing = false }
        }
    }
    
    private func refreshPlayer(project: MobileProject) async {
        do {
            let item = try await VideoProcessor.shared.createPreviewComposition(project: project, schema: self.state.compositionSchema)
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
            await MainActor.run {
                self.state.errorMessage = "Failed to load video: \(error.localizedDescription)"
            }
        }
    }
    
    public func reviseStory(request: String) {
        guard !request.isEmpty, !state.isProcessing, let project = state.project else { return }
        
        state.isProcessing = true
        state.messages.append(ChatMessage(text: request, isUser: true))
        state.jobMessage = "Thinking…"
        state.messages.append(ChatMessage(text: state.jobMessage!, isUser: false))
        
        Task {
            let isAuth = await ChatRepository.shared.isAuthenticated()
            if !isAuth {
                await MainActor.run {
                    self.state.jobMessage = nil
                    self.state.messages.removeAll { $0.text == "Thinking…" || $0.text == "Editing…" }
                    self.state.messages.append(ChatMessage(text: "Please sign in to continue chat.", isUser: false))
                    self.state.isProcessing = false
                }
                return
            }
            
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
            
            let currentHistory = self.state.messages
                .filter { $0.text != "Thinking…" && $0.text != "Editing…" && $0.text != "Please sign in to continue chat." }
                .map { AgentChatMessage(role: $0.isUser ? "user" : "assistant", content: $0.text) }
            
            let agentRequest = AgentRunRequest(
                projectId: project.id,
                deviceSessionId: UUID().uuidString,
                projectRevision: project.revision,
                intent: request,
                sources: agentSources,
                transcript: transcriptWords,
                history: currentHistory,
                evidenceJobIds: [],
                existingOperations: project.operations,
                schema: self.state.compositionSchema,
                uploadInitialVideoContext: false,
                budgets: AgentBudgets()
            )
            
            do {
                let response = try await ChatRepository.shared.executeAgentTask(request: agentRequest) { status in
                    Task { @MainActor in
                        if status == "editing" {
                            self.state.jobMessage = "Editing…"
                            if let lastIndex = self.state.messages.lastIndex(where: { $0.text == "Thinking…" }) {
                                self.state.messages.remove(at: lastIndex)
                                self.state.messages.append(ChatMessage(text: "Editing…", isUser: false))
                            }
                        }
                    }
                }
                await MainActor.run {
                    if response.type == "edit_program" || response.type == "schema_patch" {
                        let replyText = response.summary ?? "Revised proposal ready to review"
                        
                        if let updatedSchema = response.schema {
                            self.state.compositionSchema = updatedSchema
                            
                            // Save schema locally
                            if let docDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first {
                                let fileUrl = docDir.appendingPathComponent("\(project.id)_composition.json")
                                let encoder = JSONEncoder()
                                encoder.outputFormatting = .prettyPrinted
                                if let data = try? encoder.encode(updatedSchema) {
                                    try? data.write(to: fileUrl)
                                }
                            }
                        }
                        
                        let updatedProject = project.applyEditInstructions(next: response.operations ?? [], removedOperations: response.removedOperations ?? [], request: request, response: replyText)
                        self.state.project = updatedProject
                        self.state.jobMessage = replyText
                        self.state.messages.removeAll { $0.text == "Thinking…" || $0.text == "Editing…" }
                        self.state.messages.append(ChatMessage(text: replyText, isUser: false))
                        
                        Task {
                            do {
                                try await ChatRepository.shared.syncProject(project: updatedProject)
                            } catch {
                                await MainActor.run { self.state.errorMessage = "Sync failed: \(error.localizedDescription)" }
                            }
                            await self.loadPastChats()
                        }
                    } else if response.type == "clarification" {
                        self.state.messages.removeAll { $0.text == "Thinking…" || $0.text == "Editing…" }
                        if let questions = response.questions, let firstQ = questions.first {
                            self.state.pendingClarification = firstQ
                            self.state.messages.append(ChatMessage(text: firstQ.prompt, isUser: false))
                        }
                    }
                    self.state.isProcessing = false
                }
                
                if let updatedProject = await MainActor.run(resultType: MobileProject?.self, body: { self.state.project }) {
                    await refreshPlayer(project: updatedProject)
                }
                
            } catch {
                await MainActor.run {
                    self.state.messages.removeAll { $0.text == "Thinking…" || $0.text == "Editing…" }
                    if let repoError = error as? ChatRepositoryError, repoError == .unauthorized {
                        self.state.jobMessage = nil
                        self.state.messages.append(ChatMessage(text: "Please sign in to continue chat.", isUser: false))
                    } else {
                        self.state.jobMessage = "Failed: \(error.localizedDescription)"
                        self.state.messages.append(ChatMessage(text: "Error: \(error.localizedDescription)", isUser: false))
                    }
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
                let (composition, videoComp, audioMix) = try await ExportCoordinator.shared.buildComposition(project: project, schema: state.compositionSchema)
                
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
                        if s.status == .completed || s.status == .failed || s.status == .cancelled { break }
                        try? await Task.sleep(nanoseconds: 100_000_000)
                    }
                }
                
                await session.export()
                
                progressTask.cancel()
                self.exportSession = nil
                
                let sessionStatus = session.status
                let sessionError = session.error
                
                if sessionStatus == .completed {
                    do {
                        try await PHPhotoLibrary.requestAuthorization(for: .addOnly)
                        try await PHPhotoLibrary.shared().performChanges {
                            PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: outputURL)
                        }
                        await MainActor.run {
                            print("Export completed and saved to Camera Roll: \(outputURL)")
                            self.state.jobMessage = "Export complete! Saved to Camera Roll."
                            self.state.isExporting = false
                        }
                    } catch {
                        await MainActor.run {
                            print("Failed to save to Camera Roll: \(error)")
                            self.state.jobMessage = "Export complete, but failed to save to Photos."
                            self.state.isExporting = false
                        }
                    }
                } else {
                    await MainActor.run {
                        if sessionStatus == .cancelled {
                            self.state.jobMessage = "Export cancelled."
                        } else {
                            self.state.errorMessage = "Export failed: \(sessionError?.localizedDescription ?? "Unknown error")"
                        }
                        self.state.isExporting = false
                    }
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
