import SwiftUI
import AVKit

struct VideoPlayerView: View {
    @State private var player: AVPlayer?
    let jsonDSL: String
    
    var body: some View {
        VStack {
            if let player = player {
                VideoPlayer(player: player)
                    .aspectRatio(9/16, contentMode: .fit)
            } else {
                ProgressView("Compiling Video...")
            }
        }
        .task {
            await compileVideo()
        }
    }
    
    func compileVideo() async {
        do {
            guard let data = jsonDSL.data(using: .utf8) else {
                print("Failed to encode DSL to data")
                return
            }
            let dsl = try JSONDecoder().decode(VideoDSL.self, from: data)
            
            let builder = AVCompositionBuilder()
            let playerItem = try await builder.build(from: dsl)
            
            self.player = AVPlayer(playerItem: playerItem)
            self.player?.play()
        } catch {
            print("Failed to compile DSL: \(error)")
        }
    }
}
