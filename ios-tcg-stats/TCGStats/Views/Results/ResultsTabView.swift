import SwiftUI

struct ResultsTabView: View {
    @Environment(AppModel.self) private var model
    @State private var isSearching: Bool = false

    var body: some View {
        @Bindable var model = model
        NavigationStack(path: $model.resultsPath) {
            Group {
                if let key = model.currentCardKey {
                    CardResultsView(cardKey: key, isTabRoot: true)
                        .id(key)
                } else {
                    EmptyResultsView(isSearching: $isSearching)
                }
            }
            .appRoutes()
        }
        .sheet(isPresented: $isSearching) { CardSearchView() }
    }
}

private struct EmptyResultsView: View {
    @Environment(AppModel.self) private var model
    @Binding var isSearching: Bool

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                BrandHeader { EmptyView() }
                    .padding(.top, 4)
                SourcePicker()

                VStack(alignment: .leading, spacing: 12) {
                    Text("Scan a card to see how it's performing")
                        .font(.system(.title, weight: .bold))
                        .foregroundStyle(Theme.ink)
                    Text("Usage, trends and top finishes from recent tournaments on Limitless TCG.")
                        .font(.body)
                        .foregroundStyle(Theme.muted)
                    HStack(spacing: 10) {
                        Button {
                            model.selectedTab = .scan
                        } label: {
                            Label("Scan Card", systemImage: "camera.viewfinder")
                                .font(.headline)
                                .frame(maxWidth: .infinity, minHeight: 50)
                                .foregroundStyle(.white)
                                .background(Theme.blue, in: .capsule)
                        }
                        .buttonStyle(PressableStyle())
                        Button {
                            isSearching = true
                        } label: {
                            Label("Search", systemImage: "magnifyingglass")
                                .font(.headline)
                                .frame(maxWidth: .infinity, minHeight: 50)
                                .foregroundStyle(Theme.blue)
                                .background(Theme.blue.opacity(0.1), in: .capsule)
                        }
                        .buttonStyle(PressableStyle())
                    }
                }
                .padding(20)
                .cardSurface(radius: 24)

                if model.popularCards.isEmpty {
                    if case .failed(let message) = model.loadState {
                        VStack(spacing: 10) {
                            Text(message).font(.subheadline).foregroundStyle(Theme.muted)
                            Button("Try Again") { Task { await model.load(force: true) } }
                                .buttonStyle(.borderedProminent).tint(Theme.blue)
                        }
                        .frame(maxWidth: .infinity)
                    } else {
                        LoadingCard().frame(maxWidth: .infinity)
                    }
                } else {
                    Text("Most played right now")
                        .font(.title3.weight(.bold))
                        .foregroundStyle(Theme.ink)
                    ScrollView(.horizontal) {
                        LazyHStack(spacing: 14) {
                            ForEach(model.popularCards.filter { $0.card.category != .energy }.prefix(12)) { item in
                                Button {
                                    model.showResults(for: item.card.key)
                                } label: {
                                    VStack(alignment: .leading, spacing: 6) {
                                        CardImageView(card: item.card, cornerRadius: 8)
                                            .frame(width: 110)
                                            .shadow(color: .black.opacity(0.15), radius: 6, y: 4)
                                        Text(item.card.name)
                                            .font(.subheadline.weight(.semibold))
                                            .foregroundStyle(Theme.ink)
                                            .lineLimit(1)
                                        Text(CardText.percent(item.usage))
                                            .font(.caption.weight(.bold))
                                            .monospacedDigit()
                                            .foregroundStyle(Theme.red)
                                    }
                                    .frame(width: 110)
                                }
                                .buttonStyle(PressableStyle())
                            }
                        }
                        .padding(.vertical, 8)
                    }
                    .scrollIndicators(.hidden)
                    .contentMargins(.horizontal, 16)
                    .padding(.horizontal, -16)
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
        .background { CanvasBackground() }
        .toolbar(.hidden, for: .navigationBar)
    }
}

extension View {
    /// Registers every shared navigation destination.
    func appRoutes() -> some View {
        navigationDestination(for: AppRoute.self) { route in
            switch route {
            case .card(let key):
                CardResultsView(cardKey: key)
                    .toolbar(.hidden, for: .tabBar)
            case .tournament(let id, let cardKey):
                TournamentDetailView(tournamentID: id, cardKey: cardKey)
            case .allTournaments(let cardKey, let window):
                AllTournamentsView(cardKey: cardKey, window: window)
            case .allDecks(let cardKey, let window):
                AllDecksView(cardKey: cardKey, window: window)
            case .deck(let cardKey, let deckName, let window):
                DeckEntriesView(cardKey: cardKey, deckName: deckName, window: window)
            }
        }
    }
}
