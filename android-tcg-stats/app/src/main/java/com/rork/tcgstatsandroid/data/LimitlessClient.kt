package com.rork.tcgstatsandroid.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class LimitlessException(message: String) : Exception(message)

/**
 * Fetches recent Standard tournaments and decklists, either from the
 * play.limitlesstcg.com API (online events) or by scraping limitlesstcg.com
 * pages (official in-person events). Each tournament is cached on disk.
 */
object LimitlessClient {
    private const val BASE = "https://play.limitlesstcg.com/api"
    const val WEEKS_BACK = 12
    private const val PER_WEEK = 3
    private const val MIN_PLAYERS = 32
    /** Official events list every finisher; decklists are fetched for the top N only. */
    const val OFFICIAL_LIST_LIMIT = 64
    /** Maximum official events (homepage recent + older completed) per load. */
    private const val OFFICIAL_MAX_EVENTS = 8
    /** Parallel decklist requests against Limitless Labs. */
    private const val LABS_CONCURRENCY = 24

    private const val BAD_RESPONSE = "Limitless TCG didn't respond as expected. Please try again."
    private const val NO_DATA = "No recent tournament decklists were found."

    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    private data class Summary(val id: String, val name: String, val date: Long, val players: Int)

    suspend fun httpGet(url: String, accept: String, userAgent: String? = null): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", accept)
            userAgent?.let { conn.setRequestProperty("User-Agent", it) }
            if (conn.responseCode !in 200..299) throw LimitlessException(BAD_RESPONSE)
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    fun parseIsoDate(s: String): Long? {
        val cleaned = s.replace(Regex("\\.\\d+"), "")
        for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd")) {
            val parsed = runCatching { SimpleDateFormat(pattern, Locale.US).parse(cleaned)?.time }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private fun cacheDir(context: Context, source: DataSource): File =
        File(context.cacheDir, "tournaments/${source.name.lowercase()}-v2").apply { mkdirs() }

    private fun cachedRecord(context: Context, id: String, source: DataSource): TournamentRecord? {
        val file = File(cacheDir(context, source), "$id.json")
        if (!file.exists()) return null
        return runCatching { json.decodeFromString(TournamentRecord.serializer(), file.readText()) }.getOrNull()
    }

    private fun saveCache(context: Context, record: TournamentRecord) {
        runCatching {
            File(cacheDir(context, record.source), "${record.id}.json")
                .writeText(json.encodeToString(TournamentRecord.serializer(), record))
        }
    }

    private suspend fun <T, R> List<T>.mapConcurrent(limit: Int, block: suspend (T) -> R): List<R> = coroutineScope {
        val semaphore = Semaphore(limit)
        map { item -> async(Dispatchers.IO) { semaphore.withPermit { block(item) } } }.awaitAll()
    }

    /** Picks the largest finished tournaments from each recent week. */
    private fun select(list: List<Summary>, now: Long): List<Summary> {
        val oldest = now - WEEKS_BACK * Weeks.WEEK
        val finishedBefore = now - 18 * 3_600_000L
        return list
            .filter { it.players >= MIN_PLAYERS && it.date < finishedBefore && it.date >= oldest }
            .groupBy { Weeks.startOfWeek(it.date) }
            .values
            .flatMap { items -> items.sortedByDescending { it.players }.take(PER_WEEK) }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    // Online (play.limitlesstcg.com API)

    private suspend fun onlineRecord(context: Context, t: Summary): TournamentRecord {
        cachedRecord(context, t.id, DataSource.Online)?.let { return it }
        val text = httpGet("$BASE/tournaments/${t.id}/standings", "application/json")
        val standings = json.parseToJsonElement(text) as? JsonArray ?: throw LimitlessException(BAD_RESPONSE)
        val entries = standings.mapNotNull { element ->
            val s = element as? JsonObject ?: return@mapNotNull null
            val list = s["decklist"] as? JsonObject ?: return@mapNotNull null
            val cards = listOf(
                "pokemon" to CardCategory.Pokemon,
                "trainer" to CardCategory.Trainer,
                "energy" to CardCategory.Energy,
            ).flatMap { (field, category) ->
                (list[field] as? JsonArray).orEmpty().mapNotNull { item ->
                    val c = item as? JsonObject ?: return@mapNotNull null
                    val name = c.str("name") ?: return@mapNotNull null
                    DeckCard(c.int("count") ?: 1, name, c.str("set") ?: "", c.str("number") ?: "", category)
                }
            }
            if (cards.isEmpty()) return@mapNotNull null
            val record = s["record"] as? JsonObject
            val deck = s["deck"] as? JsonObject
            val player = s.str("player")
            PlayerEntry(
                id = player ?: UUID.randomUUID().toString(),
                name = s.str("name") ?: player ?: "Player",
                placing = s.int("placing"),
                wins = record?.int("wins") ?: 0,
                losses = record?.int("losses") ?: 0,
                ties = record?.int("ties") ?: 0,
                deckName = deck?.str("name") ?: "Other",
                deckIcons = (deck?.get("icons") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                cards = cards,
                cardKeys = cards.map { it.key }.toSet(),
            )
        }
        return TournamentRecord(t.id, t.name, t.date, t.players, entries, DataSource.Online)
    }

    private suspend fun loadOnline(context: Context, progress: (Double) -> Unit): List<TournamentRecord> {
        val now = System.currentTimeMillis()
        progress(0.03)
        val listText = httpGet("$BASE/tournaments?game=PTCG&format=STANDARD&limit=1000", "application/json")
        val list = json.parseToJsonElement(listText) as? JsonArray ?: throw LimitlessException(BAD_RESPONSE)
        val summaries = list.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            val date = o.str("date")?.let(::parseIsoDate) ?: return@mapNotNull null
            Summary(id, o.str("name") ?: "Tournament", date, o.int("players") ?: 0)
        }
        val selected = select(summaries, now)
        if (selected.isEmpty()) throw LimitlessException(NO_DATA)

        val done = AtomicInteger(0)
        val records = selected.mapConcurrent(6) { t ->
            val record = runCatching { onlineRecord(context, t) }.getOrNull()
            progress(0.05 + 0.95 * done.incrementAndGet() / selected.size)
            record
        }.filterNotNull().filter { it.entries.isNotEmpty() }
        records.forEach { saveCache(context, it) }
        if (records.isEmpty()) throw LimitlessException(NO_DATA)
        return records
    }

    // Official via Limitless Labs (full standings + every player's decklist)

    /** Shares identical card lines across decklists; full events hold thousands of near-identical lists. */
    private fun intern(records: List<TournamentRecord>): List<TournamentRecord> {
        val cards = HashMap<DeckCard, DeckCard>()
        val keys = HashMap<String, String>()
        return records.map { r ->
            r.copy(entries = r.entries.map { e ->
                e.copy(
                    cards = e.cards.map { c -> cards.getOrPut(c) { c } },
                    cardKeys = e.cardKeys.mapTo(HashSet(e.cardKeys.size * 2)) { k -> keys.getOrPut(k) { k } },
                )
            })
        }
    }

    private suspend fun labsRecord(context: Context, t: LabsLimitless.LabsTournament, progress: (Int, Int) -> Unit): TournamentRecord? {
        val recordId = LabsLimitless.ID_PREFIX + t.id
        cachedRecord(context, recordId, DataSource.Official)?.let { return it }
        val standings = LabsLimitless.standings(t.id)
        if (standings.isEmpty()) return null
        val withLists = standings.filter { it.hasList }
        val done = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val entries = withLists.mapConcurrent(LABS_CONCURRENCY) { s ->
            var cards: List<DeckCard>? = null
            for (attempt in 0 until 3) {
                cards = runCatching { LabsLimitless.decklist(t.id, s.tpId) }.getOrNull()
                if (cards != null) break
            }
            progress(done.incrementAndGet(), withLists.size)
            if (cards == null) failed.incrementAndGet()
            val list = cards.orEmpty()
            if (list.isEmpty()) return@mapConcurrent null
            PlayerEntry(
                id = "$recordId-${s.tpId}",
                name = s.name,
                placing = s.placing,
                wins = s.wins,
                losses = s.losses,
                ties = s.ties,
                deckName = s.deckName,
                deckIcons = s.icons,
                cards = list,
                cardKeys = list.map { it.key }.toSet(),
            )
        }.filterNotNull()
        if (entries.isEmpty()) return null
        val record = TournamentRecord(recordId, t.name, t.date, standings.size, entries, DataSource.Official)
        // Cache only (nearly) complete events so a flaky load is retried next time.
        if (failed.get() <= withLists.size / 50) saveCache(context, record)
        return record
    }

    private suspend fun loadLabs(context: Context, progress: (Double) -> Unit): List<TournamentRecord> {
        val now = System.currentTimeMillis()
        progress(0.02)
        val oldest = now - WEEKS_BACK * Weeks.WEEK
        val selected = LabsLimitless.tournaments()
            .filter { it.completed && it.date in oldest..now }
            .sortedByDescending { it.date }
            .take(OFFICIAL_MAX_EVENTS)
        if (selected.isEmpty()) return emptyList()
        // Progress is split evenly per event; each event reports its decklists.
        val records = mutableListOf<TournamentRecord>()
        selected.forEachIndexed { index, t ->
            val base = 0.04 + 0.96 * index / selected.size
            val record = runCatching {
                labsRecord(context, t) { done, total -> progress(base + 0.96 / selected.size * done / maxOf(total, 1)) }
            }.getOrNull()
            if (record != null) records += record
            progress(0.04 + 0.96 * (index + 1) / selected.size)
        }
        return records
    }

    // Official fallback (limitlesstcg.com scraping, top finishers only)

    private suspend fun loadOfficial(context: Context, progress: (Double) -> Unit): List<TournamentRecord> {
        val labs = runCatching { loadLabs(context, progress) }.getOrElse { emptyList() }
        if (labs.isNotEmpty()) return labs
        return loadOfficialScraped(context, progress)
    }

    private suspend fun loadOfficialScraped(context: Context, progress: (Double) -> Unit): List<TournamentRecord> = coroutineScope {
        val now = System.currentTimeMillis()
        progress(0.03)
        // Primary: the homepage "Recent Tournaments" block. Secondary: older completed events for trend depth.
        val homeTask = async { runCatching { OfficialLimitless.fetchPage("/") }.getOrNull() }
        val listTask = async { runCatching { OfficialLimitless.fetchPage("/tournaments?game=PTCG&format=STANDARD") }.getOrNull() }
        val recent = homeTask.await()?.let(OfficialLimitless::parseHomeRecent).orEmpty()
        val completed = listTask.await()?.let(OfficialLimitless::parseList).orEmpty()

        val oldest = now - WEEKS_BACK * Weeks.WEEK
        val seen = HashSet<String>()
        val selected = mutableListOf<Summary>()
        for (t in recent) {
            if (t.date <= now && seen.add(t.id)) selected += Summary(t.id, t.name, t.date, t.players)
        }
        for (t in completed.sortedByDescending { it.date }) {
            if (selected.size >= OFFICIAL_MAX_EVENTS) break
            if (t.date < oldest || t.date > now || !seen.add(t.id)) continue
            selected += Summary(t.id, t.name, t.date, t.players)
        }
        if (selected.isEmpty()) throw LimitlessException(BAD_RESPONSE)

        val records = mutableListOf<TournamentRecord>()
        val pending = mutableListOf<Summary>()
        for (t in selected) {
            val cached = cachedRecord(context, t.id, DataSource.Official)
            if (cached != null) records += cached else pending += t
        }

        // Standings pages give every finisher's deck and decklist link.
        val standingsDone = AtomicInteger(0)
        val standings = pending.mapConcurrent(5) { t ->
            val parsed = runCatching { OfficialLimitless.parseStandings(OfficialLimitless.fetchPage("/tournaments/${t.id}")) }.getOrNull()
            progress(0.05 + 0.15 * standingsDone.incrementAndGet() / maxOf(pending.size, 1))
            t to parsed.orEmpty().filter { it.listId != null }.take(OFFICIAL_LIST_LIMIT)
        }.filter { it.second.isNotEmpty() }

        // Decklists for the top finishers of each event.
        val jobs = standings.flatMap { (t, list) -> list.map { s -> Triple(t.id, s.rank, s.listId.orEmpty()) } }
        val decksDone = AtomicInteger(0)
        val lists = jobs.mapConcurrent(8) { (tid, rank, listId) ->
            val cards = runCatching { OfficialLimitless.parseDecklist(OfficialLimitless.fetchPage("/decks/list/$listId")) }.getOrNull()
            progress(0.20 + 0.80 * decksDone.incrementAndGet() / maxOf(jobs.size, 1))
            "$tid:$rank" to cards.orEmpty()
        }.filter { it.second.isNotEmpty() }.toMap()

        for ((t, list) in standings) {
            val entries = list.mapNotNull { s ->
                val cards = lists["${t.id}:${s.rank}"] ?: return@mapNotNull null
                PlayerEntry(
                    id = "${t.id}-${s.rank}",
                    name = s.name,
                    placing = s.rank,
                    deckName = s.deck,
                    deckIcons = s.icons,
                    cards = cards,
                    cardKeys = cards.map { it.key }.toSet(),
                )
            }
            if (entries.isEmpty()) continue
            val record = TournamentRecord(t.id, t.name, t.date, t.players, entries, DataSource.Official)
            saveCache(context, record)
            records += record
        }
        if (records.isEmpty()) throw LimitlessException(NO_DATA)
        records
    }

    /** Loads the dataset, reporting progress between 0 and 1 (may be called off the main thread). */
    suspend fun loadDataset(context: Context, source: DataSource, progress: (Double) -> Unit): CardDataset {
        val now = System.currentTimeMillis()
        val records = intern(when (source) {
            DataSource.Online -> loadOnline(context) { progress(it * 0.85) }
            DataSource.Official -> loadOfficial(context) { progress(it * 0.85) }
        })
        // Split Pokémon names that cover several distinct cards (e.g. Fire vs Darkness Chi-Yu).
        val variants = runCatching { CardVariants.resolve(context, records) { progress(0.85 + 0.15 * it) } }
            .getOrElse { emptyMap() }
        android.util.Log.i("TCGStats", "Loaded ${records.size} ${source.name} events, ${variants.values.map { it.key }.toSet().size} card variants")
        progress(1.0)
        return CardDataset(records, now, variants)
    }
}
