import Foundation
import SwiftUI

nonisolated enum EventKind: String, CaseIterable, Sendable {
    case prerelease = "Prerelease"
    case leagueCup = "League Cup"
    case leagueChallenge = "League Challenge"
    case championship = "Championship"
    case tournament = "Tournament"
    case league = "League Play"
    case other = "Event"
}

@MainActor
extension EventKind {
    var color: Color {
        switch self {
        case .prerelease: Theme.gold
        case .leagueCup: Theme.red
        case .leagueChallenge, .tournament: Theme.blue
        case .championship: Theme.ink
        case .league: Theme.green
        case .other: Theme.muted
        }
    }

    /// Text color on a tinted badge (gold is too light for text).
    var textColor: Color { self == .prerelease ? Theme.ink : color }
}

nonisolated struct GeoPoint: Hashable, Sendable {
    let latitude: Double
    let longitude: Double
    let label: String
}

nonisolated struct LocalEvent: Identifiable, Hashable, Sendable {
    let id: String
    let name: String
    let kind: EventKind
    let typeName: String?
    let start: Date?
    let hasTime: Bool
    let timeZoneID: String?
    let venue: String?
    let address: String?
    let latitude: Double?
    let longitude: Double?
    let distanceMiles: Double?
    let products: [String]
    let cost: String?
    let link: String?

    /// Hides only events that are clearly for other games (VG, GO, UNITE); unknown product formats stay visible.
    var isTCG: Bool {
        if name.localizedCaseInsensitiveContains("tcg") || products.isEmpty { return true }
        if products.contains(where: { $0.localizedCaseInsensitiveContains("tcg") || $0.localizedCaseInsensitiveContains("trading card") }) {
            return true
        }
        let otherGame = ["video game", "videogame", "vg", "go", "pokémon go", "pokemon go", "unite", "pokémon unite", "pokemon unite"]
        return !products.allSatisfy { p in
            let t = p.lowercased().trimmingCharacters(in: .whitespaces)
            return otherGame.contains(t) || t.contains("video game") || t.hasSuffix(" go") || t.contains("unite")
        }
    }

    var badgeLabel: String { kind == .other ? (typeName ?? kind.rawValue) : kind.rawValue }

    private var zone: TimeZone { timeZoneID.flatMap(TimeZone.init(identifier:)) ?? .current }

    private func format(_ pattern: String) -> String? {
        guard let start else { return nil }
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = zone
        f.dateFormat = pattern
        return f.string(from: start)
    }

    var dayKey: String { format("yyyy-MM-dd") ?? "unknown" }
    var dayLabel: String { format("EEEE, MMM d") ?? "Date to be announced" }
    var timeLabel: String? { hasTime ? format("h:mm a") : nil }
    var fullDateLabel: String { format(hasTime ? "EEE, MMM d · h:mm a" : "EEE, MMM d") ?? "Date TBA" }
    var monthAbbrev: String { format("MMM")?.uppercased() ?? "TBA" }
    var dayNumber: String { format("d") ?? "–" }
    var zoneLabel: String? {
        guard let timeZoneID, let tz = TimeZone(identifier: timeZoneID) else { return nil }
        return (tz.abbreviation(for: start ?? .now) ?? tz.identifier) + " local time"
    }

    static func milesLabel(_ d: Double) -> String {
        d < 10 ? String(format: "%.1f mi", d) : "\(Int(d)) mi"
    }
}
