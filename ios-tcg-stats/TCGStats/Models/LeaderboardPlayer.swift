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
}

/// A Championship Points season as listed in the leaderboard page's season picker.
nonisolated struct LeaderboardSeason: Sendable, Hashable {
    let id: String
    let label: String
}
