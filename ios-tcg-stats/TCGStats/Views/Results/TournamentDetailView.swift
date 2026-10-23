import SwiftUI

struct TournamentDetailView: View {
    @Environment(AppModel.self) private var model
    let tournamentID: String
    let cardKey: String

    @State private var selectedEntry: PlayerEntry?
    /// Decklist to re-present when returning from a card opened inside it.
    @State private var reopenEntry: PlayerEntry?
    @State private var pendingCardKey: String?
    @State private var showAll: Bool = false
    @State private var scope: ResultsScope = .withCard
    @State private var query: String = ""
    @FocusState private var searchFocused: Bool

    private enum ResultsScope: Hashable { case withCard, all }

    var body: some View {
        let tournament = model.dataset?.tournament(id: tournamentID)
        let card = model.identity(for: cardKey)
        ScrollView {
            if let tournament {
                let users = tournament.entries
                    .filter { $0.cardKeys.contains(cardKey) }
                    .sorted { ($0.placing ?? Int.max) < ($1.placing ?? Int.max) }
                let usage = Double(users.count) / Double(max(tournament.entries.count, 1))
                let everyone = tournament.entries.sorted { ($0.placing ?? Int.max) < ($1.placing ?? Int.max) }
                let filtered = filter(scope == .withCard ? users : everyone)
                let visible = showAll || !query.isEmpty ? filtered : Array(filtered.prefix(16))

                VStack(alignment: .leading, spacing: 18) {
                    HStack(alignment: .top, spacing: 14) {
                        TrophyIcon(placing: users.first?.placing)
                            .font(.largeTitle)
                            .scaleEffect(1.5)
                            .frame(width: 48, height: 48)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(tournament.name)
                                .font(.system(.title, weight: .bold))
                                .foregroundStyle(Theme.ink)
                            Text(tournament.date.formatted(date: .abbreviated, time: .omitted))
                                .font(.subheadline)
                                .foregroundStyle(Theme.muted)
                            Text("\(tournament.source.label) · Standard")
                                .font(.subheadline)
                                .foregroundStyle(Theme.muted)
                        }
                    }
                    .padding(.top, 8)

                    HStack(spacing: 0) {
                        HStack(spacing: 12) {
                            Image(systemName: "person.3.fill")
                                .font(.title3)
                                .foregroundStyle(Theme.muted)
                            VStack(alignment: .leading, spacing: 0) {
                                Text("\(tournament.players)")
                                    .font(.system(.title, design: .rounded, weight: .bold))
                                    .monospacedDigit()
                                    .foregroundStyle(Theme.ink)
                                Text("players")
                                    .font(.subheadline)
                                    .foregroundStyle(Theme.muted)
                            }
                        }
                        .frame(maxWidth: .infinity)
                        Divider().frame(height: 60)
                        HStack(spacing: 12) {
                            UsageRing(value: usage, lineWidth: 8, caption: nil)
                                .font(.caption)
                                .frame(width: 84, height: 84)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(card?.name ?? "This card")
                                    .font(.subheadline.weight(.semibold))
                                    .foregroundStyle(Theme.ink)
                                    .lineLimit(2)
                                Text("in \(users.count) of \(tournament.entries.count) decks")
                                    .font(.caption)
                                    .foregroundStyle(Theme.muted)
                            }
                        }
                        .frame(maxWidth: .infinity)
                    }
                    .padding(.vertical, 16)
                    .padding(.horizontal, 8)
                    .cardSurface()

                    VStack(alignment: .leading, spacing: 12) {
                        Text(scope == .withCard ? "Top Finishers Using This Card" : "All Results")
                            .font(.title3.weight(.bold))
                            .foregroundStyle(Theme.ink)
                            .contentTransition(.opacity)
                        Picker("Results", selection: Binding(
                            get: { scope },
                            set: { value in withAnimation(.snappy) { scope = value; showAll = false } }
                        )) {
                            Text("With this card · \(users.count)").tag(ResultsScope.withCard)
                            Text("All decks · \(everyone.count)").tag(ResultsScope.all)
                        }
                        .pickerStyle(.segmented)
                        .sensoryFeedback(.selection, trigger: scope)

                        if scope == .all {
                            searchField
                            if tournament.players > everyone.count {
                                Text("\(everyone.count) of \(tournament.players) players published a decklist.")
                                    .font(.caption)
                                    .foregroundStyle(Theme.muted)
                            }
                        }
                    }

                    VStack(spacing: 0) {
                        if visible.isEmpty {
                            Text(query.isEmpty ? "No decklists for this event." : "No player or deck matches “\(query)”.")
                                .font(.subheadline)
                                .foregroundStyle(Theme.muted)
                                .multilineTextAlignment(.center)
                                .frame(maxWidth: .infinity, minHeight: 56)
                        }
                        ForEach(Array(visible.enumerated()), id: \.element.id) { index, entry in
                            if index > 0 { Divider().overlay(Theme.hairline).padding(.leading, 56) }
                            Button {
                                searchFocused = false
                                selectedEntry = entry
                            } label: {
                                FinisherRow(entry: entry, copies: entry.copies(of: cardKey))
                            }
                            .buttonStyle(PressableStyle())
                        }
                        if filtered.count > visible.count {
                            Divider().overlay(Theme.hairline)
                            Button(scope == .withCard ? "Show all \(filtered.count) players" : "Show all \(filtered.count) decks") {
                                withAnimation(.snappy) { showAll = true }
                            }
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(Theme.blue)
                            .frame(maxWidth: .infinity, minHeight: 48)
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 6)
                    .cardSurface()

                    if let url = tournament.url {
                        Link(destination: url) {
                            Label("Full standings on Limitless", systemImage: "arrow.up.right.square")
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(Theme.blue)
                                .frame(maxWidth: .infinity, minHeight: 44)
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
            } else {
                ContentUnavailableView("Tournament unavailable", systemImage: "trophy", description: Text("Pull to refresh tournament data."))
                    .padding(.top, 80)
            }
        }
        .background { CanvasBackground() }
        .navigationTitle(tournament?.name ?? "Tournament")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .scrollDismissesKeyboard(.interactively)
        .sheet(item: $selectedEntry, onDismiss: openPendingCard) { entry in
            DecklistSheet(entry: entry, highlightKey: cardKey, tournamentName: tournament?.name) { key in
                reopenEntry = entry
                pendingCardKey = key
                selectedEntry = nil
            }
        }
        .onAppear {
            guard let entry = reopenEntry else { return }
            reopenEntry = nil
            selectedEntry = entry
        }
    }

    private var searchField: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(Theme.red)
            TextField("Search player or deck", text: $query)
                .focused($searchFocused)
                .submitLabel(.search)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .onSubmit { searchFocused = false }
            if !query.isEmpty {
                Button { query = "" } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(Theme.muted)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Clear")
            }
        }
        .padding(.horizontal, 12)
        .frame(height: 44)
        .background(Color(.tertiarySystemFill), in: .rect(cornerRadius: 12))
    }

