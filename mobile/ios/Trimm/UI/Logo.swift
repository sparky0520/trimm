import SwiftUI

public struct TrimmLogo: View {
    public init() {}
    
    public var body: some View {
        GeometryReader { geometry in
            let scaleX = geometry.size.width / 108.0
            let scaleY = geometry.size.height / 108.0
            let scale = min(scaleX, scaleY)
            
            ZStack(alignment: .topLeading) {
                // Main "T" shape (#FAFAFA)
                Path { path in
                    path.move(to: CGPoint(x: 28, y: 31))
                    path.addLine(to: CGPoint(x: 28 + 52, y: 31))
                    path.addLine(to: CGPoint(x: 80, y: 31 + 12))
                    path.addLine(to: CGPoint(x: 80 - 20, y: 43))
                    path.addLine(to: CGPoint(x: 60, y: 43 + 34))
                    path.addLine(to: CGPoint(x: 60 - 12, y: 77))
                    path.addLine(to: CGPoint(x: 48, y: 77 - 34))
                    path.addLine(to: CGPoint(x: 48 - 20, y: 43))
                    path.addLine(to: CGPoint(x: 28, y: 43 - 12))
                    path.closeSubpath()
                }
                .fill(Color.cream)
                .scaleEffect(scale, anchor: .topLeading)
                
                // Small square (#A1A1AA)
                Path { path in
                    path.move(to: CGPoint(x: 68, y: 65))
                    path.addLine(to: CGPoint(x: 68 + 12, y: 65))
                    path.addLine(to: CGPoint(x: 80, y: 65 + 12))
                    path.addLine(to: CGPoint(x: 80 - 12, y: 77))
                    path.addLine(to: CGPoint(x: 68, y: 77 - 12))
                    path.closeSubpath()
                }
                .fill(Color.muted)
                .scaleEffect(scale, anchor: .topLeading)
            }
        }
        .aspectRatio(1, contentMode: .fit)
    }
}
