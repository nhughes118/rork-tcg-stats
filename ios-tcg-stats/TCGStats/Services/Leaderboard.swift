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
    private static let api = "https://www.pokemon.com/us/play-pokemon/leaderboards/op/api/"
    private static let cacheTTL: TimeInterval = 12 * 3600
    private static let requestedPerPage = 100
    private static let concurrency = 5
    static let userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"

    /// Known season ids (from the site's picker), used when the page can't be read.
    private static let fallbackSeasons = [LeaderboardSeason(id: "60", label: "2026")]

    /// What the live page selected by default, plus the API request its own script made with those values.
    struct PageSetup: Sendable {
        var ready = false
        var capturedURL: String?
        var capturedBody: String?
        var type: String?
        var zone: String?
        var when: String?
        var whenLabel: String?
        var whenOptions: [String: String] = [:]
    }

    /// Request URLs to try, best first: the page's own request, then ones rebuilt from its dropdown values.
    static func templates(_ division: Division, _ setup: PageSetup) -> [URLComponents] {
        var out: [URLComponents] = []
        if let u = setup.capturedURL, let c = URLComponents(string: u) { out.append(c) }
        let type = setup.type ?? "championship"
        let when = setup.when ?? fallbackSeasons[0].id
        func build(product: Bool, when: String?) -> URLComponents {
            var c = URLComponents(string: "\(api)\(division.rawValue)/")!
            var items: [URLQueryItem] = [.init(name: "leaderboard_type", value: type)]
            if let when { items.append(.init(name: "leaderboard_when", value: when)) }
            items.append(.init(name: "leaderboard_who", value: ""))
            if product {
                items.append(.init(name: "product", value: "tcg"))
                items.append(.init(name: "league", value: division.league))
            }
            if let zone = setup.zone, !zone.isEmpty, zone != "All" { items.append(.init(name: "zone", value: zone)) }
            items.append(.init(name: "include", value: "metadata"))
            items.append(.init(name: "format", value: "json"))
            c.queryItems = items
            return c
        }
        out.append(build(product: true, when: when))
        out.append(build(product: false, when: when))
        out.append(build(product: true, when: nil))
        var seen = Set<String>()
        return out.filter { seen.insert($0.string ?? "").inserted }
    }

    /// `template` with its paging replaced. A nil `perPage` keeps the template's own page size.
    static func url(_ template: URLComponents, page: Int, perPage: Int?) -> URL? {
        var c = template
        var items = (c.queryItems ?? []).filter { $0.name != "page" && ($0.name != "per_page" || perPage == nil) }
        if let perPage { items.append(.init(name: "per_page", value: String(perPage))) }
        items.append(.init(name: "page", value: String(page)))
        if !items.contains(where: { $0.name == "format" }) { items.append(.init(name: "format", value: "json")) }
        c.queryItems = items
        return c.url
    }

    // MARK: Cache

    private static func cacheURL(_ division: Division) -> URL {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("leaderboard", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("\(division.rawValue)-v3.json")
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

    /// Every request goes through a hidden web view on the division's real page, so it carries the same
    /// bot-check cookies and dropdown defaults as a visitor's browser.
    private actor FetchSession {
        private var fetcher: LeaderboardWebFetcher?

        func prepare(_ division: Division) async -> PageSetup {
            let f = await LeaderboardWebFetcher()
            fetcher = f
            return await f.open(division.pageURL)
        }

        func text(_ url: URL) async -> String {
            guard let fetcher else { return "" }
            return await fetcher.get(url)
        }

        func close() async {
            if let fetcher { await fetcher.close() }
            fetcher = nil
        }
    }

    private static func fetchPage(_ session: FetchSession, _ template: URLComponents, page: Int, perPage: Int?, attempts: Int = 3) async throws -> Page {
        guard let url = url(template, page: page, perPage: perPage) else { throw Blocked() }
        var attempt = 0
        while true {
            try Task.checkCancellation()
            let text = await session.text(url)
            if text.drop(while: \.isWhitespace).first == "{", let parsed = parse(text, page: page) {
                return parsed
            }
            attempt += 1
            if attempt >= attempts {
                print("[Leaderboard] page \(page) failed: \(url.absoluteString) -> \(text.prefix(160))")
                throw Blocked()
            }
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
        let setup = await session.prepare(division)
        print("[Leaderboard] \(division.rawValue) page ready=\(setup.ready) type=\(setup.type ?? "-") when=\(setup.when ?? "-") (\(setup.whenLabel ?? "-")) zone=\(setup.zone ?? "-")")
        print("[Leaderboard] captured: \(setup.capturedURL ?? "none") body=\(setup.capturedBody?.prefix(200) ?? "none")")
        guard setup.ready else {
            throw LeaderboardError(message: "Pokémon's leaderboard page didn't load. It may be limiting requests, so try again in a minute.")
        }

        var first: Page?
        var chosen: (URLComponents, Int?)?
        var responded = false
        probe: for template in templates(division, setup) {
            // The site's own picker tops out at 100 rows; larger sizes come back empty, so never trust those.
            let sizes: [Int?] = [requestedPerPage, nil]
            for size in sizes {
                do {
                    let page = try await fetchPage(session, template, page: 1, perPage: size, attempts: 2)
                    responded = true
                    print("[Leaderboard] probe \(template.string ?? "") per_page=\(size.map(String.init) ?? "page") -> \(page.records.count) records, total \(page.total ?? -1)")
                    if !page.records.isEmpty { first = page; chosen = (template, size); break probe }
                } catch is CancellationError {
                    throw CancellationError()
                } catch {
                    continue
                }
            }
        }
        guard let first, let chosen else {
            let diag = "page \(setup.capturedURL == nil ? "sent no request" : "request seen"), season \(setup.whenLabel ?? "?")"
            throw LeaderboardError(message: responded
                ? "The \(division.label) leaderboard came back empty (\(diag))."
                : "Pokémon's leaderboard didn't respond. It may be limiting requests, so try again in a minute.")
        }
        let (template, size) = chosen
        let seasonID = template.queryItems?.first(where: { $0.name == "leaderboard_when" })?.value
        let seasonLabel = seasonID.flatMap { setup.whenOptions[$0] } ?? setup.whenLabel
        let perPage = first.records.count
        let total = first.total ?? perPage
        let pageCount = (first.pageCount ?? 0) > 0 ? first.pageCount! : Int((Double(total) / Double(perPage)).rounded(.up))
        print("[Leaderboard] \(division.rawValue): total=\(total) perPage=\(perPage) pages=\(pageCount) season=\(seasonLabel ?? "-")")

        let collector = Collector()
        await onProgress(await collector.add(first.records), total)

        if pageCount > 1 {
            try await withThrowingTaskGroup(of: [LeaderboardPlayer]?.self) { group in
                var pages = Array(2...pageCount).makeIterator()
                let task: @Sendable (Int) async throws -> [LeaderboardPlayer]? = { page in
                    do { return try await fetchPage(session, template, page: page, perPage: size).records }
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
        let index = LeaderboardIndex(division: division.rawValue, fetchedAt: Date(), total: total, complete: failed == 0, players: players, season: seasonLabel)
        print("[Leaderboard] \(division.rawValue): indexed \(players.count)/\(total) (failed pages \(failed))")
        save(index, division)
        return index
    }

    private static func parse(_ text: String, page: Int) -> Page? {
        guard let data = text.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        let board = root["leaderboard"] as? [String: Any]
        let meta = (root["_metadata"] as? [String: Any]) ?? (board?["_metadata"] as? [String: Any])
        let boardMeta = board?["_metadata"] as? [String: Any]
        let records = (board?["records"] as? [[String: Any]]) ?? (root["records"] as? [[String: Any]]) ?? []
        let perPage = int(meta?["per_page"]) ?? int(boardMeta?["per_page"]) ?? records.count
        let players: [LeaderboardPlayer] = records.enumerated().compactMap { i, o in
            guard let name = (str(o["screen_name"]) ?? str(o["name"]))?.replacingOccurrences(of: "\\'", with: "'") else { return nil }
            return LeaderboardPlayer(
                rank: int(o["rank"]) ?? ((page - 1) * perPage + i + 1),
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

extension Division {
    /// The division's public leaderboard page (Masters lives at `tcg-masters/`, the others at their API slug).
    nonisolated var pageURL: URL {
        let slug = self == .masters ? "tcg-masters" : rawValue
        return URL(string: "https://www.pokemon.com/us/play-pokemon/leaderboards/\(slug)/")!
    }
    nonisolated var league: String { String(rawValue.dropFirst(4)) }
}

/// Loads the real leaderboard page, records the API request its own script makes with the default dropdowns,
/// then issues same-origin fetches from inside the page.
@MainActor
final class LeaderboardWebFetcher: NSObject, WKNavigationDelegate {
    private var webView: WKWebView?

    /// Installed before any page script runs: remembers every leaderboard API request and its response.
    private static let hook = """
    (function(){
      if (window.__lbHooked) return; window.__lbHooked = true; window.__lbReqs = [];
      var open = XMLHttpRequest.prototype.open;
      XMLHttpRequest.prototype.open = function(m, u) {
        try {
          var abs = new URL(u, location.href).href;
          if (abs.indexOf('leaderboard_type') >= 0) {
            var entry = {url: abs, body: null}; window.__lbReqs.push(entry);
            var xhr = this;
            this.addEventListener('loadend', function(){ try { entry.body = xhr.responseText || ''; } catch(e) { entry.body = ''; } });
          }
        } catch (e) {}
        return open.apply(this, arguments);
      };
      if (window.fetch) {
        var f = window.fetch;
        window.fetch = function(input, init) {
          var u = typeof input === 'string' ? input : (input && input.url) || '';
          var p = f.apply(this, arguments);
          try {
            if (u.indexOf('leaderboard_type') >= 0 && !(init && init.__lbOwn)) {
              var entry = {url: new URL(u, location.href).href, body: null}; window.__lbReqs.push(entry);
              p.then(function(r){ return r.clone().text(); }).then(function(t){ entry.body = t; }, function(){ entry.body = ''; });
            }
          } catch (e) {}
          return p;
        };
      }
    })();
    """

    private static let discover = """
    const pick = () => {
      const reqs = (window.__lbReqs || []).filter(r => r.url.indexOf('isUserStats') < 0);
      return reqs.length ? reqs[reqs.length - 1] : null;
    };
    for (let i = 0; i < 30; i++) {
      const r = pick();
      if (r && r.body !== null) break;
      await new Promise(res => setTimeout(res, 500));
    }
    const r = pick();
    const val = id => { const s = document.getElementById(id); return s ? s.value : null; };
    const w = document.getElementById('leaderboardWhenFilter');
    const opt = w && w.selectedIndex >= 0 ? w.options[w.selectedIndex] : null;
    return JSON.stringify({
      url: r ? r.url : null,
      body: r && r.body ? r.body.slice(0, 4000) : null,
      type: val('leaderboardTypeFilter'),
      zone: val('leaderboardZoneFilter'),
      when: w ? w.value : null,
      whenLabel: opt ? opt.text.trim() : null,
      options: w ? Array.from(w.options).map(o => [o.value, o.text.trim()]) : []
    });
    """

    func open(_ url: URL) async -> Leaderboard.PageSetup {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default()
        config.userContentController.addUserScript(WKUserScript(source: Self.hook, injectionTime: .atDocumentStart, forMainFrameOnly: true))
        let web = WKWebView(frame: CGRect(x: 0, y: 0, width: 390, height: 844), configuration: config)
        web.alpha = 0.01
        web.isUserInteractionEnabled = false
        web.navigationDelegate = self
        webView = web
        UIApplication.shared.connectedScenes
            .compactMap { ($0 as? UIWindowScene)?.keyWindow }
            .first?
            .addSubview(web)
        web.load(URLRequest(url: url))

        var setup = Leaderboard.PageSetup()
        // The bot check serves an interstitial that reloads itself; the real page has the rankings container.
        let deadline = Date().addingTimeInterval(30)
        while Date() < deadline {
            try? await Task.sleep(for: .milliseconds(600))
            let ready = (try? await web.evaluateJavaScript("!!document.querySelector('#stats-leaderboards-selected-rankings') && !!window.jQuery")) as? Bool
            if ready == true { setup.ready = true; break }
        }
        guard setup.ready else { return setup }

        // Give the page's own script time to issue its first request, retrying if a late reload interrupts us.
        for _ in 0..<3 {
            guard let json = (try? await web.callAsyncJavaScript(Self.discover, arguments: [:], in: nil, contentWorld: .page)) as? String,
                  let data = json.data(using: .utf8),
                  let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                try? await Task.sleep(for: .seconds(1))
                continue
            }
            func s(_ k: String) -> String? { (o[k] as? String).flatMap { $0.isEmpty ? nil : $0 } }
            setup.capturedURL = s("url")
            setup.capturedBody = s("body")
            setup.type = s("type")
            setup.zone = s("zone")
            setup.when = s("when")
            setup.whenLabel = s("whenLabel")
            for pair in (o["options"] as? [[String]]) ?? [] where pair.count == 2 {
                if setup.whenOptions[pair[0]] == nil { setup.whenOptions[pair[0]] = pair[1] }
            }
            break
        }
        return setup
    }

    func get(_ url: URL) async -> String {
        guard let web = webView else { return "" }
        let js = """
        const r = await fetch(url, {credentials: 'include', __lbOwn: true, headers: {'Accept': 'application/json, text/javascript, */*; q=0.01', 'X-Requested-With': 'XMLHttpRequest'}});
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
