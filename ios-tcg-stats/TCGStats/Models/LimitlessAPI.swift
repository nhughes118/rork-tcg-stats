import Foundation

/// Tournament summary returned by `play.limitlesstcg.com/api/tournaments`.
nonisolated struct APITournament: Decodable, Sendable {
    let id: String
    let name: String
    let date: String
    let players: Int

    enum CodingKeys: String, CodingKey { case id, name, date, players }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = (try? c.decode(String.self, forKey: .name)) ?? "Tournament"
        date = (try? c.decode(String.self, forKey: .date)) ?? ""
        players = (try? c.decode(Int.self, forKey: .players)) ?? 0
    }
}

/// One player's final standing including their decklist.
nonisolated struct APIStanding: Decodable, Sendable {
    let name: String?
    let player: String?
    let placing: Int?
    let record: APIRecord?
    let deck: APIDeck?
    let decklist: APIDecklist?

    enum CodingKeys: String, CodingKey { case name, player, placing, record, deck, decklist }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = try? c.decodeIfPresent(String.self, forKey: .name)
        player = try? c.decodeIfPresent(String.self, forKey: .player)
        placing = try? c.decodeIfPresent(Int.self, forKey: .placing)
        record = try? c.decodeIfPresent(APIRecord.self, forKey: .record)
        deck = try? c.decodeIfPresent(APIDeck.self, forKey: .deck)
        decklist = try? c.decodeIfPresent(APIDecklist.self, forKey: .decklist)
    }
}

nonisolated struct APIRecord: Decodable, Sendable {
    let wins: Int?
    let losses: Int?
    let ties: Int?
}

nonisolated struct APIDeck: Decodable, Sendable {
    let id: String?
    let name: String?
    let icons: [String]?
}

nonisolated struct APIDecklist: Decodable, Sendable {
    let pokemon: [APIDeckCard]?
    let trainer: [APIDeckCard]?
    let energy: [APIDeckCard]?

    enum CodingKeys: String, CodingKey { case pokemon, trainer, energy }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        pokemon = try? c.decodeIfPresent([APIDeckCard].self, forKey: .pokemon)
        trainer = try? c.decodeIfPresent([APIDeckCard].self, forKey: .trainer)
        energy = try? c.decodeIfPresent([APIDeckCard].self, forKey: .energy)
    }
}

nonisolated struct APIDeckCard: Decodable, Sendable {
    let count: Int
    let set: String?
    let number: String?
    let name: String

    enum CodingKeys: String, CodingKey { case count, set, number, name }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        count = (try? c.decode(Int.self, forKey: .count)) ?? 1
        set = try? c.decodeIfPresent(String.self, forKey: .set)
        if let s = try? c.decodeIfPresent(String.self, forKey: .number) {
            number = s
        } else if let i = try? c.decodeIfPresent(Int.self, forKey: .number) {
            number = String(i)
        } else {
            number = nil
        }
        name = try c.decode(String.self, forKey: .name)
    }
}
