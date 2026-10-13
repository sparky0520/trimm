import SwiftUI
import AVKit
import AVFoundation
import PhotosUI

public struct EditorScreen: View {
    @Bindable var viewModel: EditorViewModel
    @State private var prompt: String = ""
    @State private var isVideoVisible: Bool = true
    @State private var showAssetsModal: Bool = false
    @State private var selectedNewItem: PhotosPickerItem?
    @State private var selectedUndoTarget: UndoTarget? = nil
    
    public init(viewModel: EditorViewModel) {
        self.viewModel = viewModel
    }
    
    private var showError: Binding<Bool> {
        Binding(
            get: { viewModel.state.errorMessage != nil },
            set: { if !$0 { viewModel.state.errorMessage = nil } }
        )
    }
    
    public var body: some View {
        ZStack {
            VStack(spacing: 0) {
                // Video Player Area
                if isVideoVisible {
                    ZStack {
                        Color.ink.ignoresSafeArea()
                        if let _ = viewModel.state.project?.source {
                            if let player = viewModel.player {
                                VideoPlayer(player: player)
                            } else {
                                ProgressView("Loading Video...")
                                    .foregroundColor(.lavender)
                            }
                        }
                    }
                    .frame(height: UIScreen.main.bounds.height * 0.4)
                    .transition(.move(edge: .top).combined(with: .opacity))
                }
            
            // Conversation Area
            ZStack {
                Color.ink.ignoresSafeArea()
                
                ScrollView {
                    VStack(spacing: 16) {
                        ForEach(viewModel.state.messages) { msg in
                            let target = viewModel.undoTarget(for: msg)
                            ChatBubble(
                                message: msg.text,
                                isUser: msg.isUser,
                                onUndo: target != nil ? {
                                    selectedUndoTarget = target
                                } : nil
                            )
                        }
                        if let clarification = viewModel.state.pendingClarification {
                            VStack(alignment: .leading, spacing: 8) {
                                ForEach(clarification.options, id: \.id) { option in
                                    Button(action: {
                                        viewModel.state.pendingClarification = nil
                                        viewModel.reviseStory(request: option.label)
                                    }) {
                                        HStack {
                                            Text(option.label)
                                                .font(.headline)
                                            Spacer()
                                            if option.id == clarification.recommendedOptionId {
                                                Image(systemName: "star.fill").foregroundColor(.yellow)
                                            }
                                        }
                                        .padding()
                                        .background(Color.workspace)
                                        .cornerRadius(8)
                                        .foregroundColor(.cream)
                                    }
                                }
                            }
                            .padding(.top, 8)
                        }
                        
                        if viewModel.state.isProcessing && !viewModel.state.messages.contains(where: {
                            $0.text.hasPrefix("Thinking") || $0.text.hasPrefix("Editing")
                        }) {
                            HStack {
                                ProgressView()
                                    .padding()
                                    .tint(.lavender)
                                Spacer()
                            }
                        }
                    }
                    .padding()
                }
            }
            
            // Input Area
            HStack(spacing: 12) {
                PhotosPicker(selection: $selectedNewItem, matching: .videos) {
                    Image(systemName: "plus")
                        .font(.title2)
                        .foregroundColor(.lavender)
                }
                .onChange(of: selectedNewItem) { _, newValue in
                    if let newValue = newValue {
                        Task {
                            if let movie = try? await newValue.loadTransferable(type: MovieFile.self) {
                                await viewModel.importSources(urls: [movie.url])
                                // Transcription and preprocessing is triggered during importSources
                            }
                            selectedNewItem = nil // Reset
                        }
                    }
                }
                
                TextField("Ask Trimm to change something...", text: $prompt)
                    .textFieldStyle(PlainTextFieldStyle())
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(Color.workspace)
                    .foregroundColor(.creamRaised)
                    .cornerRadius(20)
                    .disabled(viewModel.state.isProcessing || (viewModel.state.pendingClarification != nil && viewModel.state.pendingClarification?.allowWriteIn != true))
                
                Button(action: {
                    viewModel.state.pendingClarification = nil
                    viewModel.reviseStory(request: prompt)
                    prompt = ""
                }) {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.title)
                        .foregroundColor(prompt.isEmpty ? .muted : .coral)
                }
                .disabled(prompt.isEmpty || viewModel.state.isProcessing || (viewModel.state.pendingClarification != nil && viewModel.state.pendingClarification?.allowWriteIn != true))
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
            .padding(.bottom, 8)
            .background(Color.ink)
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                HStack(spacing: 10) {
                    Button(action: {
                        BillingManager.shared.isStorePresented = true
                    }) {
                        HStack(spacing: 4) {
                            Image(systemName: "bolt.fill")
                                .font(.system(size: 10, weight: .bold))
                                .foregroundColor(.yellow)
                            Text("\(BillingManager.shared.balance)")
                                .font(.system(size: 12, weight: .bold))
                                .foregroundColor(.cream)
                        }
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(Color.raised)
                        .clipShape(Capsule())
                        .overlay(
                            Capsule().stroke(Color.border.opacity(0.6), lineWidth: 1)
                        )
                    }

                    // Three dots options menu right next to credits left
                    Menu {
                        Button(action: {
                            viewModel.export()
                        }) {
                            Label("Export Video", systemImage: "square.and.arrow.up")
                        }
                        .disabled(viewModel.state.project == nil || viewModel.state.isProcessing || viewModel.state.isExporting)

                        Button(action: {
                            showAssetsModal = true
                        }) {
                            Label("Project Assets", systemImage: "square.stack.3d.up")
                        }
                        
                        Button(action: {
                            withAnimation {
                                isVideoVisible.toggle()
                            }
                        }) {
                            Label(isVideoVisible ? "Hide Video Preview" : "Show Video Preview",
                                  systemImage: isVideoVisible ? "eye.slash" : "eye")
                        }
                    } label: {
                        Image(systemName: "ellipsis")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundColor(.lavender)
                            .frame(width: 28, height: 28)
                            .background(Color.raised)
                            .clipShape(Circle())
                            .overlay(
                                Circle().stroke(Color.border.opacity(0.6), lineWidth: 1)
                            )
                    }
                    .accessibilityLabel("More Options")
                }
            }
        }
        
        if viewModel.state.isExporting {
            Color.black.opacity(0.8)
                .ignoresSafeArea()
            
            VStack(spacing: 24) {
                Text("Exporting Video...")
                    .font(.headline)
                    .foregroundColor(.white)
                
                ProgressView(value: viewModel.state.exportProgress)
                    .progressViewStyle(LinearProgressViewStyle(tint: .coral))
                    .padding(.horizontal, 40)
                
                Text("\(Int(viewModel.state.exportProgress * 100))%")
                    .foregroundColor(.white)
                
                Button("Cancel") {
                    viewModel.cancelExport()
                }
                .padding()
                .background(Color.raised)
                .foregroundColor(.coral)
                .cornerRadius(8)
            }
            .padding(32)
            .background(Color.ink)
            .cornerRadius(16)
            .shadow(radius: 20)
        }
        }
        .sheet(isPresented: $showAssetsModal) {
            NavigationView {
                ZStack {
                    Color.ink.ignoresSafeArea()
                    
                    if viewModel.state.sources.isEmpty {
                        VStack(spacing: 12) {
                            Image(systemName: "video.slash")
                                .font(.system(size: 40))
                                .foregroundColor(.lavender.opacity(0.6))
                            Text("No Project Assets Found")
                                .font(.headline)
                                .foregroundColor(.cream)
                            Text("Imported video clips and audio tracks will appear here.")
                                .font(.subheadline)
                                .foregroundColor(.muted)
                                .multilineTextAlignment(.center)
                                .padding(.horizontal, 32)
                        }
                    } else {
                        List {
                            ForEach(Array(viewModel.state.sources.enumerated()), id: \.element.uri) { index, source in
                                AssetRowView(source: source, index: index)
                                    .listRowInsets(EdgeInsets(top: 6, leading: 16, bottom: 6, trailing: 16))
                                    .listRowBackground(Color.workspace)
                                    .listRowSeparator(.hidden)
                            }
                        }
                        .listStyle(.plain)
                        .scrollContentBackground(.hidden)
                    }
                }
                .navigationTitle("Project Assets")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .navigationBarTrailing) {
                        Button("Done") { showAssetsModal = false }
                            .foregroundColor(.coral)
                            .fontWeight(.semibold)
                    }
                }
            }
        }
        .alert(isPresented: showError) {
            Alert(
                title: Text("Error"),
                message: Text(viewModel.state.errorMessage ?? "An unknown error occurred."),
                dismissButton: .default(Text("OK"))
            )
        }
        .alert("Undo Edits", isPresented: Binding(
            get: { selectedUndoTarget != nil },
            set: { if !$0 { selectedUndoTarget = nil } }
        )) {
            Button("Undo", role: .destructive) {
                if let target = selectedUndoTarget {
                    viewModel.undoTurn(target: target)
                }
                selectedUndoTarget = nil
            }
            Button("Cancel", role: .cancel) {
                selectedUndoTarget = nil
            }
        } message: {
            if let target = selectedUndoTarget {
                Text("“\(target.userPrompt)”\n\nAre you sure you want to undo? All video edits and chat messages from this point forward will be removed.")
            }
        }
    }
}

