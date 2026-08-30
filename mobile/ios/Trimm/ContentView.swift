import SwiftUI
import SwiftData
import Supabase

struct ContentView: View {
    var body: some View {
        VStack {
            Image(systemName: "film")
                .imageScale(.large)
                .foregroundStyle(.tint)
            Text("trimm iOS Client")
        }
        .padding()
    }
}

#Preview {
    ContentView()
}
