import SwiftUI

struct ContentView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        TabView(selection: $model.selectedTab) {
            Tab("Scan", systemImage: "camera", value: AppTab.scan) {
                ScanView()
            }
            Tab("Results", systemImage: "chart.bar.fill", value: AppTab.results) {
                ResultsTabView()
            }
            Tab("Events", systemImage: "calendar", value: AppTab.events) {
                EventsView()
            }
            Tab("Players", systemImage: "trophy", value: AppTab.players) {
                PlayersView()
            }
            Tab("History", systemImage: "clock", value: AppTab.history) {
                HistoryView()
            }
        }
        .tint(Theme.blue)
        .task { await model.load() }
    }
}

#Preview {
    ContentView()
        .environment(AppModel())
}
