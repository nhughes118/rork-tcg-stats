import SwiftUI

struct HistoryView: View {
    @Environment(AppModel.self) private var model
    @State private var query: String = ""

    private var filtered: [HistoryEntry] {
        guard !query.isEmpty else { return model.history }
        return model.history.filter { $0.card.name.localizedStandardContains(query) }
    }

    var body: some View {
        @Bindable var model = model
        NavigationStack(path: $model.historyPath) {
            List {
                Section {
                    ForEach(filtered) { entry in
                        NavigationLink(value: AppRoute.card(entry.card.key)) {
                            HistoryRow(entry: entry)
                        }
                        .listRowBackground(Theme.surface)
                    }
                    .onDelete { offsets in
                        let keys = offsets.map { filtered[$0].card.key }
                        withAnimation { model.deleteHistory(at: keys) }
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background { CanvasBackground() }
            .overlay {
                if model.history.isEmpty {
                    ContentUnavailableView {
                        Label("No scans yet", systemImage: "camera.viewfinder")
                    } description: {
                        Text("Cards you scan or look up will show up here.")
                    } actions: {
                        Button("Scan a Card") { model.selectedTab = .scan }
                            .buttonStyle(.borderedProminent)
                            .tint(Theme.blue)
                    }
                } else if filtered.isEmpty {
                    ContentUnavailableView.search(text: query)
                }
            }
            .navigationTitle("History")
            .searchable(text: $query, prompt: "Search your scans")
            .toolbar {
                if !model.history.isEmpty {
                    ToolbarItem(placement: .topBarTrailing) {
                        Menu {
                            Button("Clear History", systemImage: "trash", role: .destructive) {
                                withAnimation { model.clearHistory() }
                            }
                        } label: {
                            Image(systemName: "ellipsis.circle")
                        }
                    }
                }
            }
            .appRoutes()
        }
    }
}

private struct HistoryRow: View {
    let entry: HistoryEntry

    var body: some View {
        HStack(spacing: 14) {
            CardImageView(card: entry.card, cornerRadius: 5)
                .frame(width: 54)
                .shadow(color: .black.opacity(0.12), radius: 3, y: 2)
            VStack(alignment: .leading, spacing: 3) {
                Text(entry.card.name)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(Theme.ink)
                Text("Scanned \(entry.scannedAt.formatted(.relative(presentation: .named)))")
                    .font(.subheadline)
                    .foregroundStyle(Theme.muted)
            }
            Spacer(minLength: 8)
            Text(CardText.percent(entry.usage))
                .font(.body.weight(.bold))
                .monospacedDigit()
                .foregroundStyle(Theme.ink)
        }
        .padding(.vertical, 4)
    }
}
