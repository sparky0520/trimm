import SwiftUI

public struct ThinkingShimmerView: View {
    public let text: String
    
    @State private var phase: CGFloat = -1.0
    
    public init(text: String) {
        self.text = text
    }
    
    private var iconName: String {
        if text.localizedCaseInsensitiveContains("edit") {
            return "wand.and.stars"
        } else {
            return "sparkles"
        }
    }
    
    public var body: some View {
        HStack(spacing: 8) {
            Image(systemName: iconName)
                .font(.system(size: 14, weight: .semibold))
            
            Text(text)
                .font(.system(size: 15, weight: .medium))
        }
        .foregroundColor(Color.cream.opacity(0.42))
        .overlay(
            GeometryReader { geo in
                let width = max(geo.size.width, 120)
                LinearGradient(
                    stops: [
                        .init(color: .clear, location: 0.0),
                        .init(color: Color.white.opacity(0.92), location: 0.48),
                        .init(color: Color.white.opacity(0.92), location: 0.52),
                        .init(color: .clear, location: 1.0)
                    ],
                    startPoint: .leading,
                    endPoint: .trailing
                )
                .frame(width: width * 0.75)
                .offset(x: phase * width * 1.5)
                .blendMode(.screen)
            }
        )
        .mask(
            HStack(spacing: 8) {
                Image(systemName: iconName)
                    .font(.system(size: 14, weight: .semibold))
                
                Text(text)
                    .font(.system(size: 15, weight: .medium))
            }
        )
        .padding(.vertical, 8)
        .padding(.horizontal, 4)
        .onAppear {
            withAnimation(
                .linear(duration: 1.5)
                .repeatForever(autoreverses: false)
            ) {
                phase = 1.0
            }
        }
    }
}

public struct AnimatedResponseText: View {
    let text: String
    
    @State private var opacity: Double = 0.0
    
    public init(text: String) {
        self.text = text
    }
    
    private var isThinkingOrEditing: Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.hasPrefix("Thinking") || trimmed.hasPrefix("Editing")
    }
    
    public var body: some View {
        if isThinkingOrEditing {
            ThinkingShimmerView(text: text)
        } else {
            Text(text)
                .font(.system(size: 15))
                .padding(12)
                .background(Color.workspace)
                .foregroundColor(.cream)
                .cornerRadius(16)
                .frame(maxWidth: 280, alignment: .leading)
                .opacity(opacity)
                .onAppear {
                    startAnimation()
                }
                .onChange(of: text) { _, _ in
                    startAnimation()
                }
        }
    }
    
    private func startAnimation() {
        opacity = 0.0
        
        withAnimation(.easeIn(duration: 0.5)) {
            opacity = 1.0
        }
    }
}

