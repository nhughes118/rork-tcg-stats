import SwiftUI

/// Manual lookup of any card seen in recent tournament decklists.
struct CardSearchView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var query: String = ""

    private var results: [PopularCard] {
        let all = model.popularCards
        guard !query.isEmpty else { return Array(all.prefix(60)) }
        return all.filter { $0.card.name.localizedStandardContains(query) }
    }

    var body: some View {
        NavigationStack {
            List(results) { item in
                Button {
                    model.showResults(for: item.card.key)
                    dismiss()
                } label: {
                    HStack(spacing: 12) {
                        CardImageView(card: item.card, cornerRadius: 4)
                            .frame(width: 40)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.card.name)
                                .font(.body.weight(.semibold))
                                .foregroundStyle(Theme.ink)
                            Text(item.card.printLabel)
                                .font(.caption)
                                .foregroundStyle(Theme.muted)
                        }
                        Spacer()
                        Text(CardText.percent(item.usage))
                            .font(.subheadline.weight(.bold))
                            .monospacedDigit()
                            .foregroundStyle(Theme.ink)
                    }
                }
            }
            .overlay {
                if model.popularCards.isEmpty {
                    if case .failed(let message) = model.loadState {
                        ContentUnavailableView("Couldn't load data", systemImage: "wifi.exclamationmark", description: Text(message))
                    } else {
                        LoadingCard()
                    }
                } else if results.isEmpty {
                    ContentUnavailableView.search(text: query)
                }
            }
            .navigationTitle(query.isEmpty ? "Most Played Cards" : "Search")
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Card name")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
        }
    }
}
