import SwiftUI

@main
struct TCGStatsApp: App {
    @State private var model = AppModel()

    init() {
        URLCache.shared = URLCache(memoryCapacity: 50 * 1024 * 1024, diskCapacity: 300 * 1024 * 1024)
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(model)
                .preferredColorScheme(.light)
        }
    }
}
