import Foundation

/// Tournament source the stats are computed from.
nonisolated enum DataSource: String, Codable, CaseIterable, Identifiable, Sendable, Hashable {
    case online, official

    var id: String { rawValue }
    var label: String { self == .online ? "Online" : "Official" }
    var tagline: String { self == .online ? "TCG Live & online events" : "In-person Regionals & majors" }
}

nonisolated enum CardCategory: String, Codable, Sendable, CaseIterable {
    case pokemon, trainer, energy

    var title: String {
        switch self {
        case .pokemon: "Pokémon"
        case .trainer: "Trainer"
        case .energy: "Energy"
        }
    }
}

/// A single line in a decklist.
nonisolated struct DeckCard: Codable, Hashable, Sendable {
    let count: Int
    let name: String
    let set: String
    let number: String
    let category: CardCategory
    /// Variant key when this name covers several distinct cards (see `CardVariants`).
    var variant: String? = nil

    var nameKey: String { CardText.key(name) }
    var key: String { variant ?? nameKey }
}

/// A printed card used to represent a card name (image, set, number).
nonisolated struct CardIdentity: Codable, Hashable, Sendable {
    let name: String
    let set: String
    let number: String
    let category: CardCategory
    /// Variant key when the name covers several distinct cards.
    var variant: String? = nil
    /// Short distinguishing text for variants, e.g. "Darkness · Whirling Envy".
    var note: String? = nil

    var nameKey: String { CardText.key(name) }
    var key: String { variant ?? nameKey }
    var printLabel: String {
        let base = self.set.isEmpty ? category.title : "\(self.set) \(number)"
        guard let note, !note.isEmpty else { return base }
        return "\(base) · \(note)"
    }

    func imageURL(large: Bool = false) -> URL? {
        guard !set.isEmpty, !number.isEmpty else { return nil }
        let padded: String
        if let n = Int(number) {
            padded = String(format: "%03d", n)
        } else {
            padded = number
        }
        let size = large ? "LG" : "SM"
        return URL(string: "https://limitlesstcg.nyc3.cdn.digitaloceanspaces.com/tpci/\(set)/\(set)_\(padded)_R_EN_\(size).png")
    }

    var limitlessURL: URL? {
        guard !set.isEmpty, !number.isEmpty else { return URL(string: "https://limitlesstcg.com/cards") }
        return URL(string: "https://limitlesstcg.com/cards/\(set)/\(number)")
    }
}

/// One player's finish in a tournament.
nonisolated struct PlayerEntry: Codable, Hashable, Identifiable, Sendable {
    let id: String
    let name: String
    let placing: Int?
    let wins: Int
    let losses: Int
    let ties: Int
    let deckName: String
    let deckIcons: [String]
    let cards: [DeckCard]
    let cardKeys: Set<String>

    var recordText: String {
        guard wins != 0 || losses != 0 || ties != 0 else { return "—" }
        return ties > 0 ? "\(wins)-\(losses)-\(ties)" : "\(wins)-\(losses)"
    }

    func copies(of key: String) -> Int {
        cards.filter { $0.key == key }.reduce(0) { $0 + $1.count }
    }

    /// Plain-text decklist in the Pokémon TCG Live import format.
    var exportText: String {
        var sections: [String] = []
        for category in CardCategory.allCases {
            let lines = cards.filter { $0.category == category }
            guard !lines.isEmpty else { continue }
            let total = lines.reduce(0) { $0 + $1.count }
            var block = ["\(category.title): \(total)"]
            block += lines.map { card in
                [String(card.count), card.name, card.set, card.number]
                    .filter { !$0.isEmpty }
                    .joined(separator: " ")
            }
            sections.append(block.joined(separator: "\n"))
        }
        sections.append("Total Cards: \(cards.reduce(0) { $0 + $1.count })")
        return sections.joined(separator: "\n\n")
    }
}