struct ChatBubble: View {
    let message: String
    let isUser: Bool
    var onUndo: (() -> Void)? = nil
    
    private var isThinkingOrEditing: Bool {
        let trimmed = message.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.hasPrefix("Thinking") || trimmed.hasPrefix("Editing")
    }
    
    var body: some View {
        HStack {
            if isUser { Spacer() }
            
            if isUser {
                HStack(spacing: 8) {
                    if let onUndo = onUndo {
                        Button(action: onUndo) {
                            Image(systemName: "arrow.uturn.backward")
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundColor(.lavender.opacity(0.85))
                                .padding(7)
                                .background(Color.workspace)
                                .clipShape(Circle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Undo this edit")
                    }
                    
                    Text(message)
                        .font(.system(size: 15))
                        .padding(12)
                        .background(Color.raised)
                        .foregroundColor(.cream)
                        .cornerRadius(16)
                        .frame(maxWidth: 280, alignment: .trailing)
                        .contextMenu {
                            if let onUndo = onUndo {
                                Button(role: .destructive, action: onUndo) {
                                    Label("Undo to this edit", systemImage: "arrow.uturn.backward")
                                }
                            }
                            Button {
                                UIPasteboard.general.string = message
                            } label: {
                                Label("Copy", systemImage: "doc.on.doc")
                            }
                        }
                }
            } else if isThinkingOrEditing {
                ThinkingShimmerView(text: message)
                    .padding(.leading, 4)
            } else {
                AnimatedResponseText(text: message)
                    .contextMenu {
                        if let onUndo = onUndo {
                            Button(role: .destructive, action: onUndo) {
                                Label("Undo this edit", systemImage: "arrow.uturn.backward")
                            }
                        }
                        Button {
                            UIPasteboard.general.string = message
                        } label: {
                            Label("Copy", systemImage: "doc.on.doc")
                        }
                    }
            }
            
            if !isUser { Spacer() }
        }
    }
}

struct AssetRowView: View {
    let source: SourceMedia
    let index: Int
    @State private var thumbnail: UIImage? = nil
    
    var body: some View {
        HStack(spacing: 14) {
            // Thumbnail / preview image on the left
            ZStack {
                Color.raised
                
                if let image = thumbnail {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                } else {
                    Image(systemName: "film")
                        .font(.system(size: 22))
                        .foregroundColor(.lavender.opacity(0.6))
                }
            }
            .frame(width: 72, height: 72)
            .clipped()
            .clipShape(RoundedRectangle(cornerRadius: 10))
            .overlay(
                RoundedRectangle(cornerRadius: 10)
                    .stroke(Color.border.opacity(0.4), lineWidth: 1)
            )
            
            // Metadata on the right
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text("Clip \(index + 1)")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(.cream)
                    
                    Spacer()
                    
                    Text(formatDuration(source.durationMs))
                        .font(.system(size: 12, weight: .bold))
                        .foregroundColor(.coral)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(Color.coral.opacity(0.15))
                        .clipShape(Capsule())
                }
                
                HStack(spacing: 12) {
                    if source.width > 0 && source.height > 0 {
                        Label("\(source.width)×\(source.height)", systemImage: "aspectratio")
                            .font(.system(size: 12))
                            .foregroundColor(.muted)
                    }
                    
                    Label(source.hasAudio ? "Audio" : "Muted", systemImage: source.hasAudio ? "speaker.wave.2" : "speaker.slash")
                        .font(.system(size: 12))
                        .foregroundColor(source.hasAudio ? .muted : .gray)
                }
            }
        }
        .padding(.vertical, 4)
        .task {
            if thumbnail == nil {
                thumbnail = await generateThumbnail(for: source)
            }
        }
    }
    
    private func formatDuration(_ ms: Int64) -> String {
        let totalSeconds = max(1, Int(ms / 1000))
        let minutes = totalSeconds / 60
        let seconds = totalSeconds % 60
        if minutes > 0 {
            return String(format: "%d:%02d", minutes, seconds)
        } else {
            return "\(seconds)s"
        }
    }
    
    private func generateThumbnail(for source: SourceMedia) async -> UIImage? {
        guard let url = source.resolvedUrl else { return nil }
        let asset = AVURLAsset(url: url)
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: 240, height: 240)
        let targetSec = min(1.0, max(0.1, Double(source.durationMs) / 2000.0))
        let time = CMTime(seconds: targetSec, preferredTimescale: 600)
        
        return await Task.detached(priority: .userInitiated) {
            do {
                if #available(iOS 16.0, *) {
                    let (cgImage, _) = try await generator.image(at: time)
                    return UIImage(cgImage: cgImage)
                } else {
                    let cgImage = try generator.copyCGImage(at: time, actualTime: nil)
                    return UIImage(cgImage: cgImage)
                }
            } catch {
                return nil
            }
        }.value
    }
}
