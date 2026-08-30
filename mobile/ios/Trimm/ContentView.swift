import SwiftUI
import SwiftData
import Supabase

struct ContentView: View {
    @State private var viewModel = EditorViewModel()
    
    var body: some View {
        NavigationStack {
            if viewModel.state.sources.isEmpty {
                VStack(spacing: 20) {
                    Image(systemName: "film")
                        .font(.system(size: 64))
                        .foregroundColor(.blue)
                    
                    Text("Welcome to Trimm")
                        .font(.largeTitle)
                        .fontWeight(.bold)
                    
                    Button(action: {
                        // Mock importing a file
                        viewModel.importSources(urls: [URL(string: "file:///mock/video.mp4")!])
                        viewModel.generateDraft(prompt: "Make a quick recap")
                    }) {
                        Text("Import Video")
                            .font(.headline)
                            .foregroundColor(.white)
                            .padding()
                            .frame(maxWidth: .infinity)
                            .background(Color.blue)
                            .cornerRadius(12)
                            .padding(.horizontal, 40)
                    }
                }
            } else {
                EditorScreen(viewModel: viewModel)
            }
        }
    }
}

#Preview {
    ContentView()
}
