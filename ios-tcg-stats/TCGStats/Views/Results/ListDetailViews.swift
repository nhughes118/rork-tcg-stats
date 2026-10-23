import SwiftUI
import UIKit

struct AllTournamentsView: View {
    @Environment(AppModel.self) private var model
    let cardKey: String
    let window: StatsWindow

    var body: some View {
        let appearances = (model.stats(for: cardKey, window: window)?.appearances ?? []).sorted { $0.date > $1.date }
        List {
            Section {
                ForEach(appearances) { appearance in
                    NavigationLink(value: AppRoute.tournament(id: appearance.tournamentID, cardKey: cardKey)) {
                        TournamentRow(appearance: appearance)
                    }
                }
            } footer: {
                Text("\(appearances.count) tournaments where this card made a decklist")
            }
        }
        .scrollContentBackground(.hidden)
        .background { CanvasBackground() }
        .navigationTitle("Tournaments")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
    }
}

struct AllDecksView: View {
    @Environment(AppModel.self) private var model
    let cardKey: String
    let window: StatsWindow

    var body: some View {
        let decks = model.stats(for: cardKey, window: window)?.topDecks ?? []
        List {
            ForEach(Array(decks.enumerated()), id: \.element.id) { index, deck in
                NavigationLink(value: AppRoute.deck(cardKey: cardKey, deckName: deck.name, window: window)) {
                    DeckRowPlain(rank: index + 1, deck: deck)
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background { CanvasBackground() }
        .navigationTitle("Decks")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
    }
}

private struct DeckRowPlain: View {
    let rank: Int
    let deck: DeckShare

    var body: some View {
        HStack(spacing: 12) {
            Text("\(rank)")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Theme.muted)
                .frame(width: 24)
            DeckIconView(icons: deck.icons, size: 44)
            VStack(alignment: .leading, spacing: 2) {
                Text(deck.name).font(.body.weight(.medium)).foregroundStyle(Theme.ink)
                Text("\(deck.count) decks · best \(CardText.ordinal(deck.bestPlacing))")
                    .font(.caption).foregroundStyle(Theme.muted)
            }
            Spacer()
            Text(CardText.percent(deck.share))
                .font(.body.weight(.bold)).monospacedDigit().foregroundStyle(Theme.ink)
        }
    }
}

struct DeckEntriesView: View {
    @Environment(AppModel.self) private var model
    let cardKey: String
    let deckName: String
    let window: StatsWindow
    @State private var selected: SelectedEntry?
    /// Decklist to re-present when returning from a card opened inside it.
    @State private var reopen: SelectedEntry?
    @State private var pendingCardKey: String?

    struct SelectedEntry: Identifiable {
        let entry: PlayerEntry
        let tournament: String
        var id: String { tournament + entry.id }
    }

    var body: some View {
        let rows = model.dataset?.entries(for: cardKey, deckName: deckName, window: window) ?? []
        ScrollView {
            VStack(spacing: 0) {
                ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                    if index > 0 { Divider().overlay(Theme.hairline).padding(.leading, 56) }
                    Button {
                        selected = SelectedEntry(entry: row.1, tournament: row.0.name)
                    } label: {
                        FinisherRow(entry: row.1, subtitle: row.0.name)
                    }
                    .buttonStyle(PressableStyle())
                }
                if rows.isEmpty { emptyRow("No results") }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 6)
            .cardSurface()
            .padding(16)
        }
        .background { CanvasBackground() }
        .navigationTitle(deckName)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .sheet(item: $selected, onDismiss: openPendingCard) { item in
            DecklistSheet(entry: item.entry, highlightKey: cardKey, tournamentName: item.tournament) { key in
                reopen = item
                pendingCardKey = key
                selected = nil
            }
        }
        .onAppear {
            guard let item = reopen else { return }
            reopen = nil
            selected = item
        }
    }

    private func openPendingCard() {
        guard let key = pendingCardKey else { return }
        pendingCardKey = nil
        model.push(.card(key))
    }
}

/// Full decklist with the scanned card highlighted.
struct DecklistSheet: View {
    @Environment(\.dismiss) private var dismiss
    let entry: PlayerEntry
    let highlightKey: String
    let tournamentName: String?
    /// Opens the tapped card's results; the host re-presents this list on back.
    var onOpenCard: ((String) -> Void)? = nil
    @State private var didCopy: Bool = false
    @State private var copyTask: Task<Void, Never>?
    @State private var tab: SheetTab = .decklist
    @State private var matches: [LabsLimitless.LabsMatch]?
    @State private var matchesFailed = false

    private enum SheetTab: Hashable { case decklist, matches }

    /// Round-by-round results exist for Limitless Labs entries only.
    private var labsRef: (tournament: String, player: Int)? { LabsLimitless.playerRef(entryID: entry.id) }

