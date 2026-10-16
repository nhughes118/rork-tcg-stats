package com.rork.tcgstatsandroid.data

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Scrapes limitlesstcg.com for in-person ("official") tournaments: the homepage
 * "Recent Tournaments" list, the completed-tournaments table, standings pages
 * and per-player decklist pages.
 */
object OfficialLimitless {
    const val SITE = "https://limitlesstcg.com"
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    data class ListedTournament(val id: String, val name: String, val date: Long, val players: Int)

    data class OfficialStanding(
        val rank: Int,
        val name: String,
        val deck: String,
        val icons: List<String>,
        val listId: String?,
    )

    private fun utcFormat(pattern: String) = SimpleDateFormat(pattern, Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    suspend fun fetchPage(path: String): String =
        LimitlessClient.httpGet(if (path.startsWith("http")) path else SITE + path, "text/html", USER_AGENT)

    /** The "Recent Tournaments" block on the limitlesstcg.com homepage. */
    fun parseHomeRecent(html: String): List<ListedTournament> {
        val start = html.indexOf("<h2>Recent Tournaments</h2>")
        if (start < 0) return emptyList()
        var section = html.substring(start + "<h2>Recent Tournaments</h2>".length)
        val end = section.indexOf("<h2>")
        if (end >= 0) section = section.substring(0, end)
        val longDay = utcFormat("d MMMM yyyy")
        return section.split("<a href=\"/tournaments/").drop(1).mapNotNull { block ->
            val id = match("^(\\d+)\"", block) ?: return@mapNotNull null
            val name = match("font-bold\">([^<]*)<", block) ?: return@mapNotNull null
            val format = match("class=\"format\"[^>]*alt=\"([a-z-]+)\"", block)
            if (format != null && format != "standard") return@mapNotNull null
            val m = Regex("(\\d{1,2})(?:st|nd|rd|th)\\s+([A-Za-z]+)\\s+(\\d{4})").find(block) ?: return@mapNotNull null
            val date = runCatching { longDay.parse("${m.groupValues[1]} ${m.groupValues[2]} ${m.groupValues[3]}")?.time }.getOrNull()
                ?: return@mapNotNull null
            val players = match("(\\d+)\\s+Players", block)?.toIntOrNull() ?: 0
            ListedTournament(id, decodeEntities(name.trim()), date, players)
        }
    }

    /** Rows of the completed-tournaments table (`/tournaments`). */
    fun parseList(html: String): List<ListedTournament> {
        val isoDay = utcFormat("yyyy-MM-dd")
        return rows(html).mapNotNull { row ->
            val dateText = match("data-date=\"([\\d-]+)\"", row) ?: return@mapNotNull null
            val date = runCatching { isoDay.parse(dateText)?.time }.getOrNull() ?: return@mapNotNull null
            val id = match("href=\"/tournaments/(\\d+)\"", row) ?: return@mapNotNull null
            val format = match("data-format=\"([^\"]*)\"", row)
            if (format != null && format != "standard") return@mapNotNull null
            val players = match("data-players=\"(\\d+)\"", row)?.toIntOrNull() ?: 0
            val name = decodeEntities(match("data-name=\"([^\"]*)\"", row) ?: "Tournament")
            ListedTournament(id, name, date, players)
        }
    }

    /** Rows of a tournament standings table. */
    fun parseStandings(html: String): List<OfficialStanding> = rows(html).mapNotNull { row ->
        val rank = match("data-rank=\"(\\d+)\"", row)?.toIntOrNull() ?: return@mapNotNull null
        val name = decodeEntities(match("data-name=\"([^\"]*)\"", row) ?: "Player")
        val deck = decodeEntities(match("data-deck=\"([^\"]*)\"", row) ?: "Other")
        val icons = Regex("/pokemon/[^/\"]+/([^/\"]+)\\.png").findAll(row).map { it.groupValues[1] }.toList()
        val listId = match("/decks/list/(\\d+)", row)
        OfficialStanding(rank, name, deck.ifEmpty { "Other" }, icons, listId)
    }

    /** Card lines from a `/decks/list/{id}` page. */
    fun parseDecklist(html: String): List<DeckCard> {
        var text = html
        val start = html.indexOf("data-text-decklist")
        if (start >= 0) text = html.substring(start)
        val end = text.indexOf("data-image-decklist")
        if (end >= 0) text = text.substring(0, end)
        val cards = mutableListOf<DeckCard>()
        for (section in text.split("decklist-column-heading\">").drop(1)) {
            val category = when {
                section.startsWith("Pok") -> CardCategory.Pokemon
                section.startsWith("Tra") -> CardCategory.Trainer
                section.startsWith("Ene") -> CardCategory.Energy
                else -> continue
            }
            for (block in section.split("class=\"decklist-card\"").drop(1)) {
                val count = match("card-count\">\\s*(\\d+)\\s*<", block) ?: continue
                val name = match("card-name\">([^<]*)<", block) ?: continue
                cards += DeckCard(
                    count = count.toIntOrNull() ?: 1,
                    name = decodeEntities(name).trim(),
                    set = match("data-set=\"([^\"]*)\"", block) ?: "",
                    number = match("data-number=\"([^\"]*)\"", block) ?: "",
                    category = category,
                )
            }
        }
        return cards
    }

    private fun rows(html: String): List<String> =
        html.split("<tr").drop(1).map { it.substringBefore("</tr>") }

    private fun match(pattern: String, s: String): String? = Regex(pattern).find(s)?.groupValues?.getOrNull(1)

    private fun decodeEntities(s: String): String = s
        .replace("&#39;", "'")
        .replace("&#039;", "'")
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&eacute;", "é")
        .replace("&amp;", "&")
}
