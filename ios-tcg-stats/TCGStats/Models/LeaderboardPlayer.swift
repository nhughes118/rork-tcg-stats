import Foundation

nonisolated enum Division: String, CaseIterable, Codable, Sendable, Identifiable {
    case masters = "masters"
    case seniors = "seniors"
    case juniors = "juniors"

    var id: String { rawValue }

    var label: String {
        switch self {
        case .masters: "Masters"
        case .seniors: "Seniors"
        case .juniors: "Juniors"
        }
    }
}

/// Championship product: the Trading Card Game, the video games (VGC) or Pokémon GO.
nonisolated enum Game: String, CaseIterable, Codable, Sendable, Identifiable {
    case tcg
    case vgc
    case go

    var id: String { rawValue }

    /// The product code the leaderboards API expects.
    var apiSlug: String {
        switch self {
        case .tcg: "tcg"
        case .vgc: "vg"
        case .go: "pgo"
        }
    }

    /// The pillar code the championships site's URLs use.
    var siteSlug: String {
        switch self {
        case .tcg: "tcg"
        case .vgc: "vgc"
        case .go: "go"
        }
    }

    var label: String {
        switch self {
        case .tcg: "TCG"
        case .vgc: "VGC"
        case .go: "Pokémon GO"
        }
    }

    /// Pokémon GO has a single combined leaderboard with no age divisions.
    var hasDivisions: Bool { self != .go }

    /// The API's division value for `division` on this game's board.
    func divisionSlug(_ division: Division) -> String { hasDivisions ? division.rawValue : "all" }
}

nonisolated struct LeaderboardPlayer: Codable, Hashable, Sendable, Identifiable {
    let rank: Int
    let name: String
    let points: Int
    let country: String?
    let countryCode: String?
    let inCutoff: Bool

    var id: String { "\(rank)|\(name)" }
}

nonisolated struct LeaderboardIndex: Codable, Sendable {
    let division: String
    let fetchedAt: Date
    let total: Int
    let complete: Bool
    let players: [LeaderboardPlayer]
    /// Season label shown in the UI, e.g. "2026".
    var season: String? = nil
    /// Code of the `LeaderboardRegion` this index was built for (nil in older caches = Global).
    var region: String? = nil
    /// Raw value of the `Game` this index was built for (nil in older caches = TCG).
    var game: String? = nil
}

/// Play! Pokémon championship regions. Each zone has its own leaderboard with region-specific ranks.
nonisolated enum LeaderboardRegion: String, CaseIterable, Codable, Sendable, Identifiable {
    case global = "global"
    case northAmerica = "NA"
    case europe = "EU"
    case latinAmerica = "LA"
    case oceania = "AP"
    case middleEast = "SO"
    case russia = "RU"

    var id: String { rawValue }

    /// `global` boards cover the whole world; the rest are regional zones.
    var type: String { self == .global ? "global" : "zone" }

    var label: String {
        switch self {
        case .global: "Global"
        case .northAmerica: "US & Canada"
        case .europe: "Europe"
        case .latinAmerica: "Latin America"
        case .oceania: "Oceania"
        case .middleEast: "Middle East & S. Africa"
        case .russia: "Russia"
        }
    }
}