/// A completed tournament with every published decklist.
nonisolated struct TournamentRecord: Codable, Hashable, Identifiable, Sendable {
    let id: String
    let name: String
    let date: Date
    let players: Int
    let entries: [PlayerEntry]
    var source: DataSource

    init(id: String, name: String, date: Date, players: Int, entries: [PlayerEntry], source: DataSource = .online) {
        self.id = id
        self.name = name
        self.date = date
        self.players = players
        self.entries = entries
        self.source = source
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = (try? c.decode(String.self, forKey: .name)) ?? "Tournament"
        date = (try? c.decode(Date.self, forKey: .date)) ?? .now
        players = (try? c.decode(Int.self, forKey: .players)) ?? 0
        entries = (try? c.decode([PlayerEntry].self, forKey: .entries)) ?? []
        source = (try? c.decodeIfPresent(DataSource.self, forKey: .source)) ?? .online
    }

    var url: URL? {
        switch source {
        case .online: URL(string: "https://play.limitlesstcg.com/tournament/\(id)/standings")
        case .official:
            LabsLimitless.isLabsID(id) ? LabsLimitless.pageURL(id) : URL(string: "https://limitlesstcg.com/tournaments/\(id)")
        }
    }
}

nonisolated enum StatsWindow: String, CaseIterable, Identifiable, Hashable, Sendable {
    case fourWeeks, eightWeeks, all

    var id: String { rawValue }
    var label: String {
        switch self {
        case .fourWeeks: "4 wks"
        case .eightWeeks: "8 wks"
        case .all: "All"
        }
    }
    var weeks: Int? {
        switch self {
        case .fourWeeks: 4
        case .eightWeeks: 8
        case .all: nil
        }
    }
}

nonisolated struct TrendPoint: Identifiable, Hashable, Sendable {
    let weekStart: Date
    let usage: Double
    let decks: Int
    var id: Date { weekStart }
}

nonisolated struct DeckShare: Identifiable, Hashable, Sendable {
    let name: String
    let icons: [String]
    let count: Int
    /// Share of all decks in the window.
    let share: Double
    /// Best finish among these decks.
    let bestPlacing: Int?
    var id: String { name }
}

nonisolated struct TournamentAppearance: Identifiable, Hashable, Sendable {
    let tournamentID: String
    let name: String
    let date: Date
    let players: Int
    let decklists: Int
    let usage: Double
    let finishers: [PlayerEntry]

    var id: String { tournamentID }
    var best: PlayerEntry? { finishers.first }
}

nonisolated struct CardStats: Sendable {
    let card: CardIdentity
    let usage: Double
    let delta: Double?
    let decksWithCard: Int
    let totalDecks: Int
    let tournamentCount: Int
    let averageCopies: Double
    let trend: [TrendPoint]
    let topDecks: [DeckShare]
    let appearances: [TournamentAppearance]

    /// Appearances ordered by best finish, then size.
    var topAppearances: [TournamentAppearance] {
        appearances.sorted {
            let a = $0.best?.placing ?? Int.max
            let b = $1.best?.placing ?? Int.max
            if a != b { return a < b }
            return $0.players > $1.players
        }
    }
}

/// A card the user has looked up.
nonisolated struct HistoryEntry: Codable, Identifiable, Hashable, Sendable {
    var id: String { card.key }
    let card: CardIdentity
    var usage: Double
    var scannedAt: Date
}

/// Text helpers shared by matching and indexing.
nonisolated enum CardText {
    static func normalize(_ s: String) -> String {
        let folded = s
            .replacingOccurrences(of: "’", with: "'")
            .folding(options: [.diacriticInsensitive, .caseInsensitive], locale: Locale(identifier: "en_US"))
            .lowercased()
        var out = ""
        var lastSpace = true
        for ch in folded {
            if ch.isLetter || ch.isNumber || ch == "'" || ch == "-" {
                out.append(ch)
                lastSpace = false
            } else if !lastSpace {
                out.append(" ")
                lastSpace = true
            }
        }
        return out.trimmingCharacters(in: .whitespaces)
    }

    static func key(_ name: String) -> String { normalize(name) }

    static func ordinal(_ n: Int?) -> String {
        guard let n else { return "—" }
        let suffix: String
        switch (n % 100, n % 10) {
        case (11...13, _): suffix = "th"
        case (_, 1): suffix = "st"
        case (_, 2): suffix = "nd"
        case (_, 3): suffix = "rd"
        default: suffix = "th"
        }
        return "\(n)\(suffix)"
    }

    static func percent(_ v: Double, digits: Int = 1) -> String {
        String(format: "%.\(digits)f%%", v * 100)
    }
}
