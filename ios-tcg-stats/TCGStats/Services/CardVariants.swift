import Foundation

/// Gameplay identity of a printed Pokémon card, scraped from its Limitless card page.
nonisolated struct CardFingerprint: Codable, Hashable, Sendable {
    let type: String
    let hp: String
    let moves: [String]

    var signature: String { "\(type)|\(hp)|\(moves.joined(separator: ","))" }
}

/// A distinct gameplay version of a Pokémon that shares its name with other cards.
nonisolated struct CardVariant: Hashable, Sendable {
    let key: String
    let note: String
    let moves: [String]
}

/// Different Pokémon cards often share a name (e.g. Fire Chi-Yu from Twilight Masquerade
/// vs Darkness Chi-Yu from Pitch Black). Decklists only carry name + print, so this looks
/// up each print's type, HP and attacks and splits names into separate variants, while
/// keeping true reprints (same gameplay text) merged.
nonisolated enum CardVariants {
    private static var fileURL: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("card_fingerprints.json")
    }

    static func printID(set: String, number: String) -> String {
        "\(set.uppercased())-\(Int(number).map(String.init) ?? number)"
    }

    private static func load() -> [String: CardFingerprint] {
        guard let data = try? Data(contentsOf: fileURL),
              let map = try? JSONDecoder().decode([String: CardFingerprint].self, from: data) else { return [:] }
        return map
    }

    private static func save(_ map: [String: CardFingerprint]) {
        let url = fileURL
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        if let data = try? JSONEncoder().encode(map) { try? data.write(to: url, options: .atomic) }
    }

    private static func firstMatch(_ pattern: String, in s: String) -> String? {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.dotMatchesLineSeparators]) else { return nil }
        let ns = s as NSString
        guard let m = regex.firstMatch(in: s, range: NSRange(location: 0, length: ns.length)), m.numberOfRanges > 1 else { return nil }
        return ns.substring(with: m.range(at: 1))
    }

    private static func allMatches(_ pattern: String, in s: String) -> [String] {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.dotMatchesLineSeparators]) else { return [] }
        let ns = s as NSString
        return regex.matches(in: s, range: NSRange(location: 0, length: ns.length)).map { ns.substring(with: $0.range(at: 1)) }
    }

    private static func clean(_ html: String) -> String {
        html.replacingOccurrences(of: "<[^>]+>", with: " ", options: .regularExpression)
            .replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&#039;", with: "'")
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)
    }

    static func parse(_ html: String) -> CardFingerprint? {
        guard let rawTitle = firstMatch("<p class=\"card-text-title\">(.*?)</p>", in: html) else { return nil }
        let parts = clean(rawTitle).components(separatedBy: " - ").map { $0.trimmingCharacters(in: .whitespaces) }
        let type = parts.count > 1 ? parts[1] : ""
        let hp = parts.first { $0.hasSuffix("HP") }.map { String($0.dropLast(2)).trimmingCharacters(in: .whitespaces) } ?? ""
        let moves = allMatches("<p class=\"card-text-(?:attack|ability)-info\">(.*?)</p>", in: html).compactMap { raw -> String? in
            var text = raw.replacingOccurrences(of: "<span class=\"ptcg-symbol\">.*?</span>", with: " ", options: .regularExpression)
            text = clean(text)
            if text.hasPrefix("Ability:") { text = String(text.dropFirst(8)).trimmingCharacters(in: .whitespaces) }
            text = text.replacingOccurrences(of: "\\s+\\d+[+×x\\-]?$", with: "", options: .regularExpression)
            return text.isEmpty ? nil : text
        }
        guard !type.isEmpty || !moves.isEmpty else { return nil }
        return CardFingerprint(type: type, hp: hp, moves: moves)
    }

    /// Returns print id → variant for every Pokémon name that maps to more than one distinct card.
    static func resolve(_ records: [TournamentRecord], progress: @escaping @Sendable (Double) -> Void) async -> [String: CardVariant] {
        var printsByName: [String: Set<String>] = [:]
        for t in records {
            for e in t.entries {
                for c in e.cards where c.category == .pokemon && !c.set.isEmpty && !c.number.isEmpty {
                    printsByName[c.nameKey, default: []].insert(printID(set: c.set, number: c.number))
                }
            }
        }
        let ambiguous = printsByName.filter { $0.value.count > 1 }
        guard !ambiguous.isEmpty else { return [:] }

        var known = load()
        let missing = ambiguous.values.flatMap { $0 }.filter { known[$0] == nil }
        if !missing.isEmpty {
            var done = 0
            var index = 0
            while index < missing.count {
                let batch = Array(missing[index..<min(index + 8, missing.count)])
                index += 8
                await withTaskGroup(of: (String, CardFingerprint?).self) { group in
                    for id in batch {
                        group.addTask {
                            let parts = id.split(separator: "-", maxSplits: 1).map(String.init)
                            guard parts.count == 2, let html = try? await OfficialLimitless.fetchPage("/cards/\(parts[0])/\(parts[1])") else {
                                return (id, nil)
                            }
                            return (id, parse(html))
                        }
                    }
                    for await (id, fp) in group {
                        done += 1
                        progress(Double(done) / Double(missing.count))
                        if let fp { known[id] = fp }
                    }
                }
            }
            save(known)
        }

        var result: [String: CardVariant] = [:]
        for (nameKey, prints) in ambiguous {
            let fingerprinted = prints.compactMap { id in known[id].map { (id, $0) } }
            let groups = Dictionary(grouping: fingerprinted, by: { $0.1.signature })
            guard groups.count > 1 else { continue }
            for members in groups.values {
                guard let fp = members.first?.1 else { continue }
                let base = fp.moves.isEmpty ? "\(fp.type) \(fp.hp)" : fp.moves.joined(separator: " ")
                let slug = CardText.normalize(base)
                    .filter { $0.isLetter || $0.isNumber || $0 == " " }
                    .trimmingCharacters(in: .whitespaces)
                    .replacingOccurrences(of: " ", with: "-")
                let note = [fp.type, fp.moves.first ?? ""].filter { !$0.isEmpty }.joined(separator: " · ")
                let variant = CardVariant(key: "\(nameKey)~\(slug)", note: note.isEmpty ? fp.type : note, moves: fp.moves)
                for (id, _) in members { result[id] = variant }
            }
        }
        return result
    }
}
