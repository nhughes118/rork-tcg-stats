package com.rork.tcgstatsandroid.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** One finisher in a tournament's full standings, with or without a published decklist. */
@Serializable
data class StandingRow(
    val placing: Int? = null,
    val name: String,
    val deckName: String = "Other",
    val deckIcons: List<String> = emptyList(),
    val wins: Int = 0,
    val losses: Int = 0,
    val ties: Int = 0,
    /** limitlesstcg.com decklist id (official events); fetched on demand. */
    val listId: String? = null,
) {
    fun asEntry(tournamentId: String, cards: List<DeckCard> = emptyList()) = PlayerEntry(
        id = "$tournamentId-standing-${placing ?: name}",
        name = name,
        placing = placing,
        wins = wins,
        losses = losses,
        ties = ties,
        deckName = deckName,
        deckIcons = deckIcons,
        cards = cards,
        cardKeys = cards.map { it.key }.toSet(),
    )
}

/**
 * Full tournament standings (every player, including those without a decklist),
 * loaded lazily for the tournament detail search and cached on disk.
 */
object TournamentStandings {
    private val memory = ConcurrentHashMap<String, List<StandingRow>>()
    private val decklists = ConcurrentHashMap<String, List<DeckCard>>()
    private val serializer = ListSerializer(StandingRow.serializer())

    private fun file(context: Context, record: TournamentRecord): File =
        File(context.cacheDir, "standings-v1").apply { mkdirs() }
            .resolve("${record.source.name.lowercase()}-${record.id}.json")

    suspend fun load(context: Context, record: TournamentRecord): List<StandingRow> = withContext(Dispatchers.IO) {
        val key = "${record.source.name}:${record.id}"
        memory[key]?.let { return@withContext it }
        val f = file(context, record)
        val cached = if (f.exists()) runCatching { LimitlessClient.json.decodeFromString(serializer, f.readText()) }.getOrNull() else null
        val rows = cached ?: fetch(record).also { rows ->
            runCatching { f.writeText(LimitlessClient.json.encodeToString(serializer, rows)) }
        }
        memory[key] = rows
        rows
    }

    private suspend fun fetch(record: TournamentRecord): List<StandingRow> = when (record.source) {
        DataSource.Official -> if (LabsLimitless.isLabsId(record.id)) {
            LabsLimitless.standings(LabsLimitless.labsId(record.id)).map {
                StandingRow(it.placing, it.name, it.deckName, it.icons, it.wins, it.losses, it.ties, listId = null)
            }
        } else OfficialLimitless.parseStandings(OfficialLimitless.fetchPage("/tournaments/${record.id}")).map {
            StandingRow(placing = it.rank, name = it.name, deckName = it.deck, deckIcons = it.icons, listId = it.listId)
        }
        DataSource.Online -> {
            val text = LimitlessClient.httpGet("https://play.limitlesstcg.com/api/tournaments/${record.id}/standings", "application/json")
            val array = LimitlessClient.json.parseToJsonElement(text) as? JsonArray ?: throw LimitlessException("Standings unavailable.")
            array.mapNotNull { element ->
                val s = element as? JsonObject ?: return@mapNotNull null
                val r = s["record"] as? JsonObject
                val deck = s["deck"] as? JsonObject
                val player = s.str("player")
                StandingRow(
                    placing = s.int("placing"),
                    name = s.str("name") ?: player ?: "Player",
                    deckName = deck?.str("name") ?: "Other",
                    deckIcons = (deck?.get("icons") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                    wins = r?.int("wins") ?: 0,
                    losses = r?.int("losses") ?: 0,
                    ties = r?.int("ties") ?: 0,
                )
            }
        }
    }

    /** Fetches a decklist that wasn't part of the dataset (scraped official finishers beyond the top 64). */
    suspend fun decklist(listId: String): List<DeckCard> {
        decklists[listId]?.let { return it }
        val cards = OfficialLimitless.parseDecklist(OfficialLimitless.fetchPage("/decks/list/$listId"))
        if (cards.isNotEmpty()) decklists[listId] = cards
        return cards
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
}
