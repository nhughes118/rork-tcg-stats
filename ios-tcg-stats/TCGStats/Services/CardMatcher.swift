import Foundation

/// A line of text recognized on a camera frame. `midY` is normalized (0 bottom, 1 top).
nonisolated struct RecognizedLine: Sendable {
    let text: String
    let midY: Double
}

/// Matches OCR text from a card photo to a known competitive card.
nonisolated struct CardMatcher: Sendable {
    private struct Candidate: Sendable {
        let key: String
        let padded: String
        let basePadded: String?
    }

    private let candidates: [Candidate]
    private let keys: Set<String>
    private let printIndex: [String: String]
    private let setCodes: Set<String>
    private let numberRegex = try? NSRegularExpression(pattern: "(\\d{1,3})\\s*/\\s*(\\d{2,3})")

    init(dataset: CardDataset) {
        keys = Set(dataset.cards.keys)
        candidates = dataset.cards.keys.map { key in
            let base = key.hasSuffix(" ex") ? String(key.dropLast(3)) : nil
            return Candidate(key: key, padded: " \(key) ", basePadded: base.map { " \($0) " })
        }
        printIndex = dataset.printIndex
        setCodes = dataset.setCodes
    }

    func match(_ lines: [RecognizedLine]) -> String? {
        guard !lines.isEmpty else { return nil }
        let fullRaw = lines.map(\.text).joined(separator: " ")
        let full = CardText.normalize(fullRaw)

        if let key = matchPrint(fullRaw: fullRaw) { return key }

        let hasExEvidence = full.contains("ex rule") || full.contains("pokemon ex") || full.contains(" ex ")
        var best: (key: String, score: Double)?

        for line in lines {
            let norm = CardText.normalize(line.text)
            guard norm.count >= 3 else { continue }
            let padded = " \(norm) "
            let topBonus = line.midY * 12
            for c in candidates {
                var score: Double?
                if padded.contains(c.padded) {
                    score = Double(c.key.count) + topBonus + (norm == c.key ? 6 : 0)
                } else if let base = c.basePadded, padded.contains(base) {
                    let baseKey = String(c.key.dropLast(3))
                    if keys.contains(baseKey) && !hasExEvidence { continue }
                    score = Double(baseKey.count) + topBonus + 0.5
                }
                if let score, score > (best?.score ?? 0) {
                    best = (c.key, score)
                }
            }
        }
        guard let best, best.score >= 7 else { return nil }
        return best.key
    }

    private func matchPrint(fullRaw: String) -> String? {
        guard let regex = numberRegex else { return nil }
        let ns = fullRaw as NSString
        let numbers = regex.matches(in: fullRaw, range: NSRange(location: 0, length: ns.length)).compactMap { m -> String? in
            guard let n = Int(ns.substring(with: m.range(at: 1))) else { return nil }
            return String(n)
        }
        guard !numbers.isEmpty else { return nil }
        let tokens = fullRaw
            .components(separatedBy: CharacterSet.alphanumerics.inverted)
            .filter { $0.count >= 2 && $0.count <= 4 && $0 == $0.uppercased() && setCodes.contains($0) }
        for set in tokens {
            for n in numbers {
                if let key = printIndex["\(set)-\(n)"] { return key }
            }
        }
        return nil
    }
}