    /// Entries whose player or deck name contains every word of the search.
    private func filter(_ entries: [PlayerEntry]) -> [PlayerEntry] {
        let tokens = CardText.key(query).split(separator: " ").map(String.init)
        guard !tokens.isEmpty else { return entries }
        return entries.filter { entry in
            let hay = CardText.key("\(entry.name) \(entry.deckName)")
            return tokens.allSatisfy { hay.contains($0) }
        }
    }

    private func openPendingCard() {
        guard let key = pendingCardKey else { return }
        pendingCardKey = nil
        model.push(.card(key))
    }
}

struct FinisherRow: View {
    let entry: PlayerEntry
    var copies: Int?
    var subtitle: String?

    var body: some View {
        HStack(spacing: 12) {
            PlacingBadge(placing: entry.placing)
            DeckIconView(icons: entry.deckIcons, size: 48)
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.name)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(Theme.ink)
                    .lineLimit(1)
                Text(subtitle ?? entry.deckName)
                    .font(.subheadline)
                    .foregroundStyle(Theme.muted)
                    .lineLimit(1)
                if let copies, copies > 0 {
                    Text("\(copies)× copies")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(Theme.red)
                }
            }
            Spacer(minLength: 8)
            Text(entry.recordText)
                .font(.body.weight(.bold))
                .monospacedDigit()
                .foregroundStyle(Theme.ink)
            Image(systemName: "chevron.right")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Theme.muted.opacity(0.6))
        }
        .padding(.vertical, 10)
        .contentShape(.rect)
    }
}
