import SwiftUI
import SwiftData
import ClerkKit
import ClerkKitUI

@main
struct TrimmApp: App {
    init() {
        Clerk.configure(publishableKey: "pk_test_b3JpZW50ZWQtcm9vc3Rlci01My5jbGVyay5hY2NvdW50cy5kZXYk")
    }
    
    var sharedModelContainer: ModelContainer = {
        let schema = Schema([
            // Add models here
        ])
        let modelConfiguration = ModelConfiguration(schema: schema, isStoredInMemoryOnly: false)

        do {
            return try ModelContainer(for: schema, configurations: [modelConfiguration])
        } catch {
            fatalError("Could not create ModelContainer: \(error)")
        }
    }()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(Clerk.shared)
        }
        .modelContainer(sharedModelContainer)
    }
}
