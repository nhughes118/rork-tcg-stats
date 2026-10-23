import Foundation

/// One finisher in a tournament's full standings, with or without a published decklist.
nonisolated struct StandingRow: Codable, Hashable, Sendable {
    let placing: Int?
    let name: String
    let deckName: String
    let deckIcons: [String]
    let wins: Int
    let losses: Int
    let ties: Int
    /// limitlesstcg.com decklist id (official events); fetched on demand.
    let listID: String?

    func asEntry(tournamentID: String, cards: [DeckCard] = []) -> PlayerEntry {
        PlayerEntry(
            id: "\(tournamentID)-standing-\(placing.map(String.init) ?? name)",
            name: name,
            placing: placing,
            wins: wins,
            losses: losses,
            ties: ties,
            deckName: deckName,
            deckIcons: deckIcons,
            cards: cards,
            cardKeys: Set(cards.map(\.key))
        )
    }
}

/// Full tournament standings (every player, including those without a decklist),
/// loaded lazily for the tournament detail search and cached on disk.
nonisolated enum TournamentStandings {
    private static func file(_ record: TournamentRecord) -> URL {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("standings-v1", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("\(record.source.rawValue)-\(record.id).json")
    }

    static func load(_ record: TournamentRecord) async throws -> [StandingRow] {
        let url = file(record)
        if let data = try? Data(contentsOf: url), let rows = try? JSONDecoder().decode([StandingRow].self, from: data) {
            return rows
        }
        let rows = try await fetch(record)
        if let data = try? JSONEncoder().encode(rows) { try? data.write(to: url, options: .atomic) }
        return rows
    }

    private static func fetch(_ record: TournamentRecord) async throws -> [StandingRow] {
        switch record.source {
        case .official:
            if LabsLimitless.isLabsID(record.id) {
                return try await LabsLimitless.standings(LabsLimitless.labsID(record.id)).map {
                    StandingRow(placing: $0.placing, name: $0.name, deckName: $0.deckName, deckIcons: $0.icons,
                                wins: $0.wins, losses: $0.losses, ties: $0.ties, listID: nil)
                }
            }
            let html = try await OfficialLimitless.fetchPage("/tournaments/\(record.id)")
            return OfficialLimitless.parseStandings(html).map {
                StandingRow(placing: $0.rank, name: $0.name, deckName: $0.deck, deckIcons: $0.icons,
                            wins: 0, losses: 0, ties: 0, listID: $0.listID)
            }
        case .online:
            guard let url = URL(string: "\(LimitlessClient.base)/tournaments/\(record.id)/standings") else {
                throw LimitlessError.badResponse
            }
            var request = URLRequest(url: url)
            request.timeoutInterval = 30
            request.setValue("application/json", forHTTPHeaderField: "Accept")
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                throw LimitlessError.badResponse
            }
            return try JSONDecoder().decode([APIStanding].self, from: data).map { s in
                StandingRow(
                    placing: s.placing,
                    name: s.name ?? s.player ?? "Player",
                    deckName: s.deck?.name ?? "Other",
                    deckIcons: s.deck?.icons ?? [],
                    wins: s.record?.wins ?? 0,
                    losses: s.record?.losses ?? 0,
                    ties: s.record?.ties ?? 0,
                    listID: nil
                )
            }
        }
    }

    /// Fetches a decklist that wasn't part of the dataset (scraped official finishers beyond the top 64).
    static func decklist(_ listID: String) async throws -> [DeckCard] {
        OfficialLimitless.parseDecklist(try await OfficialLimitless.fetchPage("/decks/list/\(listID)"))
    }
}
