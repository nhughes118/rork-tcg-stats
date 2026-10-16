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
        .sheet(item: $selected) { item in
            DecklistSheet(entry: item.entry, highlightKey: cardKey, tournamentName: item.tournament)
        }
    }
}

/// Full decklist with the scanned card highlighted.
struct DecklistSheet: View {
    @Environment(\.dismiss) private var dismiss
    let entry: PlayerEntry
    let highlightKey: String
    let tournamentName: String?
    @State private var didCopy: Bool = false
    @State private var copyTask: Task<Void, Never>?

    var body: some View {
        NavigationStack {
            List {
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
                                }
                                .listRowBackground(isHighlighted ? Theme.red.opacity(0.08) : Color.white)
                            }
                        }
                    }
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
        }
        .presentationDetents([.medium, .large])
        .sensoryFeedback(.success, trigger: didCopy) { _, new in new }
        .onDisappear { copyTask?.cancel() }
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
