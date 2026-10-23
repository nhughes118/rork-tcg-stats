import Foundation

nonisolated enum Division: String, CaseIterable, Codable, Sendable, Identifiable {
    case masters = "tcg-master"
    case seniors = "tcg-senior"
    case juniors = "tcg-junior"

    var id: String { rawValue }

    var label: String {
        switch self {
        case .masters: "Masters"
        case .seniors: "Seniors"
        case .juniors: "Juniors"
        }
    }
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
    /// Zone of the `LeaderboardRegion` this index was built for (nil in older caches = Global).
    var region: String? = nil
}

/// Play! Pokémon rating zones. Each zone has its own leaderboard with region-specific ranks.
nonisolated enum LeaderboardRegion: String, CaseIterable, Codable, Sendable, Identifiable {
    case global = "All"
    case northAmerica = "REGION-NA"
    case europe = "REGION-EU"
    case latinAmerica = "REGION-LA"
    case oceania = "REGION-AP"
    case middleEast = "REGION-SO"

    var id: String { rawValue }

    var label: String {
        switch self {
        case .global: "Global"
        case .northAmerica: "US & Canada"
        case .europe: "Europe"
        case .latinAmerica: "Latin America"
        case .oceania: "Oceania"
        case .middleEast: "Middle East & S. Africa"
        }
    }
}

/// A Championship Points season as listed in the leaderboard page's season picker.
nonisolated struct LeaderboardSeason: Sendable, Hashable {
    let id: String
    let label: String
}
