import SwiftUI

public struct AnimatedResponseText: View {
    let text: String
    
    @State private var opacity: Double = 0.0
    
    public init(text: String) {
        self.text = text
    }
    
    public var body: some View {
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
                // If text changes, restart animation
                startAnimation()
            }
    }
    
    private func startAnimation() {
        opacity = 0.0
        
        withAnimation(.easeIn(duration: 0.5)) {
            opacity = 1.0
        }
    }
}
