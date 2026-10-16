import SwiftUI
import UIKit

enum AppTab: Hashable {
    case scan, results, history
}

/// Navigation destinations shared by the Results and History stacks.
enum AppRoute: Hashable {
    case card(String)
    case tournament(id: String, cardKey: String)
    case allTournaments(cardKey: String, window: StatsWindow)
    case allDecks(cardKey: String, window: StatsWindow)
    case deck(cardKey: String, deckName: String, window: StatsWindow)
}

/// Popular card shown in search and empty states.
struct PopularCard: Identifiable, Hashable {
    let card: CardIdentity
    let usage: Double
    var id: String { card.key }
}

@Observable
final class AppModel {
    enum LoadState: Equatable {
        case idle
        case loading(Double)
        case loaded
        case failed(String)
    }

    private static let sourceKey = "tournament_source"

    /// Selected tournament source; persisted between launches.
    private(set) var source: DataSource
    private var datasets: [DataSource: CardDataset] = [:]
    private var matchers: [DataSource: CardMatcher] = [:]
    private var popularBySource: [DataSource: [PopularCard]] = [:]
    private var states: [DataSource: LoadState] = [:]

    var selectedTab: AppTab = .results
    var currentCardKey: String?
    var resultsPath: [AppRoute] = []
    var historyPath: [AppRoute] = []
    var history: [HistoryEntry] = []

    @ObservationIgnored private var statsCache: [String: CardStats] = [:]

    init() {
        source = DataSource(rawValue: UserDefaults.standard.string(forKey: Self.sourceKey) ?? "") ?? .online
        history = HistoryStore.load()
        currentCardKey = history.first?.card.key
        if history.isEmpty { selectedTab = .scan }
    }

    // Current-source accessors.
    var dataset: CardDataset? { datasets[source] }
    var matcher: CardMatcher? { matchers[source] }
    var popularCards: [PopularCard] { popularBySource[source] ?? [] }
    var loadState: LoadState { states[source] ?? .idle }
    var isLoaded: Bool { dataset != nil }

    /// Switches the stats source and starts loading it if needed.
    func selectSource(_ newSource: DataSource) {
        guard newSource != source else { return }
        source = newSource
        UserDefaults.standard.set(newSource.rawValue, forKey: Self.sourceKey)
        statsCache = [:]
        Task { await load() }
    }

    func load(force: Bool = false) async {
        let src = source
        if case .loading = states[src] { return }
        if datasets[src] != nil && !force {
            states[src] = .loaded
            return
        }
        states[src] = .loading(0)
        do {
            let result = try await LimitlessClient.loadDataset(source: src) { value in
                Task { @MainActor [weak self] in
                    guard let self, case .loading(let current) = self.states[src], value > current else { return }
                    self.states[src] = .loading(value)
                }
            }
            datasets[src] = result
            matchers[src] = CardMatcher(dataset: result)
            for key in statsCache.keys where key.hasPrefix("\(src.rawValue)|") {
                statsCache.removeValue(forKey: key)
            }
            popularBySource[src] = result.recentUsage
                .sorted { $0.value > $1.value }
                .compactMap { key, usage in result.cards[key].map { PopularCard(card: $0, usage: usage) } }
            refreshHistoryUsage()
            states[src] = .loaded
        } catch {
            if datasets[src] == nil {
                states[src] = .failed(error.localizedDescription)
            } else {
                states[src] = .loaded
            }
        }
    }

    func stats(for key: String, window: StatsWindow) -> CardStats? {
        let cacheKey = "\(source.rawValue)|\(key)|\(window.rawValue)"
        if let cached = statsCache[cacheKey] { return cached }
        guard let stats = dataset?.stats(for: key, window: window) else { return nil }
        statsCache[cacheKey] = stats
        return stats
    }

    func identity(for key: String) -> CardIdentity? {
        dataset?.cards[key] ?? history.first { $0.card.key == key }?.card
    }

    /// Opens results for a card on the Results tab and records it in history.
    func showResults(for key: String) {
        guard let card = dataset?.cards[key] else { return }
        currentCardKey = key
        resultsPath = []
        selectedTab = .results
        let usage = dataset?.recentUsage[key] ?? 0
        history.removeAll { $0.card.key == key }
        history.insert(HistoryEntry(card: card, usage: usage, scannedAt: .now), at: 0)
        HistoryStore.save(history)
        UINotificationFeedbackGenerator().notificationOccurred(.success)
    }

    func deleteHistory(at keys: [String]) {
        history.removeAll { keys.contains($0.card.key) }
        HistoryStore.save(history)
    }

    func clearHistory() {
        history = []
        HistoryStore.save(history)
    }

    private func refreshHistoryUsage() {
        guard let dataset else { return }
        for i in history.indices {
            history[i].usage = dataset.recentUsage[history[i].card.key] ?? 0
        }
        HistoryStore.save(history)
    }
}
