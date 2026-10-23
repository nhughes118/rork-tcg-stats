import Foundation

nonisolated enum LimitlessError: LocalizedError {
    case badResponse
    case noData

    var errorDescription: String? {
        switch self {
        case .badResponse: "Limitless TCG didn't respond as expected. Please try again."
        case .noData: "No recent tournament decklists were found."
        }
    }
}

nonisolated struct TournamentSummary: Sendable {
    let id: String
    let name: String
    let date: Date
    let players: Int
}

/// Fetches recent Standard tournaments and decklists, either from the
/// play.limitlesstcg.com API (online events) or by scraping
/// limitlesstcg.com pages (official in-person events).
nonisolated enum LimitlessClient {
    static let base = "https://play.limitlesstcg.com/api"
    static let weeksBack = 12
    static let perWeek = 3
    static let minPlayers = 32
    /// Official events list every finisher; decklists are fetched for the top N only.
    static let officialListLimit = 64
    /// Maximum official events (homepage recent + older completed) per load.
    static let officialMaxEvents = 8

    private static let isoFractional: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()
    private static let iso: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()

    static func parseDate(_ s: String) -> Date? {
        isoFractional.date(from: s) ?? iso.date(from: s)
    }

    private static func cacheDirectory(_ source: DataSource) -> URL {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("tournaments", isDirectory: true)
            .appendingPathComponent("\(source.rawValue)-v2", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    private static func get(_ path: String) async throws -> Data {
        guard let url = URL(string: base + path) else { throw LimitlessError.badResponse }
        var request = URLRequest(url: url)
        request.timeoutInterval = 30
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw LimitlessError.badResponse
        }
        return data
    }

    /// Picks the largest finished tournaments from each recent week.
    static func select(_ list: [TournamentSummary], now: Date) -> [TournamentSummary] {
        var calendar = Calendar(identifier: .iso8601)
        calendar.timeZone = .current
        let oldest = now.addingTimeInterval(-Double(weeksBack) * 7 * 86_400)
        let finishedBefore = now.addingTimeInterval(-18 * 3600)
        var byWeek: [Date: [TournamentSummary]] = [:]
        for t in list where t.players >= minPlayers && t.date < finishedBefore && t.date >= oldest {
            let week = calendar.dateInterval(of: .weekOfYear, for: t.date)?.start ?? t.date
            byWeek[week, default: []].append(t)
        }
        return byWeek.values.flatMap { items in
            items.sorted { $0.players > $1.players }.prefix(perWeek)
        }
    }

    private static func cachedRecord(_ t: TournamentSummary, _ source: DataSource) -> TournamentRecord? {
        let file = cacheDirectory(source).appendingPathComponent("\(t.id).json")
        guard let data = try? Data(contentsOf: file) else { return nil }
        return try? JSONDecoder().decode(TournamentRecord.self, from: data)
    }

    private static func saveCache(_ record: TournamentRecord) {
        let file = cacheDirectory(record.source).appendingPathComponent("\(record.id).json")
        if let encoded = try? JSONEncoder().encode(record) {
            try? encoded.write(to: file, options: .atomic)
        }
    }

    // MARK: Online (play.limitlesstcg.com API)

    private static func onlineRecord(for t: TournamentSummary) async throws -> TournamentRecord {
        if let cached = cachedRecord(t, .online) {
            return cached
        }
        let data = try await get("/tournaments/\(t.id)/standings")
        let standings = try JSONDecoder().decode([APIStanding].self, from: data)
        let entries: [PlayerEntry] = standings.compactMap { s in
            guard let list = s.decklist else { return nil }
            var cards: [DeckCard] = []
            for (items, category) in [(list.pokemon, CardCategory.pokemon), (list.trainer, .trainer), (list.energy, .energy)] {
                for c in items ?? [] {
                    cards.append(DeckCard(count: c.count, name: c.name, set: c.set ?? "", number: c.number ?? "", category: category))
                }
            }
            guard !cards.isEmpty else { return nil }
            return PlayerEntry(
                id: s.player ?? UUID().uuidString,
                name: s.name ?? s.player ?? "Player",
                placing: s.placing,
                wins: s.record?.wins ?? 0,
                losses: s.record?.losses ?? 0,
                ties: s.record?.ties ?? 0,
                deckName: s.deck?.name ?? "Other",
                deckIcons: s.deck?.icons ?? [],
                cards: cards,
                cardKeys: Set(cards.map(\.key))
            )
        }
        return TournamentRecord(id: t.id, name: t.name, date: t.date, players: t.players, entries: entries, source: .online)
    }

    private static func loadOnline(progress: @escaping @Sendable (Double) -> Void) async throws -> CardDataset {
        let now = Date()
        progress(0.03)
        let listData = try await get("/tournaments?game=PTCG&format=STANDARD&limit=1000")
        let list = try JSONDecoder().decode([APITournament].self, from: listData)
        let summaries = list.compactMap { api -> TournamentSummary? in
            guard let date = parseDate(api.date) else { return nil }
            return TournamentSummary(id: api.id, name: api.name, date: date, players: api.players)
        }
        return try await buildDataset(selected: select(summaries, now: now), now: now, progress: progress) { summary in
            try await onlineRecord(for: summary)
        }
    }

    // MARK: Official via Limitless Labs (full standings + every player's decklist)

    /// Parallel decklist requests against Limitless Labs.
    static let labsConcurrency = 24

    /// Shares identical card lines across decklists; full events hold thousands of near-identical lists.
    private static func intern(_ records: [TournamentRecord]) -> [TournamentRecord] {
        var cards: [DeckCard: DeckCard] = [:]
        return records.map { r in
            let entries = r.entries.map { e in
                let shared = e.cards.map { c in
                    if let existing = cards[c] { return existing }
                    cards[c] = c
                    return c
                }
                return PlayerEntry(id: e.id, name: e.name, placing: e.placing, wins: e.wins, losses: e.losses, ties: e.ties,
                                   deckName: e.deckName, deckIcons: e.deckIcons, cards: shared, cardKeys: e.cardKeys)
            }
            return TournamentRecord(id: r.id, name: r.name, date: r.date, players: r.players, entries: entries, source: r.source)
        }
    }

    private static func labsRecord(_ t: LabsLimitless.LabsTournament, progress: @escaping @Sendable (Int, Int) -> Void) async throws -> TournamentRecord? {
        let recordID = LabsLimitless.idPrefix + t.id
        let summary = TournamentSummary(id: recordID, name: t.name, date: t.date, players: 0)
        if let cached = cachedRecord(summary, .official) { return cached }
        let standings = try await LabsLimitless.standings(t.id)
        guard !standings.isEmpty else { return nil }
        let withLists = standings.filter(\.hasList)

        var lists: [Int: [DeckCard]] = [:]
        var done = 0
        var failed = 0
        await withTaskGroup(of: (Int, [DeckCard]?).self) { group in
            var iterator = withLists.makeIterator()
            func addNext() {
                guard let s = iterator.next() else { return }
                group.addTask {
                    for _ in 0..<3 {
                        if let cards = try? await LabsLimitless.decklist(t.id, tpID: s.tpID) { return (s.tpID, cards) }
                    }
                    return (s.tpID, nil)
                }
            }
            for _ in 0..<labsConcurrency { addNext() }
            for await (tp, cards) in group {
                done += 1
                progress(done, withLists.count)
                if let cards { if !cards.isEmpty { lists[tp] = cards } } else { failed += 1 }
                addNext()
            }
        }

        let entries: [PlayerEntry] = withLists.compactMap { s in
            guard let cards = lists[s.tpID] else { return nil }
            return PlayerEntry(
                id: "\(recordID)-\(s.tpID)",
                name: s.name,
                placing: s.placing,
                wins: s.wins,
                losses: s.losses,
                ties: s.ties,
                deckName: s.deckName,
                deckIcons: s.icons,
                cards: cards,
                cardKeys: Set(cards.map(\.key))
            )
        }
        guard !entries.isEmpty else { return nil }
        let record = TournamentRecord(id: recordID, name: t.name, date: t.date, players: standings.count, entries: entries, source: .official)
        // Cache only (nearly) complete events so a flaky load is retried next time.
        if failed <= withLists.count / 50 { saveCache(record) }
        return record
    }

    private static func loadLabs(progress: @escaping @Sendable (Double) -> Void) async -> [TournamentRecord] {
        let now = Date()
        progress(0.02)
        let oldest = now.addingTimeInterval(-Double(weeksBack) * 7 * 86_400)
        guard let all = try? await LabsLimitless.tournaments() else { return [] }
        let selected = all
            .filter { $0.completed && $0.date >= oldest && $0.date <= now }
            .sorted { $0.date > $1.date }
            .prefix(officialMaxEvents)
        guard !selected.isEmpty else { return [] }
        var records: [TournamentRecord] = []
        let count = Double(selected.count)
        for (index, t) in selected.enumerated() {
            let base = 0.04 + 0.96 * Double(index) / count
            let record = try? await labsRecord(t) { done, total in
                progress(base + 0.96 / count * Double(done) / Double(max(total, 1)))
            }
            if let record { records.append(record) }
            progress(0.04 + 0.96 * Double(index + 1) / count)
        }
        return records
    }

    private static func loadOfficial(progress: @escaping @Sendable (Double) -> Void) async throws -> CardDataset {
        let labs = await loadLabs(progress: progress)
        if !labs.isEmpty { return CardDataset(tournaments: labs, now: Date()) }
        return try await loadOfficialScraped(progress: progress)
    }

    // MARK: Official fallback (limitlesstcg.com scraping, top finishers only)

    private static func loadOfficialScraped(progress: @escaping @Sendable (Double) -> Void) async throws -> CardDataset {
        let now = Date()
        progress(0.03)
        // Primary: the homepage "Recent Tournaments" block. Secondary: older completed events for trend depth.
        async let homeTask = try? OfficialLimitless.fetchPage("/")
        async let listTask = try? OfficialLimitless.fetchPage("/tournaments?game=PTCG&format=STANDARD")
        let homeHTML = await homeTask
        let listHTML = await listTask
        let recent = homeHTML.map(OfficialLimitless.parseHomeRecent) ?? []
        let completed = listHTML.map(OfficialLimitless.parseList) ?? []

        let oldest = now.addingTimeInterval(-Double(weeksBack) * 7 * 86_400)
        var seen: Set<String> = []
        var selected: [TournamentSummary] = []
        for t in recent where t.date <= now && seen.insert(t.id).inserted {
            selected.append(TournamentSummary(id: t.id, name: t.name, date: t.date, players: t.players))
        }
        for t in completed.sorted(by: { $0.date > $1.date }) where selected.count < officialMaxEvents {
            guard t.date >= oldest, t.date <= now, seen.insert(t.id).inserted else { continue }
            selected.append(TournamentSummary(id: t.id, name: t.name, date: t.date, players: t.players))
        }
        guard !selected.isEmpty else { throw LimitlessError.badResponse }

        var records: [TournamentRecord] = []
        var pending: [Int] = []
        for (i, t) in selected.enumerated() {
            if let cached = cachedRecord(t, .official) {
                records.append(cached)
            } else {
                pending.append(i)
            }
        }

        // Standings pages give every finisher's deck and decklist link.
        var standingsByIndex: [Int: [OfficialLimitless.OfficialStanding]] = [:]
        var standingsDone = 0
        var index = 0
        while index < pending.count {
            let batch = Array(pending[index..<min(index + 5, pending.count)])
            index += 5
            await withTaskGroup(of: (Int, [OfficialLimitless.OfficialStanding])?.self) { group in
                for idx in batch {
                    group.addTask {
                        guard let page = try? await OfficialLimitless.fetchPage("/tournaments/\(selected[idx].id)") else { return nil }
                        return (idx, OfficialLimitless.parseStandings(page))
                    }
                }
                for await result in group {
                    standingsDone += 1
                    progress(0.05 + 0.15 * Double(standingsDone) / Double(max(pending.count, 1)))
                    if let result, !result.1.isEmpty { standingsByIndex[result.0] = result.1 }
                }
            }
        }

        // Decklists for the top finishers of each event.
        struct Job: Sendable {
            let key: String
            let path: String
        }
        var jobs: [Job] = []
        for (idx, standings) in standingsByIndex {
            for s in standings.filter({ $0.listID != nil }).prefix(officialListLimit) {
                jobs.append(Job(key: "\(idx):\(s.rank)", path: "/decks/list/\(s.listID ?? "")"))
            }
        }
        var lists: [String: [DeckCard]] = [:]
        var decksDone = 0
        let totalJobs = jobs.count
        index = 0
        while index < jobs.count {
            let batch = Array(jobs[index..<min(index + 8, jobs.count)])
            index += 8
            await withTaskGroup(of: (String, [DeckCard])?.self) { group in
                for job in batch {
                    group.addTask {
                        guard let page = try? await OfficialLimitless.fetchPage(job.path) else { return nil }
                        let cards = OfficialLimitless.parseDecklist(page)
                        return cards.isEmpty ? nil : (job.key, cards)
                    }
                }
                for await result in group {
                    decksDone += 1
                    progress(0.20 + 0.80 * Double(decksDone) / Double(max(totalJobs, 1)))
                    if let result { lists[result.0] = result.1 }
                }
            }
        }

        for (idx, standings) in standingsByIndex {
            let t = selected[idx]
            let entries: [PlayerEntry] = standings.filter { $0.listID != nil }.prefix(officialListLimit).compactMap { s in
                guard let cards = lists["\(idx):\(s.rank)"], !cards.isEmpty else { return nil }
                return PlayerEntry(
                    id: "\(t.id)-\(s.rank)",
                    name: s.name,
                    placing: s.rank,
                    wins: 0,
                    losses: 0,
                    ties: 0,
                    deckName: s.deck,
                    deckIcons: s.icons,
                    cards: cards,
                    cardKeys: Set(cards.map(\.key))
                )
            }
            guard !entries.isEmpty else { continue }
            records.append(TournamentRecord(id: t.id, name: t.name, date: t.date, players: t.players, entries: entries, source: .official))
        }
        records.forEach(saveCache)
        guard !records.isEmpty else { throw LimitlessError.noData }
        return CardDataset(tournaments: records, now: now)
    }

    // MARK: Shared assembly

    private static func buildDataset(
        selected: [TournamentSummary],
        now: Date,
        progress: @escaping @Sendable (Double) -> Void,
        fetch: @escaping @Sendable (TournamentSummary) async throws -> TournamentRecord
    ) async throws -> CardDataset {
        guard !selected.isEmpty else { throw LimitlessError.noData }

        var records: [TournamentRecord] = []
        var cached: [TournamentRecord] = []
        var pending: [TournamentSummary] = []
        for t in selected {
            if let record = cachedRecord(t, .online) {
                cached.append(record)
            } else {
                pending.append(t)
            }
        }

        var done = 0
        let total = max(pending.count, 1)
        var index = 0
        while index < pending.count {
            let batch = Array(pending[index..<min(index + 6, pending.count)])
            index += 6
            await withTaskGroup(of: TournamentRecord?.self) { group in
                for t in batch {
                    group.addTask { try? await fetch(t) }
                }
                for await r in group {
                    done += 1
                    progress(0.05 + 0.95 * Double(done) / Double(total))
                    if let r, !r.entries.isEmpty { cached.append(r) }
                }
            }
        }
        cached.forEach(saveCache)
        guard !cached.isEmpty else { throw LimitlessError.noData }
        return CardDataset(tournaments: cached, now: now)
    }

    /// Loads the dataset, reporting progress between 0 and 1.
    static func loadDataset(source: DataSource, progress: @escaping @Sendable (Double) -> Void) async throws -> CardDataset {
        let scaled: @Sendable (Double) -> Void = { progress($0 * 0.85) }
        let loaded: CardDataset = switch source {
        case .online: try await loadOnline(progress: scaled)
        case .official: try await loadOfficial(progress: scaled)
        }
        let base = CardDataset(tournaments: intern(loaded.tournaments), now: loaded.generatedAt)
        // Split Pokémon names that cover several distinct cards (e.g. Fire vs Darkness Chi-Yu).
        let variants = await CardVariants.resolve(base.tournaments) { progress(0.85 + 0.15 * $0) }
        progress(1)
        guard !variants.isEmpty else { return base }
        return CardDataset(tournaments: base.tournaments, now: base.generatedAt, variants: variants)
    }
}
