import Foundation
import UIKit
import WebKit

nonisolated struct LeaderboardError: LocalizedError, Sendable {
    let message: String
    var errorDescription: String? { message }
}

/// Downloads the Play! Pokémon TCG Championship Points leaderboard. The site has no name search, so the whole
/// division is indexed once (cached on disk for 12 hours) and searched locally.
nonisolated enum Leaderboard {
    static let pageURL = URL(string: "https://www.pokemon.com/us/play-pokemon/leaderboards/tcg-masters/")!
    private static let api = "https://www.pokemon.com/us/play-pokemon/leaderboards/op/api/"
    private static let cacheTTL: TimeInterval = 12 * 3600
    private static let requestedPerPage = 1000
    private static let concurrency = 5
    static let userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"

    static func apiURL(_ division: Division, page: Int, perPage: Int) -> URL {
        URL(string: "\(api)\(division.rawValue)/?per_page=\(perPage)&page=\(page)&include=metadata&format=json")!
    }

    // MARK: Cache

    private static func cacheURL(_ division: Division) -> URL {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("leaderboard", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("\(division.rawValue)-v1.json")
    }

    static func cached(_ division: Division) async -> LeaderboardIndex? {
        let url = cacheURL(division)
        return await Task.detached(priority: .userInitiated) {
            guard let data = try? Data(contentsOf: url) else { return nil }
            return try? JSONDecoder().decode(LeaderboardIndex.self, from: data)
        }.value
    }

    static func isFresh(_ index: LeaderboardIndex) -> Bool {
        index.complete && Date().timeIntervalSince(index.fetchedAt) < cacheTTL
    }

    private static func save(_ index: LeaderboardIndex, _ division: Division) {
        guard let data = try? JSONEncoder().encode(index) else { return }
        try? data.write(to: cacheURL(division), options: .atomic)
    }

    // MARK: Fetching

    private struct Page: Sendable {
        let records: [LeaderboardPlayer]
        let total: Int?
        let pageCount: Int?
    }

    private struct Blocked: Error {}

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

    /// Native requests first; if the bot wall answers with HTML, a hidden web view session takes over.
    private actor FetchSession {
        private var fetcher: LeaderboardWebFetcher?
        private var opening: Task<LeaderboardWebFetcher, Never>?

        func text(_ url: URL) async -> String {
            if fetcher == nil && opening == nil {
                var request = URLRequest(url: url, timeoutInterval: 30)
                request.setValue("application/json, text/plain, */*", forHTTPHeaderField: "Accept")
                request.setValue(Leaderboard.userAgent, forHTTPHeaderField: "User-Agent")
                if let (data, response) = try? await URLSession.shared.data(for: request),
                   (response as? HTTPURLResponse)?.statusCode == 200,
                   let body = String(data: data, encoding: .utf8),
                   body.drop(while: \.isWhitespace).first == "{" {
                    return body
                }
                print("[Leaderboard] native fetch blocked, switching to web view")
            }
            let f = await webFetcher()
            return await f.get(url)
        }

        private func webFetcher() async -> LeaderboardWebFetcher {
            if let fetcher { return fetcher }
            if let opening { return await opening.value }
            let task = Task { @MainActor () -> LeaderboardWebFetcher in
                let f = LeaderboardWebFetcher()
                await f.open(Leaderboard.pageURL)
                return f
            }
            opening = task
            let f = await task.value
            fetcher = f
            return f
        }

        func close() async {
            if let fetcher { await fetcher.close() }
            fetcher = nil
            opening = nil
        }
    }

    private static func fetchPage(_ session: FetchSession, _ division: Division, page: Int) async throws -> Page {
        var attempt = 0
        while true {
            try Task.checkCancellation()
            let text = await session.text(apiURL(division, page: page, perPage: requestedPerPage))
            if text.drop(while: \.isWhitespace).first == "{", let parsed = parse(text) {
                return parsed
            }
            attempt += 1
            if attempt >= 3 { throw Blocked() }
            try await Task.sleep(for: .milliseconds(1200 * attempt))
        }
    }

    /// Indexes `division`. `onProgress` receives the players collected so far (unsorted) and the expected total.
    static func load(
        _ division: Division,
        onProgress: @escaping @Sendable ([LeaderboardPlayer], Int) async -> Void
    ) async throws -> LeaderboardIndex {
        let session = FetchSession()
        do {
            let index = try await run(session, division, onProgress: onProgress)
            await session.close()
            return index
        } catch {
            await session.close()
            throw error
        }
    }

    private static func run(
        _ session: FetchSession,
        _ division: Division,
        onProgress: @escaping @Sendable ([LeaderboardPlayer], Int) async -> Void
    ) async throws -> LeaderboardIndex {
        let first: Page
        do {
            first = try await fetchPage(session, division, page: 1)
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw LeaderboardError(message: "Pokémon's leaderboard didn't respond. It may be limiting requests, so try again in a minute.")
        }
        guard !first.records.isEmpty else {
            throw LeaderboardError(message: "The \(division.label) leaderboard is empty right now.")
        }
        let perPage = first.records.count
        let total = first.total ?? perPage
        let pageCount = (first.pageCount ?? 0) > 0 ? first.pageCount! : Int((Double(total) / Double(perPage)).rounded(.up))
        print("[Leaderboard] \(division.rawValue): total=\(total) perPage=\(perPage) pages=\(pageCount)")

        let collector = Collector()
        await onProgress(await collector.add(first.records), total)

        if pageCount > 1 {
            try await withThrowingTaskGroup(of: [LeaderboardPlayer]?.self) { group in
                var pages = Array(2...pageCount).makeIterator()
                let task: @Sendable (Int) async throws -> [LeaderboardPlayer]? = { page in
                    do { return try await fetchPage(session, division, page: page).records }
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
        let index = LeaderboardIndex(division: division.rawValue, fetchedAt: Date(), total: total, complete: failed == 0, players: players)
        print("[Leaderboard] \(division.rawValue): indexed \(players.count)/\(total) (failed pages \(failed))")
        save(index, division)
        return index
    }

    private static func parse(_ text: String) -> Page? {
        guard let data = text.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        let board = root["leaderboard"] as? [String: Any]
        let meta = (root["_metadata"] as? [String: Any]) ?? (board?["_metadata"] as? [String: Any])
        let boardMeta = board?["_metadata"] as? [String: Any]
        let records = (board?["records"] as? [[String: Any]]) ?? (root["records"] as? [[String: Any]]) ?? []
        let players: [LeaderboardPlayer] = records.compactMap { o in
            guard let rank = int(o["rank"]), let name = str(o["screen_name"]) ?? str(o["name"]) else { return nil }
            return LeaderboardPlayer(
                rank: rank,
                name: name,
                points: int(o["score"]) ?? 0,
                country: str(o["country_name"]),
                countryCode: str(o["country"]),
                inCutoff: (o["in_cutoff"] as? Bool) ?? ((o["in_cutoff"] as? NSNumber)?.boolValue ?? false)
            )
        }
        func metaInt(_ k: String) -> Int? { int(meta?[k]) ?? int(boardMeta?[k]) }
        return Page(records: players, total: metaInt("total_count"), pageCount: metaInt("page_count"))
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

/// Passes the site's bot check in a real browser context, then issues same-origin fetches from the page.
@MainActor
final class LeaderboardWebFetcher: NSObject, WKNavigationDelegate {
    private var webView: WKWebView?

    func open(_ url: URL) async {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default()
        let web = WKWebView(frame: CGRect(x: 0, y: 0, width: 1, height: 1), configuration: config)
        web.alpha = 0.01
        web.isUserInteractionEnabled = false
        web.navigationDelegate = self
        webView = web
        UIApplication.shared.connectedScenes
            .compactMap { ($0 as? UIWindowScene)?.keyWindow }
            .first?
            .addSubview(web)
        web.load(URLRequest(url: url))

        // The bot check serves an interstitial that reloads itself; the real page defines pokemonUI.
        let deadline = Date().addingTimeInterval(25)
        while Date() < deadline {
            try? await Task.sleep(for: .milliseconds(600))
            let ready = (try? await web.evaluateJavaScript("!!(window.pokemonUI || document.querySelector('#stats-leaderboards-selected-rankings'))")) as? Bool
            if ready == true { return }
        }
    }

    func get(_ url: URL) async -> String {
        guard let web = webView else { return "" }
        let js = """
        const r = await fetch(url, {credentials: 'include', headers: {'Accept': 'application/json, text/plain, */*', 'X-Requested-With': 'XMLHttpRequest'}});
        return await r.text();
        """
        let result = try? await web.callAsyncJavaScript(js, arguments: ["url": url.absoluteString], in: nil, contentWorld: .page)
        return (result as? String) ?? ""
    }

    func close() {
        webView?.stopLoading()
        webView?.removeFromSuperview()
        webView = nil
    }
}
