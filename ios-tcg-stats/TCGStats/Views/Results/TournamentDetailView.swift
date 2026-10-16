import SwiftUI

struct TournamentDetailView: View {
    @Environment(AppModel.self) private var model
    let tournamentID: String
    let cardKey: String

    @State private var selectedEntry: PlayerEntry?
    @State private var showAll: Bool = false

    var body: some View {
        let tournament = model.dataset?.tournament(id: tournamentID)
        let card = model.identity(for: cardKey)
        ScrollView {
            if let tournament {
                let users = tournament.entries
                    .filter { $0.cardKeys.contains(cardKey) }
                    .sorted { ($0.placing ?? Int.max) < ($1.placing ?? Int.max) }
                let usage = Double(users.count) / Double(max(tournament.entries.count, 1))
                let visible = showAll ? users : Array(users.prefix(16))

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

                    Text("Top Finishers Using This Card")
                        .font(.title3.weight(.bold))
                        .foregroundStyle(Theme.ink)

                    VStack(spacing: 0) {
                        ForEach(Array(visible.enumerated()), id: \.element.id) { index, entry in
                            if index > 0 { Divider().overlay(Theme.hairline).padding(.leading, 56) }
                            Button {
                                selectedEntry = entry
                            } label: {
                                FinisherRow(entry: entry, copies: entry.copies(of: cardKey))
                            }
                            .buttonStyle(PressableStyle())
                        }
                        if users.count > visible.count {
                            Divider().overlay(Theme.hairline)
                            Button("Show all \(users.count) players") {
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
        .sheet(item: $selectedEntry) { entry in
            DecklistSheet(entry: entry, highlightKey: cardKey, tournamentName: tournament?.name)
        }
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