    var body: some View {
        NavigationStack {
            List {
                if labsRef != nil {
                    Section {
                        Picker("View", selection: Binding(
                            get: { tab },
                            set: { value in withAnimation(.snappy) { tab = value } }
                        )) {
                            Text("Decklist").tag(SheetTab.decklist)
                            Text("Matches").tag(SheetTab.matches)
                        }
                        .pickerStyle(.segmented)
                        .sensoryFeedback(.selection, trigger: tab)
                        .listRowInsets(EdgeInsets(top: 0, leading: 0, bottom: 0, trailing: 0))
                        .listRowBackground(Color.clear)
                    }
                }
                if tab == .matches {
                    matchesContent
                } else {
                    decklistContent
                }
            }
            .navigationTitle(entry.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    ShareLink(item: shareText) {
                        Image(systemName: "square.and.arrow.up")
                    }
                    .disabled(entry.cards.isEmpty)
                    .accessibilityLabel("Share decklist")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .task(id: tab) { await loadMatches() }
        }
        .presentationDetents([.medium, .large])
        .sensoryFeedback(.success, trigger: didCopy) { _, new in new }
        .onDisappear { copyTask?.cancel() }
    }

    private func loadMatches() async {
        guard tab == .matches, matches == nil, let ref = labsRef else { return }
        matchesFailed = false
        do { matches = try await LabsLimitless.matches(tournament: ref.tournament, player: ref.player) }
        catch { matchesFailed = true }
    }

    @ViewBuilder
    private var matchesContent: some View {
        if let matches {
            if matches.isEmpty {
                Section { Text("No matches recorded for this player.").foregroundStyle(.secondary) }
            } else {
                Section("By opponent deck") {
                    ForEach(MatchupSummary.make(matches)) { row in
                        HStack(spacing: 12) {
                            DeckIconView(icons: row.icons, size: 36)
                            Text(row.deck)
                                .foregroundStyle(Theme.ink)
                                .lineLimit(1)
                            Spacer(minLength: 8)
                            Text(row.recordText)
                                .font(.body.weight(.bold))
                                .monospacedDigit()
                                .foregroundStyle(row.wins > row.losses ? Theme.blue : row.losses > row.wins ? Theme.red : Theme.ink)
                        }
                    }
                }
                Section("Rounds") {
                    ForEach(matches) { match in
                        MatchRow(match: match)
                    }
                }
                if let ref = labsRef, let url = LabsLimitless.playerURL(tournament: ref.tournament, player: ref.player) {
                    Section {
                        Link(destination: url) {
                            Label("View on Limitless Labs", systemImage: "arrow.up.right.square")
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(Theme.blue)
                        }
                    }
                }
            }
        } else if matchesFailed {
            Section {
                VStack(spacing: 10) {
                    Text("Couldn't load matches from Limitless Labs.")
                        .foregroundStyle(.secondary)
                    Button("Try Again") { Task { await loadMatches() } }
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Theme.blue)
                }
                .frame(maxWidth: .infinity, minHeight: 80)
            }
        } else {
            Section {
                ProgressView("Loading matches…")
                    .frame(maxWidth: .infinity, minHeight: 80)
            }
        }
    }

    @ViewBuilder
    private var decklistContent: some View {
                Section {
                    HStack(spacing: 12) {
                        DeckIconView(icons: entry.deckIcons, size: 52)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(entry.deckName).font(.headline)
                            Text("\(CardText.ordinal(entry.placing)) · \(entry.recordText)\(tournamentName.map { " · \($0)" } ?? "")")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                    }
                    CopyDecklistButton(didCopy: didCopy, isEnabled: !entry.cards.isEmpty, action: copyDecklist)
                        .listRowInsets(EdgeInsets(top: 4, leading: 16, bottom: 12, trailing: 16))
                        .listRowSeparator(.hidden)
                }
                ForEach(CardCategory.allCases, id: \.self) { category in
                    let cards = entry.cards.filter { $0.category == category }
                    if !cards.isEmpty {
                        Section("\(category.title) · \(cards.reduce(0) { $0 + $1.count })") {
                            ForEach(Array(cards.enumerated()), id: \.offset) { _, card in
                                let isHighlighted = card.key == highlightKey
                                Button {
                                    onOpenCard?(card.key)
                                } label: {
                                HStack(spacing: 12) {
                                    Text("\(card.count)")
                                        .font(.body.weight(.bold))
                                        .monospacedDigit()
                                        .frame(width: 22)
                                        .foregroundStyle(isHighlighted ? Theme.red : Theme.ink)
                                    CardImageView(card: CardIdentity(name: card.name, set: card.set, number: card.number, category: card.category), cornerRadius: 3)
                                        .frame(width: 30)
                                    Text(card.name)
                                        .fontWeight(isHighlighted ? .bold : .regular)
                                        .foregroundStyle(isHighlighted ? Theme.red : Theme.ink)
                                    Spacer()
                                    Text("\(card.set) \(card.number)")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                    Image(systemName: "chevron.right")
                                        .font(.footnote.weight(.semibold))
                                        .foregroundStyle(.tertiary)
                                }
                                .contentShape(.rect)
                                }
                                .buttonStyle(.plain)
                                .disabled(onOpenCard == nil)
                                .accessibilityHint("Opens tournament results for this card")
                                .listRowBackground(isHighlighted ? Theme.red.opacity(0.08) : Color.white)
                            }
                        }
                    }
                }
    }

