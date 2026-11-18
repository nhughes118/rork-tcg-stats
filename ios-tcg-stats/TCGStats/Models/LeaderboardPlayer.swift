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
