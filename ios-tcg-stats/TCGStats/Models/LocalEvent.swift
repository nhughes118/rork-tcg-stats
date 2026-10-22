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

nonisolated enum EventGame: String, CaseIterable, Sendable {
    case tcg = "TCG"
    case vgc = "VGC"
    case go = "GO"
}

nonisolated enum EventGameFilter: String, CaseIterable, Sendable {
    case all = "All", tcg = "TCG", vgc = "VGC", go = "GO"

    var game: EventGame? {
        switch self {
        case .all: nil
        case .tcg: .tcg
        case .vgc: .vgc
        case .go: .go
        }
    }
}

nonisolated enum EventTypeFilter: String, CaseIterable, Sendable {
    case all = "All", cp = "CP events", other = "Other"
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

    /// Games this event is for, from its listed products and name. Unknown formats count as TCG;
    /// UNITE-only events have no game and only show under "All".
    var games: Set<EventGame> {
        var result = Set<EventGame>()
        func scan(_ raw: String, fromName: Bool) {
            let t = raw.lowercased()
            if t.contains("tcg") || t.contains("trading card") { result.insert(.tcg) }
            if t.contains("video game") || t.contains("videogame") || t.contains("vgc")
                || t.range(of: #"\bvg\b"#, options: .regularExpression) != nil { result.insert(.vgc) }
            if t.contains("pokémon go") || t.contains("pokemon go")
                || (!fromName && t.range(of: #"\bgo\b"#, options: .regularExpression) != nil) { result.insert(.go) }
        }
        products.forEach { scan($0, fromName: false) }
        scan(name, fromName: true)
        if result.isEmpty {
            let unite = products.contains { $0.localizedCaseInsensitiveContains("unite") }
                || name.localizedCaseInsensitiveContains("unite")
            if !unite { result.insert(.tcg) }
        }
        return result
    }

    /// Short game label for rows, e.g. "TCG" or "TCG/VGC".
    var gameLabel: String? {
        let g = games
        guard !g.isEmpty else { return products.contains { $0.localizedCaseInsensitiveContains("unite") } ? "UNITE" : nil }
        return EventGame.allCases.filter(g.contains).map(\.rawValue).joined(separator: "/")
    }

    /// Championship Points events (League Challenges and League Cups).
    var awardsCP: Bool { kind == .leagueCup || kind == .leagueChallenge }

    func matches(game: EventGameFilter, type: EventTypeFilter) -> Bool {
        if let g = game.game, !games.contains(g) { return false }
        switch type {
        case .all: return true
        case .cp: return awardsCP
        case .other: return !awardsCP
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
