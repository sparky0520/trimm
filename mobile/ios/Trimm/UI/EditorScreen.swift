import SwiftUI
import AVKit
import PhotosUI

public struct EditorScreen: View {
    @Bindable var viewModel: EditorViewModel
    @State private var prompt: String = ""
    @State private var isVideoVisible: Bool = true
    @State private var showAssetsModal: Bool = false
    @State private var selectedNewItem: PhotosPickerItem?
    
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
                            ChatBubble(message: msg.text, isUser: msg.isUser)
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
                        
                        if viewModel.state.isProcessing {
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
                HStack(spacing: 16) {
                    Button(action: {
                        showAssetsModal = true
                    }) {
                        Image(systemName: "square.stack.3d.up")
                    }
                    .foregroundColor(.lavender)
                    
                    Button(action: {
                        withAnimation {
                            isVideoVisible.toggle()
                        }
                    }) {
                        Image(systemName: isVideoVisible ? "eye" : "eye.slash")
                    }
                    .foregroundColor(.lavender)
                    
                    Button("Export") {
                        viewModel.export()
                    }
                    .disabled(viewModel.state.project == nil || viewModel.state.isProcessing || viewModel.state.isExporting)
                    .foregroundColor(.coral)
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
                List(viewModel.state.sources, id: \.uri) { source in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(source.uri).font(.headline)
                        Text("Duration: \(source.durationMs)ms").font(.subheadline).foregroundColor(.gray)
                        Text("Has Audio: \(source.hasAudio ? "Yes" : "No")").font(.caption).foregroundColor(.gray)
                    }
                    .padding(.vertical, 4)
                }
                .navigationTitle("Project Assets")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .navigationBarTrailing) {
                        Button("Done") { showAssetsModal = false }
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
    }
}

struct ChatBubble: View {
    let message: String
    let isUser: Bool
    
    var body: some View {
        HStack {
            if isUser { Spacer() }
            
            if isUser {
                Text(message)
                    .font(.system(size: 15))
                    .padding(12)
                    .background(Color.raised)
                    .foregroundColor(.cream)
                    .cornerRadius(16)
                    .frame(maxWidth: 280, alignment: .trailing)
            } else {
                AnimatedResponseText(text: message)
            }
            
            if !isUser { Spacer() }
        }
    }
}