    private var shareText: String {
        "\(entry.deckName) — \(entry.name)\n\n\(entry.exportText)"
    }

    private func copyDecklist() {
        UIPasteboard.general.string = entry.exportText
        withAnimation(.spring(duration: 0.3)) { didCopy = true }
        copyTask?.cancel()
        copyTask = Task {
            try? await Task.sleep(for: .seconds(2))
            guard !Task.isCancelled else { return }
            withAnimation(.spring(duration: 0.3)) { didCopy = false }
        }
    }
}

/// A player's record against each opponent deck.
private struct MatchupSummary: Identifiable {
    let deck: String
    let icons: [String]
    var wins = 0
    var losses = 0
    var ties = 0
    var id: String { deck }
    var recordText: String { ties > 0 ? "\(wins)-\(losses)-\(ties)" : "\(wins)-\(losses)" }

    static func make(_ matches: [LabsLimitless.LabsMatch]) -> [MatchupSummary] {
        var byDeck: [String: MatchupSummary] = [:]
        var order: [String] = []
        for m in matches where m.opponent != nil {
            if byDeck[m.opponentDeck] == nil {
                byDeck[m.opponentDeck] = MatchupSummary(deck: m.opponentDeck, icons: m.opponentIcons)
                order.append(m.opponentDeck)
            }
            switch m.result {
            case .win: byDeck[m.opponentDeck]?.wins += 1
            case .loss: byDeck[m.opponentDeck]?.losses += 1
            case .tie: byDeck[m.opponentDeck]?.ties += 1
            }
        }
        return order.compactMap { byDeck[$0] }.sorted {
            let a = $0.wins + $0.losses + $0.ties, b = $1.wins + $1.losses + $1.ties
            return a != b ? a > b : $0.deck < $1.deck
        }
    }
}

private struct MatchRow: View {
    let match: LabsLimitless.LabsMatch

    var body: some View {
        HStack(spacing: 12) {
            VStack(spacing: 1) {
                Text(match.isTopCut ? "TOP" : "R")
                    .font(.system(size: 9, weight: .bold))
                    .foregroundStyle(match.isTopCut ? Theme.gold : Theme.muted)
                Text("\(match.round)")
                    .font(.subheadline.weight(.bold))
                    .monospacedDigit()
                    .foregroundStyle(Theme.ink)
            }
            .frame(width: 30)
            DeckIconView(icons: match.opponentIcons, size: 40)
            VStack(alignment: .leading, spacing: 2) {
                Text(match.opponent ?? "Bye")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(Theme.ink)
                    .lineLimit(1)
                Text(match.opponent == nil ? "Free win" : "\(match.opponentDeck) · \(match.opponentRecord)")
                    .font(.subheadline)
                    .foregroundStyle(Theme.muted)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            ResultBadge(result: match.result)
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }
}

private struct ResultBadge: View {
    let result: LabsLimitless.MatchResult

    var body: some View {
        let (letter, color): (String, Color) = switch result {
        case .win: ("W", Theme.blue)
        case .loss: ("L", Theme.red)
        case .tie: ("T", Theme.silver)
        }
        Text(letter)
            .font(.subheadline.weight(.heavy))
            .foregroundStyle(.white)
            .frame(width: 30, height: 30)
            .background(color, in: .rect(cornerRadius: 8))
            .accessibilityLabel(result == .win ? "Win" : result == .loss ? "Loss" : "Tie")
    }
}

private struct CopyDecklistButton: View {
    let didCopy: Bool
    let isEnabled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Image(systemName: didCopy ? "checkmark.circle.fill" : "doc.on.doc.fill")
                    .contentTransition(.symbolEffect(.replace))
                Text(didCopy ? "Copied to Clipboard" : "Copy Decklist")
                    .contentTransition(.opacity)
            }
            .font(.body.weight(.semibold))
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity, minHeight: 48)
            .background(didCopy ? Theme.blue : Theme.red, in: .rect(cornerRadius: 14))
        }
        .buttonStyle(PressableStyle())
        .disabled(!isEnabled)
        .opacity(isEnabled ? 1 : 0.4)
        .accessibilityHint("Copies the list as text in Pokémon TCG Live format")
    }
}
