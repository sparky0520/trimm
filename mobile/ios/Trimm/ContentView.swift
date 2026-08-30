import SwiftUI
import SwiftData
import Supabase
import PhotosUI
import CoreTransferable
import ClerkKit
import ClerkKitUI

struct MovieFile: Transferable {
    let url: URL
    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .movie) { movie in
            SentTransferredFile(movie.url)
        } importing: { received in
            let copy = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".mp4")
            try FileManager.default.copyItem(at: received.file, to: copy)
            return Self.init(url: copy)
        }
    }
}

struct ContentView: View {
    @State private var viewModel = EditorViewModel()
    @State private var selectedItem: PhotosPickerItem?
    
    private var clerk = Clerk.shared
    
    var body: some View {
        Group {
            if clerk.user == nil {
                AuthView()
            } else {
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
                        
                        PhotosPicker(selection: $selectedItem, matching: .videos) {
                            Text("Select clips to edit")
                                .font(.system(size: 16, weight: .semibold))
                                .foregroundColor(.darkText)
                                .padding()
                                .frame(maxWidth: .infinity)
                                .background(Color.coral)
                                .cornerRadius(10)
                                .padding(.horizontal, 40)
                        }
                        .onChange(of: selectedItem) { _, newValue in
                            if let newValue = newValue {
                                Task {
                                    if let movie = try? await newValue.loadTransferable(type: MovieFile.self) {
                                        await viewModel.importSources(urls: [movie.url])
                                        await MainActor.run {
                                            viewModel.initializeProject()
                                        }
                                    }
                                }
                            }
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
            } // ZStack
        } // NavigationStack
            } // else
        } // Group
    } // body
} // ContentView

#Preview {
    ContentView()
}
