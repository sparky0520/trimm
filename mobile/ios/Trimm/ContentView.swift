import SwiftUI
import SwiftData
import Supabase

struct ContentView: View {
    @State private var viewModel = EditorViewModel()
    
    var body: some View {
        NavigationStack {
            ZStack {
                Color.ink.ignoresSafeArea()
                
                if viewModel.state.sources.isEmpty {
                    VStack(spacing: 24) {
                        Spacer()
                        
                        TrimmLogo()
                            .frame(width: 108, height: 108)
                        Text("START A NEW EDIT")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundColor(.muted)
                            .tracking(1.4)
                        
                        Text("Turn a rough recording\ninto something worth sharing.")
                            .font(.system(size: 27))
                            .foregroundColor(.creamRaised)
                            .multilineTextAlignment(.center)
                            .lineSpacing(4)
                        
                        Text("Bring in your talking-head and product clips, then shape them naturally with AI.")
                            .font(.system(size: 15))
                            .foregroundColor(.muted)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 40)
                        
                        Button(action: {
                            // Mock importing a file
                            viewModel.importSources(urls: [URL(string: "file:///mock/video.mp4")!])
                            viewModel.generateDraft(prompt: "Make a quick recap")
                        }) {
                            Text("Select clips to edit")
                                .font(.system(size: 16, weight: .semibold))
                                .foregroundColor(.darkText)
                                .padding()
                                .frame(maxWidth: .infinity)
                                .background(Color.coral)
                                .cornerRadius(10)
                                .padding(.horizontal, 40)
                        }
                        .padding(.top, 16)
                        
                        Spacer()
                        
                        Text("YOUR VIDEO STAYS ON DEVICE")
                            .font(.system(size: 9, weight: .regular))
                            .foregroundColor(.muted)
                            .tracking(0.8)
                            .padding(.bottom, 32)
                    }
                } else {
                    EditorScreen(viewModel: viewModel)
                }
            }
        }
    }
}

#Preview {
    ContentView()
}
