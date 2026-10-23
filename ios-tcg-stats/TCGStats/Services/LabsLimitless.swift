import Foundation

/// Limitless Labs (labs.limitlesstcg.com): full standings and every player's
/// decklist for official Regionals, Internationals, Special Events and Worlds.
/// Data comes from the JSON API the Labs site itself uses.
nonisolated enum LabsLimitless {
    static let api = "https://mew.limitlesstcg.com/labs/data/tcg"
    static let idPrefix = "labs-"

    struct LabsTournament: Sendable {
        let id: String
        let name: String
        let date: Date
        let completed: Bool
    }

    struct LabsStanding: Sendable {
        let tpID: Int
        let name: String
        let placing: Int?
        let wins: Int
        let losses: Int
        let ties: Int
        let deckName: String
        let icons: [String]
        let hasList: Bool
    }

    static func isLabsID(_ id: String) -> Bool { id.hasPrefix(idPrefix) }
    static func labsID(_ recordID: String) -> String { String(recordID.dropFirst(isLabsID(recordID) ? idPrefix.count : 0)) }
    static func pageURL(_ recordID: String) -> URL? { URL(string: "https://labs.limitlesstcg.com/\(labsID(recordID))/standings") }

    /// Labs tournament and player ids encoded in a Labs player entry id (`labs-0076-445`).
    static func playerRef(entryID: String) -> (tournament: String, player: Int)? {
        guard isLabsID(entryID) else { return nil }
        let parts = entryID.dropFirst(idPrefix.count).split(separator: "-")
        guard parts.count == 2, let player = Int(parts[1]) else { return nil }
        return (String(parts[0]), player)
    }

    static func playerURL(tournament: String, player: Int) -> URL? {
        URL(string: "https://labs.limitlesstcg.com/\(tournament)/player/\(player)")
    }

    enum MatchResult: Sendable { case win, loss, tie }

    struct LabsMatch: Identifiable, Sendable {
        let round: Int
        let isTopCut: Bool
        let opponent: String?
        let opponentDeck: String
        let opponentIcons: [String]
        let opponentRecord: String
        let result: MatchResult
        var id: Int { round }
    }

    private struct APIMatch: Decodable {
        let round: FlexInt?
        let phase: FlexInt?
        let p1_id: FlexInt?
        let p2_id: FlexInt?
        let winner: FlexInt?
        let p1_name: String?
        let p2_name: String?
        let p1_deck_name: String?
        let p2_deck_name: String?
        let p1_icons: String?
        let p2_icons: String?
        let p1_wins: FlexInt?
        let p1_losses: FlexInt?
        let p1_ties: FlexInt?
        let p2_wins: FlexInt?
        let p2_losses: FlexInt?
        let p2_ties: FlexInt?
    }

    private static func record(_ w: FlexInt?, _ l: FlexInt?, _ t: FlexInt?) -> String {
        let wins = w?.value ?? 0, losses = l?.value ?? 0, ties = t?.value ?? 0
        return ties > 0 ? "\(wins)-\(losses)-\(ties)" : "\(wins)-\(losses)"
    }

    /// Every round a player played, latest first.
    static func matches(tournament: String, player: Int) async throws -> [LabsMatch] {
        let rows = try await get("matches?tournamentId=\(tournament)&playerId=\(player)", as: [APIMatch].self)
        return rows.compactMap { m in
            guard let round = m.round?.value else { return nil }
            let isP1 = m.p1_id?.value == player
            let oppID = isP1 ? m.p2_id?.value : m.p1_id?.value
            let winner = m.winner?.value
            let result: MatchResult = if oppID == nil || winner == player { .win } else if winner == 0 { .tie } else { .loss }
            let deck = isP1 ? m.p2_deck_name : m.p1_deck_name
            let icons = (isP1 ? m.p2_icons : m.p1_icons) ?? ""
            return LabsMatch(
                round: round,
                isTopCut: (m.phase?.value ?? 1) >= 3,
                opponent: oppID == nil ? nil : (isP1 ? m.p2_name : m.p1_name),
                opponentDeck: (deck?.isEmpty == false ? deck : nil) ?? (oppID == nil ? "Bye" : "Unknown deck"),
                opponentIcons: icons.split(separator: " ").map(String.init),
                opponentRecord: isP1 ? record(m.p2_wins, m.p2_losses, m.p2_ties) : record(m.p1_wins, m.p1_losses, m.p1_ties),
                result: result
            )
        }
        .sorted { $0.round > $1.round }
    }

    private struct Envelope<T: Decodable>: Decodable {
        let ok: Bool
        let message: T?
    }

    private static func get<T: Decodable>(_ path: String, as type: T.Type) async throws -> T {
        guard let url = URL(string: "\(api)/\(path)") else { throw LimitlessError.badResponse }
        var request = URLRequest(url: url)
        request.timeoutInterval = 30
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw LimitlessError.badResponse
        }
        let envelope = try JSONDecoder().decode(Envelope<T>.self, from: data)
        guard envelope.ok, let message = envelope.message else { throw LimitlessError.badResponse }
        return message
    }

    /// Ints in the Labs API are sometimes numbers and sometimes strings.
    private struct FlexInt: Decodable {
        let value: Int?
        init(from decoder: Decoder) throws {
            let c = try decoder.singleValueContainer()
            if let i = try? c.decode(Int.self) { value = i }
            else if let s = try? c.decode(String.self) { value = Int(s) }
            else { value = nil }
        }
    }

    private struct APITournament: Decodable {
        let id: FlexInt
        let type: String?
        let city: String?
        let name: String?
        let utc_start: String?
        let completed: FlexInt?
    }

    private struct APIStandingRow: Decodable {
        let tp_id: FlexInt?
        let name: String?
        let placement: FlexInt?
        let wins: FlexInt?
        let losses: FlexInt?
        let ties: FlexInt?
        let decklist: FlexInt?
        let deck_name: String?
        let icons: String?
    }

    private struct APICard: Decodable {
        let count: FlexInt?
        let name: String?
        let set: String?
        let number: String?
    }

    private struct APIList: Decodable {
        let pokemon: [APICard]?
        let trainer: [APICard]?
        let energy: [APICard]?
    }

    private static let startFormat: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd HH:mm:ss"
        f.timeZone = TimeZone(identifier: "UTC")
        return f
    }()

    private static func typeLabel(_ type: String?) -> String {
        switch type {
        case "regional": "Regionals"
        case "international": "Internationals"
        case "special": "Special Event"
        case "worlds": "World Championship"
        default: "Championship"
        }
    }

    static func tournaments() async throws -> [LabsTournament] {
        let list = try await get("tournaments", as: [APITournament].self)
        return list.compactMap { t in
            guard let id = t.id.value, let start = t.utc_start, let date = startFormat.date(from: start) else { return nil }
            let padded = String(repeating: "0", count: max(0, 4 - String(id).count)) + String(id)
            let name = (t.name?.isEmpty == false ? t.name : nil) ?? "\(t.city ?? "Tournament") \(typeLabel(t.type))"
            return LabsTournament(id: padded, name: name, date: date, completed: t.completed?.value == 1)
        }
    }

    static func standings(_ labsID: String) async throws -> [LabsStanding] {
        let rows = try await get("standings?tournamentId=\(labsID)&division=MA", as: [APIStandingRow].self)
        return rows.compactMap { r in
            guard let tp = r.tp_id?.value else { return nil }
            return LabsStanding(
                tpID: tp,
                name: r.name ?? "Player",
                placing: r.placement?.value,
                wins: r.wins?.value ?? 0,
                losses: r.losses?.value ?? 0,
                ties: r.ties?.value ?? 0,
                deckName: (r.deck_name?.isEmpty == false ? r.deck_name : nil) ?? "Other",
                icons: (r.icons ?? "").split(separator: " ").map(String.init),
                hasList: r.decklist?.value == 1
            )
        }
        .sorted { ($0.placing ?? Int.max) < ($1.placing ?? Int.max) }
    }

    static func decklist(_ labsID: String, tpID: Int) async throws -> [DeckCard] {
        let list = try await get("decklist?tournamentId=\(labsID)&playerId=\(tpID)", as: APIList.self)
        var cards: [DeckCard] = []
        for (items, category) in [(list.pokemon, CardCategory.pokemon), (list.trainer, .trainer), (list.energy, .energy)] {
            for c in items ?? [] {
                guard let name = c.name else { continue }
                cards.append(DeckCard(count: c.count?.value ?? 1, name: name, set: c.set ?? "", number: c.number ?? "", category: category))
            }
        }
        return cards
    }
}
