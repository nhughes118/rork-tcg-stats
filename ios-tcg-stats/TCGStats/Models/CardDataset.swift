import Foundation

/// Aggregated tournament data with card indexes and stats computation.
nonisolated struct CardDataset: Sendable {
    let tournaments: [TournamentRecord]
    let cards: [String: CardIdentity]
    let printIndex: [String: String]
    let setCodes: Set<String>
    let recentUsage: [String: Double]
    let generatedAt: Date

    init(tournaments: [TournamentRecord], now: Date = .now) {
        self.tournaments = tournaments.sorted { $0.date > $1.date }
        self.generatedAt = now

        var printCounts: [String: [CardIdentity: Int]] = [:]
        var prints: [String: String] = [:]
        var sets: Set<String> = []
        for t in tournaments {
            for e in t.entries {
                for c in e.cards {
                    let identity = CardIdentity(name: c.name, set: c.set, number: c.number, category: c.category)
                    printCounts[c.key, default: [:]][identity, default: 0] += 1
                    if !c.set.isEmpty {
                        sets.insert(c.set)
                        prints["\(c.set)-\(Int(c.number).map(String.init) ?? c.number)"] = c.key
                    }
                }
            }
        }
        var cards: [String: CardIdentity] = [:]
        for (key, counts) in printCounts {
            if let best = counts.max(by: { $0.value < $1.value })?.key {
                cards[key] = best
            }
        }
        self.cards = cards
        self.printIndex = prints
        self.setCodes = sets

        let cutoff = now.addingTimeInterval(-8 * 7 * 86_400)
        var total = 0
        var counts: [String: Int] = [:]
        for t in tournaments where t.date >= cutoff {
            for e in t.entries {
                total += 1
                for k in e.cardKeys { counts[k, default: 0] += 1 }
            }
        }
        var usage: [String: Double] = [:]
        if total > 0 {
            for (k, v) in counts { usage[k] = Double(v) / Double(total) }
        }
        self.recentUsage = usage
    }

    var totalDecklists: Int { tournaments.reduce(0) { $0 + $1.entries.count } }

    func tournament(id: String) -> TournamentRecord? {
        tournaments.first { $0.id == id }
    }

    private func usage(of key: String, from start: Date, to end: Date) -> Double? {
        var total = 0
        var hits = 0
        for t in tournaments where t.date >= start && t.date < end {
            total += t.entries.count
            hits += t.entries.reduce(0) { $0 + ($1.cardKeys.contains(key) ? 1 : 0) }
        }
        return total > 0 ? Double(hits) / Double(total) : nil
    }

    func stats(for key: String, window: StatsWindow, now: Date = .now) -> CardStats? {
        guard let card = cards[key] else { return nil }
        let cutoff = window.weeks.map { now.addingTimeInterval(-Double($0) * 7 * 86_400) }
        let scoped = tournaments.filter { t in cutoff.map { t.date >= $0 } ?? true }

        var total = 0
        var withCard = 0
        var copies = 0
        var decks: [String: (icons: [String], count: Int, best: Int?)] = [:]
        var appearances: [TournamentAppearance] = []
        var weekly: [Date: (total: Int, hits: Int)] = [:]
        var calendar = Calendar(identifier: .iso8601)
        calendar.timeZone = .current

        for t in scoped {
            let users = t.entries.filter { $0.cardKeys.contains(key) }
            total += t.entries.count
            withCard += users.count
            let week = calendar.dateInterval(of: .weekOfYear, for: t.date)?.start ?? t.date
            let current = weekly[week] ?? (0, 0)
            weekly[week] = (current.total + t.entries.count, current.hits + users.count)

            for u in users {
                copies += u.copies(of: key)
                var d = decks[u.deckName] ?? (u.deckIcons, 0, nil)
                d.count += 1
                if let p = u.placing { d.best = min(d.best ?? p, p) }
                decks[u.deckName] = d
            }
            if !users.isEmpty {
                let sorted = users.sorted { ($0.placing ?? Int.max) < ($1.placing ?? Int.max) }
                appearances.append(TournamentAppearance(
                    tournamentID: t.id,
                    name: t.name,
                    date: t.date,
                    players: t.players,
                    decklists: t.entries.count,
                    usage: Double(users.count) / Double(max(t.entries.count, 1)),
                    finishers: sorted
                ))
            }
        }

        let trend = weekly
            .map { TrendPoint(weekStart: $0.key, usage: $0.value.total > 0 ? Double($0.value.hits) / Double($0.value.total) : 0, decks: $0.value.total) }
            .sorted { $0.weekStart < $1.weekStart }

        let topDecks = decks
            .map { DeckShare(name: $0.key, icons: $0.value.icons, count: $0.value.count, share: Double($0.value.count) / Double(max(total, 1)), bestPlacing: $0.value.best) }
            .sorted { $0.count > $1.count }

        let month: TimeInterval = 28 * 86_400
        var delta: Double?
        if let recent = usage(of: key, from: now.addingTimeInterval(-month), to: now.addingTimeInterval(86_400)),
           let previous = usage(of: key, from: now.addingTimeInterval(-2 * month), to: now.addingTimeInterval(-month)) {
            delta = recent - previous
        }

        return CardStats(
            card: card,
            usage: total > 0 ? Double(withCard) / Double(total) : 0,
            delta: delta,
            decksWithCard: withCard,
            totalDecks: total,
            tournamentCount: scoped.count,
            averageCopies: withCard > 0 ? Double(copies) / Double(withCard) : 0,
            trend: trend,
            topDecks: topDecks,
            appearances: appearances
        )
    }

    /// Entries in the dataset that play the card with a given archetype.
    func entries(for key: String, deckName: String, window: StatsWindow, now: Date = .now) -> [(TournamentRecord, PlayerEntry)] {
        let cutoff = window.weeks.map { now.addingTimeInterval(-Double($0) * 7 * 86_400) }
        var result: [(TournamentRecord, PlayerEntry)] = []
        for t in tournaments where cutoff.map({ t.date >= $0 }) ?? true {
            for e in t.entries where e.deckName == deckName && e.cardKeys.contains(key) {
                result.append((t, e))
            }
        }
        return result.sorted { ($0.1.placing ?? Int.max, $1.0.date) < ($1.1.placing ?? Int.max, $0.0.date) }
    }
}
