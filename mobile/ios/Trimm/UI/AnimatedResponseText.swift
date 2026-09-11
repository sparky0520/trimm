import SwiftUI

public struct AnimatedResponseText: View {
    let text: String
    let typewriterSpeed: TimeInterval = 0.02
    
    @State private var displayedText: String = ""
    @State private var opacity: Double = 0.0
    @State private var timer: Timer? = nil
    
    public init(text: String) {
        self.text = text
    }
    
    public var body: some View {
        Text(displayedText)
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
            .onChange(of: text) { _, newText in
                // If text changes, restart animation
                startAnimation()
            }
            .onDisappear {
                timer?.invalidate()
            }
    }
    
    private func startAnimation() {
        timer?.invalidate()
        displayedText = ""
        
        // Fade in quickly
        withAnimation(.easeIn(duration: 0.3)) {
            opacity = 1.0
        }
        
        // Typewriter effect
        let characters = Array(text)
        var currentIndex = 0
        
        timer = Timer.scheduledTimer(withTimeInterval: typewriterSpeed, repeats: true) { t in
            if currentIndex < characters.count {
                displayedText.append(characters[currentIndex])
                currentIndex += 1
            } else {
                t.invalidate()
            }
        }
    }
}
