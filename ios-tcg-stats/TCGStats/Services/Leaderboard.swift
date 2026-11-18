import Foundation
import UIKit

nonisolated struct LeaderboardError: LocalizedError, Sendable {
    let message: String
    var errorDescription: String? { message }
}

extension Game {
    /// The game's leaderboard on Pokémon's championships site.
    nonisolated func pageURL(division: Division) -> URL {
        URL(string: "https://championships.pokemon.com/en-us/competitors/leaderboards/?pillar=\(siteSlug)&division=\(divisionSlug(division))")!
    }
}

/// Downloads the Play! Pokémon Championship Points leaderboard from the championships site's API
/// (api.play.pokemon.com), which is open — no bot check. The site has no name search, so the whole
/// board is indexed once (cached on disk for 12 hours) and searched locally.
nonisolated enum Leaderboard {
    /// The championships site's content store: source of the live region, division and season filters.
    private static let store = URL(string: "https://championships.pokemon.com/api/1/site/content_store/item.json?url=/site/website/en-us/competitors/leaderboards/index.xml&flatten=true")!
    private static let api = "https://api.play.pokemon.com/services/spar/leaderboards/"
    private static let cacheTTL: TimeInterval = 12 * 3600
    private static let periodsTTL: TimeInterval = 24 * 3600
    private static let perPage = 200
    private static let concurrency = 8
    static let userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"

    /// Season fallback if the championships site can't be read (2027 Worlds qualification).
    private static let fallbackPeriod = LeaderboardPeriod(guid: "1c13d02f09f3f0713f7e41986bef2f36", label: "2027 World Championships")

    /// A championship-points season/cycle as listed in the championships site's filters.
    nonisolated struct LeaderboardPeriod: Sendable, Hashable {
        let guid: String
        let label: String
    }

    private struct Board: Sendable {
        let players: [LeaderboardPlayer]
        let total: Int
    }

    private actor Collector {
        private var byID: [String: LeaderboardPlayer] = [:]
        private(set) var failedPages = 0
        func add(_ records: [LeaderboardPlayer]) -> [LeaderboardPlayer] {
            for r in records { byID[r.id] = r }
            return Array(byID.values)
        }
        func fail() { failedPages += 1 }
        func all() -> [LeaderboardPlayer] { Array(byID.values) }
    }

    // MARK: Cache

    private static var cacheDir: URL {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("leaderboard", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    private static func cacheURL(_ game: Game, _ division: Division, _ region: LeaderboardRegion) -> URL {
        let suffix = region == .global ? "" : "-\(region.rawValue.lowercased())"
        return cacheDir.appendingPathComponent("\(game.apiSlug)-\(game.divisionSlug(division))\(suffix)-v4.json")
    }

    static func cached(_ game: Game, _ division: Division, _ region: LeaderboardRegion) async -> LeaderboardIndex? {
        let url = cacheURL(game, division, region)
        return await Task.detached(priority: .userInitiated) {
            guard let data = try? Data(contentsOf: url),
                  let index = try? JSONDecoder().decode(LeaderboardIndex.self, from: data),
                  index.division == game.divisionSlug(division),
                  (index.game ?? Game.tcg.rawValue) == game.rawValue,
                  (index.region ?? LeaderboardRegion.global.rawValue) == region.rawValue else { return nil }
            return index
        }.value
    }

    static func isFresh(_ index: LeaderboardIndex) -> Bool {
        index.complete && Date().timeIntervalSince(index.fetchedAt) < cacheTTL
    }

    private static func save(_ index: LeaderboardIndex, _ game: Game, _ division: Division, _ region: LeaderboardRegion) {
        guard let data = try? JSONEncoder().encode(index) else { return }
        try? data.write(to: cacheURL(game, division, region), options: .atomic)
    }

    // MARK: Season

    private struct PeriodDTO: Codable {
        let guid: String
        let name: String
    }

    private struct PeriodsCache: Codable {
        let fetchedAt: Date
        let periods: [PeriodDTO]
    }

    /// The championships site's own default season (its "year" dropdown, which mixes whole seasons
    /// and single events): the first entry in the store's list — currently 2027 Worlds qualification.
    private static func currentPeriod() async -> LeaderboardPeriod {
        let file = cacheDir.appendingPathComponent("periods-v2.json")
        if let data = try? Data(contentsOf: file),
           let cache = try? JSONDecoder().decode(PeriodsCache.self, from: data),
           Date().timeIntervalSince(cache.fetchedAt) < periodsTTL,
           let first = cache.periods.first {
            return LeaderboardPeriod(guid: first.guid, label: first.name)
        }
        guard let (data, response) = try? await URLSession.shared.data(from: store),
              let http = response as? HTTPURLResponse, http.statusCode == 200,
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let raw = findString("periodsjson_t", in: root),
              let periodsRoot = (try? JSONSerialization.jsonObject(with: Data(raw.utf8))) as? [String: Any],
              let results = periodsRoot["results"] as? [[String: Any]] else {
            return fallbackPeriod
        }
        let periods = results.compactMap { o -> LeaderboardPeriod? in
            guard let guid = str(o["guid"]), let name = str(o["name"]) else { return nil }
            return LeaderboardPeriod(guid: guid, label: name)
        }
        guard !periods.isEmpty else { return fallbackPeriod }
        if let encoded = try? JSONEncoder().encode(PeriodsCache(fetchedAt: Date(), periods: periods.map { PeriodDTO(guid: $0.guid, name: $0.label) })) {
            try? encoded.write(to: file, options: .atomic)
        }
        return periods[0]
    }

    /// Depth-first search for a string field somewhere in the content-store payload.
    private static func findString(_ key: String, in value: Any) -> String? {
        if let dict = value as? [String: Any] {
            for (k, v) in dict {
                if k == key, let s = v as? String { return s }
                if let found = findString(key, in: v) { return found }
            }
        } else if let array = value as? [Any] {
            for v in array {
                if let found = findString(key, in: v) { return found }
            }
        }
        return nil
    }

    // MARK: Fetching

    private static func fetchPage(_ game: Game, _ division: Division, _ region: LeaderboardRegion, period: String, page: Int) async throws -> Board {
        var c = URLComponents(string: api)!
        c.queryItems = [
            .init(name: "product", value: game.apiSlug),
            .init(name: "region", value: region.rawValue),
            .init(name: "region_type", value: region.type),
            .init(name: "division", value: game.divisionSlug(division)),
            .init(name: "period", value: period),
            .init(name: "page_size", value: String(perPage)),
            .init(name: "page", value: String(page)),
            .init(name: "point_type", value: "championship"),
            .init(name: "sort_by", value: "ranking_order:asc"),
        ]
        guard let url = c.url else { throw LeaderboardError(message: "Pokémon's leaderboard didn't respond. Check your connection and try again.") }
        var attempt = 0
        while true {
            do {
                var request = URLRequest(url: url)
                request.timeoutInterval = 30
                request.setValue("application/json", forHTTPHeaderField: "Accept")
                request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
                let (data, response) = try await URLSession.shared.data(for: request)
                guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
                    throw LeaderboardError(message: "status \(attempt)")
                }
                guard let board = parse(data) else {
                    throw LeaderboardError(message: "Pokémon's leaderboard didn't respond as expected.")
                }
                return board
            } catch is CancellationError {
                throw CancellationError()
            } catch let e as LeaderboardError where e.message.contains("expected") {
                throw e
            } catch {
                attempt += 1
                if attempt >= 3 {
                    throw LeaderboardError(message: "Pokémon's leaderboard didn't respond. Check your connection and try again.")
                }
                try? await Task.sleep(for: .milliseconds(800 * attempt))
            }
        }
    }

    private static func parse(_ data: Data) -> Board? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let results = root["results"] as? [[String: Any]] else { return nil }
        let players: [LeaderboardPlayer] = results.enumerated().compactMap { i, o in
            guard let name = str(o["display_name"]) else { return nil }
            return LeaderboardPlayer(
                rank: int(o["rank"]) ?? i + 1,
                name: name,
                points: int(o["primary_point_total"]) ?? 0,
                country: str(o["player_country"]),
                countryCode: str(o["player_country_code"]),
                inCutoff: false
            )
        }
        return Board(players: players, total: int(root["count"]) ?? players.count)
    }

    private static func str(_ v: Any?) -> String? {
        guard let s = (v as? String)?.trimmingCharacters(in: .whitespacesAndNewlines), !s.isEmpty, s != "null" else { return nil }
        return s
    }

    private static func int(_ v: Any?) -> Int? {
        if let n = v as? NSNumber { return n.intValue }
        if let s = v as? String { return Int(s) ?? Double(s).map { Int($0) } }
        return nil
    }

    /// Indexes the game's board for `division`. `onProgress` receives the players collected so far (unsorted) and the expected total.
    static func load(
        _ game: Game,
        _ division: Division,
        region: LeaderboardRegion,
        onProgress: @escaping @Sendable ([LeaderboardPlayer], Int) async -> Void
    ) async throws -> LeaderboardIndex {
        let period = await currentPeriod()
        let first = try await fetchPage(game, division, region, period: period.guid, page: 1)
        guard !first.players.isEmpty else {
            let scope = game == .go ? game.label : "\(division.label) \(region == .global ? "" : "\(region.label) ")"
            throw LeaderboardError(message: "The \(scope)leaderboard came back empty.")
        }
        let total = first.total
        let pageCount = max(1, Int((Double(total) / Double(perPage)).rounded(.up)))
        print("[Leaderboard] \(game.rawValue)/\(game.divisionSlug(division)): total=\(total) pages=\(pageCount) season=\(period.label)")

        let collector = Collector()
        await onProgress(await collector.add(first.players), total)

        if pageCount > 1 {
            try await withThrowingTaskGroup(of: [LeaderboardPlayer]?.self) { group in
                var pages = Array(2...pageCount).makeIterator()
                let task: @Sendable (Int) async throws -> [LeaderboardPlayer]? = { page in
                    do { return try await fetchPage(game, division, region, period: period.guid, page: page).players }
                    catch is CancellationError { throw CancellationError() }
                    catch { return nil }
                }
                for _ in 0..<concurrency {
                    if let page = pages.next() { group.addTask { try await task(page) } }
                }
                while let result = try await group.next() {
                    if let records = result {
                        await onProgress(await collector.add(records), total)
                    } else {
                        await collector.fail()
                    }
                    if let page = pages.next() { group.addTask { try await task(page) } }
                }
            }
        }

        let players = await collector.all().sorted { $0.rank < $1.rank }
        let failed = await collector.failedPages
        let index = LeaderboardIndex(division: game.divisionSlug(division), fetchedAt: Date(), total: total, complete: failed == 0, players: players, season: period.label, region: region.rawValue, game: game.rawValue)
        print("[Leaderboard] \(game.rawValue)/\(game.divisionSlug(division)): indexed \(players.count)/\(total) (failed pages \(failed))")
        save(index, game, division, region)
        return index
    }

    // MARK: Search

    static func normalize(_ s: String) -> String {
        s.folding(options: [.diacriticInsensitive, .caseInsensitive, .widthInsensitive], locale: Locale(identifier: "en_US"))
    }

    private static func tokens(_ s: String) -> [String] {
        normalize(s).split(whereSeparator: { !$0.isLetter && !$0.isNumber }).map(String.init)
    }

    /// Players whose name contains every token of `query`, best rank first.
    static func search(_ players: [LeaderboardPlayer], normalized: [String], query: String, limit: Int = 200) -> [LeaderboardPlayer] {
        let q = tokens(query)
        guard !q.isEmpty else { return [] }
        var out: [LeaderboardPlayer] = []
        for i in players.indices {
            let n = i < normalized.count ? normalized[i] : normalize(players[i].name)
            if q.allSatisfy({ n.contains($0) }) { out.append(players[i]) }
        }
        out.sort { $0.rank < $1.rank }
        return Array(out.prefix(limit))
    }
}
