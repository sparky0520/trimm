import SwiftUI
import AVKit

public struct EditorScreen: View {
    @Bindable var viewModel: EditorViewModel
    @State private var prompt: String = ""
    
    public init(viewModel: EditorViewModel) {
        self.viewModel = viewModel
    }
    
    public var body: some View {
        VStack(spacing: 0) {
            // Video Player Area
            ZStack {
                Color.black.ignoresSafeArea()
                if let _ = viewModel.state.project?.source {
                    // Placeholder for Video Player
                    Text("Video Player Placeholder")
                        .foregroundColor(.white)
                }
            }
            .frame(height: UIScreen.main.bounds.height * 0.4)
            
            // Conversation Area
            ScrollView {
                VStack(spacing: 16) {
                    if let initial = viewModel.state.initialRequest {
                        ChatBubble(message: initial, isUser: true)
                    }
                    
                    if let project = viewModel.state.project {
                        // In a full implementation, iterate over project.analysis.revisionConversation
                        // and project.analysis.appliedEditCheckpoints
                        let analysis = project.analysis
                        if let _ = analysis.completedAtEpochMs {
                             ChatBubble(message: "Analysis complete. Proposal ready.", isUser: false)
                        }
                    }
                    
                    if let job = viewModel.state.jobMessage {
                        ChatBubble(message: job, isUser: false)
                    }
                    
                    if viewModel.state.isProcessing {
                        HStack {
                            ProgressView()
                                .padding()
                            Spacer()
                        }
                    }
                }
                .padding()
            }
            
            // Input Area
            HStack {
                TextField("Ask Trimm to change something...", text: $prompt)
                    .textFieldStyle(RoundedBorderTextFieldStyle())
                    .disabled(viewModel.state.isProcessing)
                
                Button(action: {
                    viewModel.reviseStory(request: prompt)
                    prompt = ""
                }) {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.title)
                        .foregroundColor(prompt.isEmpty ? .gray : .blue)
                }
                .disabled(prompt.isEmpty || viewModel.state.isProcessing)
            }
            .padding()
            .background(Color(UIColor.systemBackground))
        }
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct ChatBubble: View {
    let message: String
    let isUser: Bool
    
    var body: some View {
        HStack {
            if isUser { Spacer() }
            
            Text(message)
                .padding(12)
                .background(isUser ? Color.blue : Color(UIColor.secondarySystemBackground))
                .foregroundColor(isUser ? .white : .primary)
                .cornerRadius(16)
                .frame(maxWidth: 280, alignment: isUser ? .trailing : .leading)
            
            if !isUser { Spacer() }
        }
    }
}
