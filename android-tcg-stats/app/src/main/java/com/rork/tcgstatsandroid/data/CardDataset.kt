package com.rork.tcgstatsandroid.data

import java.util.Calendar

object Weeks {
    const val DAY: Long = 86_400_000L
    const val WEEK: Long = 7 * DAY

    /** Monday 00:00 (local time) of the ISO week containing [millis]. */
    fun startOfWeek(millis: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = millis
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        while (c.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) c.add(Calendar.DAY_OF_MONTH, -1)
        return c.timeInMillis
    }
}

private class DeckAccumulator(val icons: List<String>, var count: Int = 0, var best: Int? = null)

/** Aggregated tournament data with card indexes and stats computation. */
class CardDataset(
    input: List<TournamentRecord>,
    val generatedAt: Long = System.currentTimeMillis(),
    variants: Map<String, CardVariant> = emptyMap(),
) {
    val tournaments: List<TournamentRecord> = applyVariants(input, variants).sortedByDescending { it.date }
    val cards: Map<String, CardIdentity>
    val printIndex: Map<String, String>
    val setCodes: Set<String>
    val recentUsage: Map<String, Double>
    /** Card keys per plain name, when a name covers several distinct cards. */
    val keysByName: Map<String, List<String>>
    /** Attack/ability names for variant keys, used to disambiguate scans. */
    val variantMoves: Map<String, List<String>> = variants.values.associate { it.key to it.moves }
    private val noteByKey: Map<String, String> = variants.values.associate { it.key to it.note }

    init {
        val printCounts = HashMap<String, HashMap<CardIdentity, Int>>()
        val prints = HashMap<String, String>()
        val sets = HashSet<String>()
        for (t in tournaments) for (e in t.entries) for (c in e.cards) {
            val identity = CardIdentity(
                c.name, c.set, c.number, c.category,
                variant = c.variant,
                note = c.variant?.let { noteByKey[it] },
            )
            val counts = printCounts.getOrPut(c.key) { HashMap() }
            counts[identity] = (counts[identity] ?: 0) + 1
            if (c.set.isNotEmpty()) {
                sets += c.set
                prints["${c.set}-${c.number.toIntOrNull()?.toString() ?: c.number}"] = c.key
            }
        }
        cards = printCounts.mapNotNull { (key, counts) -> counts.maxByOrNull { it.value }?.key?.let { key to it } }.toMap()
        printIndex = prints
        setCodes = sets

        val cutoff = generatedAt - 8 * Weeks.WEEK
        var total = 0
        val counts = HashMap<String, Int>()
        for (t in tournaments) {
            if (t.date < cutoff) continue
            for (e in t.entries) {
                total += 1
                for (k in e.cardKeys) counts[k] = (counts[k] ?: 0) + 1
            }
        }
        recentUsage = if (total > 0) counts.mapValues { it.value.toDouble() / total } else emptyMap()
        keysByName = cards.values.groupBy({ it.nameKey }, { it.key })
            .mapValues { (_, keys) -> keys.sortedByDescending { recentUsage[it] ?: 0.0 } }
    }

    /**
     * Maps a stored key to a key in this dataset. Older history entries use the plain
     * name; if that name is now split into variants, the most played one is returned.
     */
    fun resolve(key: String): String? {
        if (key in cards) return key
        val nameKey = key.substringBefore('~').substringBefore('#')
        return keysByName[nameKey]?.firstOrNull()
    }

    private companion object {
        fun applyVariants(input: List<TournamentRecord>, variants: Map<String, CardVariant>): List<TournamentRecord> =
            input.map { t ->
                t.copy(entries = t.entries.map { e ->
                    val cards = e.cards.map { c ->
                        val v = if (c.category == CardCategory.Pokemon && c.set.isNotEmpty()) {
                            variants[CardVariants.printId(c.set, c.number)]?.key
                        } else null
                        if (v != c.variant) c.copy(variant = v) else c
                    }
                    e.copy(cards = cards, cardKeys = cards.map { it.key }.toSet())
                })
            }
    }

    fun tournament(id: String): TournamentRecord? = tournaments.firstOrNull { it.id == id }

    private fun usage(key: String, start: Long, end: Long): Double? {
        var total = 0
        var hits = 0
        for (t in tournaments) {
            if (t.date < start || t.date >= end) continue
            total += t.entries.size
            hits += t.entries.count { key in it.cardKeys }
        }
        return if (total > 0) hits.toDouble() / total else null
    }

    fun stats(key: String, window: StatsWindow, now: Long = System.currentTimeMillis()): CardStats? {
        val card = cards[key] ?: return null
        val cutoff = window.weeks?.let { now - it * Weeks.WEEK }
        val scoped = tournaments.filter { cutoff == null || it.date >= cutoff }

        var total = 0
        var withCard = 0
        var copies = 0
        val decks = LinkedHashMap<String, DeckAccumulator>()
        val appearances = mutableListOf<TournamentAppearance>()
        val weekly = HashMap<Long, IntArray>()

        for (t in scoped) {
            val users = t.entries.filter { key in it.cardKeys }
            total += t.entries.size
            withCard += users.size
            val week = Weeks.startOfWeek(t.date)
            val bucket = weekly.getOrPut(week) { IntArray(2) }
            bucket[0] += t.entries.size
            bucket[1] += users.size

            for (u in users) {
                copies += u.copies(key)
                val d = decks.getOrPut(u.deckName) { DeckAccumulator(u.deckIcons) }
                d.count += 1
                u.placing?.let { p -> d.best = minOf(d.best ?: p, p) }
            }
            if (users.isNotEmpty()) {
                appearances += TournamentAppearance(
                    tournamentId = t.id,
                    name = t.name,
                    date = t.date,
                    players = t.players,
                    decklists = t.entries.size,
                    usage = users.size.toDouble() / maxOf(t.entries.size, 1),
                    finishers = users.sortedBy { it.placing ?: Int.MAX_VALUE },
                )
            }
        }

        val trend = weekly.map { (week, v) ->
            TrendPoint(week, if (v[0] > 0) v[1].toDouble() / v[0] else 0.0, v[0])
        }.sortedBy { it.weekStart }

        val topDecks = decks.map { (name, d) ->
            DeckShare(name, d.icons, d.count, d.count.toDouble() / maxOf(total, 1), d.best)
        }.sortedByDescending { it.count }

        val month = 28 * Weeks.DAY
        val recent = usage(key, now - month, now + Weeks.DAY)
        val previous = usage(key, now - 2 * month, now - month)
        val delta = if (recent != null && previous != null) recent - previous else null

        return CardStats(
            card = card,
            usage = if (total > 0) withCard.toDouble() / total else 0.0,
            delta = delta,
            decksWithCard = withCard,
            totalDecks = total,
            tournamentCount = scoped.size,
            averageCopies = if (withCard > 0) copies.toDouble() / withCard else 0.0,
            trend = trend,
            topDecks = topDecks,
            appearances = appearances,
        )
    }

    /** Entries that play the card with a given archetype, best finishes first. */
    fun entries(key: String, deckName: String, window: StatsWindow, now: Long = System.currentTimeMillis()): List<Pair<TournamentRecord, PlayerEntry>> {
        val cutoff = window.weeks?.let { now - it * Weeks.WEEK }
        val result = mutableListOf<Pair<TournamentRecord, PlayerEntry>>()
        for (t in tournaments) {
            if (cutoff != null && t.date < cutoff) continue
            for (e in t.entries) if (e.deckName == deckName && key in e.cardKeys) result += t to e
        }
        return result.sortedWith(
            compareBy<Pair<TournamentRecord, PlayerEntry>> { it.second.placing ?: Int.MAX_VALUE }.thenByDescending { it.first.date }
        )
    }
}
