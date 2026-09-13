import SwiftUI
import AVKit

public struct EditorScreen: View {
    @Bindable var viewModel: EditorViewModel
    @State private var prompt: String = ""
    
    public init(viewModel: EditorViewModel) {
        self.viewModel = viewModel
    }
    
    public var body: some View {
        ZStack {
            VStack(spacing: 0) {
                // Video Player Area
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
            
            // Conversation Area
            ZStack {
                Color.ink.ignoresSafeArea()
                
                ScrollView {
                    VStack(spacing: 16) {
                        ForEach(viewModel.state.messages) { msg in
                            ChatBubble(message: msg.text, isUser: msg.isUser)
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
            HStack {
                TextField("Ask Trimm to change something...", text: $prompt)
                    .textFieldStyle(PlainTextFieldStyle())
                    .padding(12)
                    .background(Color.workspace)
                    .foregroundColor(.creamRaised)
                    .cornerRadius(8)
                    .disabled(viewModel.state.isProcessing)
                
                Button(action: {
                    viewModel.reviseStory(request: prompt)
                    prompt = ""
                }) {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.title)
                        .foregroundColor(prompt.isEmpty ? .muted : .coral)
                }
                .disabled(prompt.isEmpty || viewModel.state.isProcessing)
            }
            .padding()
            .background(Color.ink)
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button("Export") {
                    viewModel.export()
                }
                .disabled(viewModel.state.project == nil || viewModel.state.isProcessing || viewModel.state.isExporting)
                .foregroundColor(.coral)
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
