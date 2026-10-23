package com.rork.tcgstatsandroid.data

import kotlinx.serialization.Serializable
import java.util.Locale

/** Tournament source the stats are computed from. */
@Serializable
enum class DataSource(val label: String, val tagline: String) {
    Online("Online", "TCG Live & online events"),
    Official("Official", "In-person Regionals & majors"),
}

@Serializable
enum class CardCategory(val title: String) {
    Pokemon("Pokémon"),
    Trainer("Trainer"),
    Energy("Energy"),
}

/** A single line in a decklist. */
@Serializable
data class DeckCard(
    val count: Int,
    val name: String,
    val set: String,
    val number: String,
    val category: CardCategory,
    /** Variant key when this name covers several distinct cards (see [CardVariants]). */
    val variant: String? = null,
) {
    val nameKey: String by lazy(LazyThreadSafetyMode.PUBLICATION) { CardText.key(name) }
    val key: String get() = variant ?: nameKey
}

/** A printed card used to represent a card name (image, set, number). */
@Serializable
data class CardIdentity(
    val name: String,
    val set: String,
    val number: String,
    val category: CardCategory,
    /** Variant key when the name covers several distinct cards. */
    val variant: String? = null,
    /** Short distinguishing text for variants, e.g. "Darkness · Whirling Envy". */
    val note: String? = null,
) {
    val nameKey: String get() = CardText.key(name)
    val key: String get() = variant ?: nameKey
    val printLabel: String
        get() {
            val base = if (set.isEmpty()) category.title else "$set $number"
            return if (note.isNullOrEmpty()) base else "$base · $note"
        }

    fun imageUrl(large: Boolean = false): String? {
        if (set.isEmpty() || number.isEmpty()) return null
        val padded = number.toIntOrNull()?.let { String.format(Locale.US, "%03d", it) } ?: number
        val size = if (large) "LG" else "SM"
        return "https://limitlesstcg.nyc3.cdn.digitaloceanspaces.com/tpci/$set/${set}_${padded}_R_EN_$size.png"
    }

    val limitlessUrl: String
        get() = if (set.isEmpty() || number.isEmpty()) "https://limitlesstcg.com/cards"
        else "https://limitlesstcg.com/cards/$set/$number"
}

/** One player's finish in a tournament. */
@Serializable
data class PlayerEntry(
    val id: String,
    val name: String,
    val placing: Int? = null,
    val wins: Int = 0,
    val losses: Int = 0,
    val ties: Int = 0,
    val deckName: String,
    val deckIcons: List<String> = emptyList(),
    val cards: List<DeckCard> = emptyList(),
    val cardKeys: Set<String> = emptySet(),
) {
    val recordText: String
        get() {
            if (wins == 0 && losses == 0 && ties == 0) return "—"
            return if (ties > 0) "$wins-$losses-$ties" else "$wins-$losses"
        }

    fun copies(key: String): Int = cards.filter { it.key == key }.sumOf { it.count }

    /** Plain-text decklist in the Pokémon TCG Live import format. */
    val exportText: String
        get() {
            val sections = mutableListOf<String>()
            for (category in CardCategory.entries) {
                val lines = cards.filter { it.category == category }
                if (lines.isEmpty()) continue
                val block = mutableListOf("${category.title}: ${lines.sumOf { it.count }}")
                lines.forEach { card ->
                    block += listOf(card.count.toString(), card.name, card.set, card.number)
                        .filter { it.isNotEmpty() }
                        .joinToString(" ")
                }
                sections += block.joinToString("\n")
            }
            sections += "Total Cards: ${cards.sumOf { it.count }}"
            return sections.joinToString("\n\n")
        }
}

/** A completed tournament with every published decklist. */
@Serializable
data class TournamentRecord(
    val id: String,
    val name: String = "Tournament",
    val date: Long,
    val players: Int = 0,
    val entries: List<PlayerEntry> = emptyList(),
    val source: DataSource = DataSource.Online,
) {
    val url: String
        get() = when (source) {
            DataSource.Online -> "https://play.limitlesstcg.com/tournament/$id/standings"
            DataSource.Official ->
                if (LabsLimitless.isLabsId(id)) LabsLimitless.pageUrl(id) else "https://limitlesstcg.com/tournaments/$id"
        }
}

enum class StatsWindow(val label: String, val weeks: Int?) {
    FourWeeks("4 wks", 4),
    EightWeeks("8 wks", 8),
    All("All", null),
}

data class TrendPoint(val weekStart: Long, val usage: Double, val decks: Int)

data class DeckShare(
    val name: String,
    val icons: List<String>,
    val count: Int,
    /** Share of all decks in the window. */
    val share: Double,
    /** Best finish among these decks. */
    val bestPlacing: Int?,
)

data class TournamentAppearance(
    val tournamentId: String,
    val name: String,
    val date: Long,
    val players: Int,
    val decklists: Int,
    val usage: Double,
    val finishers: List<PlayerEntry>,
) {
    val best: PlayerEntry? get() = finishers.firstOrNull()
}

data class CardStats(
    val card: CardIdentity,
    val usage: Double,
    val delta: Double?,
    val decksWithCard: Int,
    val totalDecks: Int,
    val tournamentCount: Int,
    val averageCopies: Double,
    val trend: List<TrendPoint>,
    val topDecks: List<DeckShare>,
    val appearances: List<TournamentAppearance>,
) {
    /** Appearances ordered by best finish, then size. */
    val topAppearances: List<TournamentAppearance>
        get() = appearances.sortedWith(
            compareBy<TournamentAppearance> { it.best?.placing ?: Int.MAX_VALUE }.thenByDescending { it.players }
        )
}

/** A card the user has looked up. */
@Serializable
data class HistoryEntry(
    val card: CardIdentity,
    val usage: Double,
    val scannedAt: Long,
)
