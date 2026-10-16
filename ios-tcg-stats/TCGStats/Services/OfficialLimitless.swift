import Foundation

/// Scrapes limitlesstcg.com for in-person ("official") tournaments:
/// the homepage "Recent Tournaments" list, the completed-tournaments table,
/// standings pages and per-player decklist pages.
nonisolated enum OfficialLimitless {
    static let site = "https://limitlesstcg.com"

    struct ListedTournament: Sendable, Hashable {
        let id: String
        let name: String
        let date: Date
        let players: Int
    }

    struct OfficialStanding: Sendable {
        let rank: Int
        let name: String
        let deck: String
        let icons: [String]
        let listID: String?
    }

    private static let isoDay: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        f.timeZone = TimeZone(identifier: "UTC")
        return f
    }()

    private static let longDay: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "d MMMM yyyy"
        f.timeZone = TimeZone(identifier: "UTC")
        return f
    }()

    // MARK: Fetching

    static func fetchPage(_ path: String) async throws -> String {
        guard let url = URL(string: path.hasPrefix("http") ? path : site + path) else {
            throw LimitlessError.badResponse
        }
        var request = URLRequest(url: url)
        request.timeoutInterval = 30
        request.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko)", forHTTPHeaderField: "User-Agent")
        request.setValue("text/html", forHTTPHeaderField: "Accept")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw LimitlessError.badResponse
        }
        return String(decoding: data, as: UTF8.self)
    }

    // MARK: Parsing

    /// The "Recent Tournaments" block on the limitlesstcg.com homepage.
    static func parseHomeRecent(_ html: String) -> [ListedTournament] {
        guard let start = html.range(of: "<h2>Recent Tournaments</h2>") else { return [] }
        var section = String(html[start.upperBound...])
        if let end = section.range(of: "<h2>") {
            section = String(section[..<end.lowerBound])
        }
        var result: [ListedTournament] = []
        for block in section.components(separatedBy: "<a href=\"/tournaments/").dropFirst() {
            guard let id = match(#"^(\d+)""#, block),
                  let name = match(#"font-bold">([^<]*)<"#, block)
            else { continue }
            if let format = match(#"class="format"[^>]*alt="([a-z-]+)""#, block), format != "standard" { continue }
            guard let day = match(#"(\d{1,2})(?:st|nd|rd|th)\s+[A-Za-z]+\s+\d{4}"#, block),
                  let rest = match(#"\d{1,2}(?:st|nd|rd|th)\s+([A-Za-z]+\s+\d{4})"#, block),
                  let date = longDay.date(from: "\(day) \(rest)")
            else { continue }
            let players = Int(match(#"(\d+)\s+Players"#, block) ?? "") ?? 0
            result.append(ListedTournament(id: id, name: decodeEntities(name.trimmingCharacters(in: .whitespacesAndNewlines)), date: date, players: players))
        }
        return result
    }

    /// Rows of the completed-tournaments table (`/tournaments`).
    static func parseList(_ html: String) -> [ListedTournament] {
        rows(in: html).compactMap { row in
            guard let dateText = match(#"data-date="([\d-]+)""#, row),
                  let date = isoDay.date(from: dateText),
                  let id = match(#"href="/tournaments/(\d+)""#, row)
            else { return nil }
            if let format = match(#"data-format="([^"]*)""#, row), format != "standard" { return nil }
            let players = Int(match(#"data-players="(\d+)""#, row) ?? "") ?? 0
            let name = decodeEntities(match(#"data-name="([^"]*)""#, row) ?? "Tournament")
            return ListedTournament(id: id, name: name, date: date, players: players)
        }
    }

    /// Rows of a tournament standings table: rank, player, deck, icons, decklist link.
    static func parseStandings(_ html: String) -> [OfficialStanding] {
        rows(in: html).compactMap { row in
            guard let rankText = match(#"data-rank="(\d+)""#, row),
                  let rank = Int(rankText)
            else { return nil }
            let name = decodeEntities(match(#"data-name="([^"]*)""#, row) ?? "Player")
            let deck = decodeEntities(match(#"data-deck="([^"]*)""#, row) ?? "Other")
            let icons = matches(#"/pokemon/[^/"]+/([^/"]+)\.png"#, row)
            let listID = match(#"/decks/list/(\d+)"#, row)
            return OfficialStanding(rank: rank, name: name, deck: deck.isEmpty ? "Other" : deck, icons: icons, listID: listID)
        }
    }

    /// Card lines from a `/decks/list/{id}` page, split into Pokémon/Trainer/Energy sections.
    static func parseDecklist(_ html: String) -> [DeckCard] {
        var text = html
        if let start = html.range(of: "data-text-decklist") {
            text = String(html[start.lowerBound...])
        }
        if let end = text.range(of: "data-image-decklist") {
            text = String(text[..<end.lowerBound])
        }
        var cards: [DeckCard] = []
        for section in text.components(separatedBy: "decklist-column-heading\">").dropFirst() {
            let category: CardCategory
            if section.hasPrefix("Pok") { category = .pokemon }
            else if section.hasPrefix("Tra") { category = .trainer }
            else if section.hasPrefix("Ene") { category = .energy }
            else { continue }
            for block in section.components(separatedBy: "class=\"decklist-card\"").dropFirst() {
                guard let countText = match(#"card-count">\s*(\d+)\s*<"#, block),
                      let name = match(#"card-name">([^<]*)<"#, block)
                else { continue }
                cards.append(DeckCard(
                    count: Int(countText) ?? 1,
                    name: decodeEntities(name).trimmingCharacters(in: .whitespacesAndNewlines),
                    set: match(#"data-set="([^"]*)""#, block) ?? "",
                    number: match(#"data-number="([^"]*)""#, block) ?? "",
                    category: category
                ))
            }
        }
        return cards
    }

    // MARK: Helpers

    /// Each `<tr ...>...</tr>` row, including its opening tag attributes.
    private static func rows(in html: String) -> [String] {
        html.components(separatedBy: "<tr").dropFirst().map { chunk in
            chunk.components(separatedBy: "</tr>").first ?? chunk
        }
    }

    private static func match(_ pattern: String, _ s: String) -> String? {
        guard let re = try? NSRegularExpression(pattern: pattern) else { return nil }
        return re.firstMatch(in: s, range: NSRange(s.startIndex..., in: s))
            .flatMap { Range($0.range(at: 1), in: s).map { String(s[$0]) } }
    }

    private static func matches(_ pattern: String, _ s: String) -> [String] {
        guard let re = try? NSRegularExpression(pattern: pattern) else { return [] }
        return re.matches(in: s, range: NSRange(s.startIndex..., in: s)).compactMap {
            Range($0.range(at: 1), in: s).map { String(s[$0]) }
        }
    }

    private static func decodeEntities(_ s: String) -> String {
        s.replacingOccurrences(of: "&#39;", with: "'")
            .replacingOccurrences(of: "&#039;", with: "'")
            .replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&eacute;", with: "é")
            .replacingOccurrences(of: "&amp;", with: "&")
    }
}
