package com.rork.tcgstatsandroid.data

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

class LeaderboardException(message: String) : Exception(message)

enum class Division(val slug: String, val label: String) {
    Masters("masters", "Masters"),
    Seniors("seniors", "Seniors"),
    Juniors("juniors", "Juniors");

    /** The division's leaderboard on Pokémon's championships site. */
    val pageUrl: String
        get() = "https://championships.pokemon.com/en-us/competitors/leaderboards/?pillar=tcg&division=$slug"
}

/** Play! Pokémon championship regions. Each zone has its own leaderboard with region-specific ranks. */
enum class LeaderboardRegion(val code: String, val type: String, val label: String) {
    Global("global", "global", "Global"),
    NorthAmerica("NA", "zone", "US & Canada"),
    Europe("EU", "zone", "Europe"),
    LatinAmerica("LA", "zone", "Latin America"),
    Oceania("AP", "zone", "Oceania"),
    MiddleEast("SO", "zone", "Middle East & S. Africa"),
    Russia("RU", "zone", "Russia");
}

@Serializable
data class LeaderboardPlayer(
    val rank: Int,
    val name: String,
    val points: Int,
    val country: String? = null,
    val countryCode: String? = null,
    val inCutoff: Boolean = false,
) {
    val key: String get() = "$rank|$name"
}

@Serializable
data class LeaderboardIndex(
    val division: String,
    val fetchedAt: Long,
    val total: Int,
    val complete: Boolean,
    val players: List<LeaderboardPlayer>,
    /** Season label shown in the UI, e.g. "2026". */
    val season: String? = null,
    /** Code of [LeaderboardRegion] this index was built for. */
    val region: String = LeaderboardRegion.Global.code,
)

/**
 * Downloads the Play! Pokémon TCG Championship Points leaderboard from the championships site's API
 * (api.play.pokemon.com), which is open — no bot check. The site has no name search, so the whole
 * division is indexed once (cached on disk for [CACHE_TTL_MS]) and searched locally.
 */
object Leaderboard {
    /** The championships site's content store: source of the live region, division and season filters. */
    private const val STORE_URL =
        "https://championships.pokemon.com/api/1/site/content_store/item.json" +
            "?url=/site/website/en-us/competitors/leaderboards/index.xml&flatten=true"
    private const val API = "https://api.play.pokemon.com/services/spar/leaderboards/"
    private const val CACHE_TTL_MS = 12 * 3_600_000L
    private const val PERIODS_TTL_MS = 24 * 3_600_000L
    private const val PER_PAGE = 200
    private const val CONCURRENCY = 8
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    /** Season fallback if the championships site can't be read (2027 Worlds qualification). */
    private val FALLBACK_PERIOD = LeaderboardPeriod("1c13d02f09f3f0713f7e41986bef2f36", "2027 World Championships")

    /** A championship-points season/cycle as listed in the championships site's filters. */
    data class LeaderboardPeriod(val guid: String, val label: String)

    fun pageUrl(division: Division): String = division.pageUrl

    // ---------- Cache ----------

    private fun cacheFile(context: Context, division: Division, region: LeaderboardRegion): File {
        val suffix = if (region == LeaderboardRegion.Global) "" else "-${region.code.lowercase(Locale.US)}"
        return File(context.filesDir, "leaderboard").apply { mkdirs() }.let { File(it, "${division.slug}$suffix-v4.json") }
    }

    private fun periodsFile(context: Context): File =
        File(File(context.filesDir, "leaderboard").apply { mkdirs() }, "periods-v2.json")

    suspend fun cached(context: Context, division: Division, region: LeaderboardRegion): LeaderboardIndex? = withContext(Dispatchers.IO) {
        val file = cacheFile(context, division, region)
        if (!file.exists()) return@withContext null
        runCatching { LimitlessClient.json.decodeFromString(LeaderboardIndex.serializer(), file.readText()) }.getOrNull()
            ?.takeIf { it.division == division.slug && it.region == region.code }
    }

    fun isFresh(index: LeaderboardIndex): Boolean =
        index.complete && System.currentTimeMillis() - index.fetchedAt < CACHE_TTL_MS

    private suspend fun save(context: Context, division: Division, region: LeaderboardRegion, index: LeaderboardIndex) = withContext(Dispatchers.IO) {
        runCatching {
            val file = cacheFile(context, division, region)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(LimitlessClient.json.encodeToString(LeaderboardIndex.serializer(), index))
            tmp.renameTo(file)
        }
    }

    // ---------- Season filters ----------

    /**
     * The championships site's own default season (its "year" dropdown, which mixes whole seasons
     * and single events): the first entry in the store's list — currently 2027 Worlds qualification.
     */
    private suspend fun currentPeriod(context: Context): LeaderboardPeriod = withContext(Dispatchers.IO) {
        val file = periodsFile(context)
        if (file.exists()) {
            val saved = runCatching {
                val cache = LimitlessClient.json.decodeFromString(PeriodsCache.serializer(), file.readText())
                val list = cache.periods.mapNotNull { p ->
                    LeaderboardPeriod(p.guid, p.name).takeIf { p.name.isNotEmpty() }
                }
                if (cache.fetchedAt > 0 && list.isNotEmpty()) cache.fetchedAt to list else null
            }.getOrNull()
            if (saved != null && System.currentTimeMillis() - saved.first < PERIODS_TTL_MS) {
                return@withContext saved.second.first()
            }
        }
        val fetched = runCatching {
            val text = LimitlessClient.httpGet(STORE_URL, "application/json", UA)
            val root = LimitlessClient.json.parseToJsonElement(text).jsonObject
            val raw = root.findString("periodsjson_t") ?: return@runCatching null
            val results = LimitlessClient.json.parseToJsonElement(raw).jsonObject["results"] as? JsonArray
                ?: return@runCatching null
            val periods = results.mapNotNull { p ->
                val po = p as? JsonObject ?: return@mapNotNull null
                val guid = po.str("guid") ?: return@mapNotNull null
                val name = po.str("name") ?: return@mapNotNull null
                LeaderboardPeriod(guid, name)
            }
            if (periods.isEmpty()) return@runCatching null
            periods
        }.getOrNull()
        if (fetched != null && fetched.isNotEmpty()) {
            runCatching {
                val payload = LimitlessClient.json.encodeToString(
                    PeriodsCache.serializer(),
                    PeriodsCache(System.currentTimeMillis(), fetched.map { PeriodDTO(it.guid, it.label) }),
                )
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(payload)
                tmp.renameTo(file)
            }
            return@withContext fetched.first()
        }
        FALLBACK_PERIOD
    }

    /** Depth-first search for a string field somewhere in the content-store payload. */
    private fun JsonObject.findString(key: String): String? {
        for ((k, v) in this) {
            if (k == key) return (v as? JsonPrimitive)?.contentOrNull
            when (v) {
                is JsonObject -> v.findString(key)?.let { return it }
                is JsonArray -> for (e in v) (e as? JsonObject)?.findString(key)?.let { return it }
                else -> {}
            }
        }
        return null
    }

    @Serializable
    private data class PeriodDTO(val guid: String, val name: String)

    @Serializable
    private data class PeriodsCache(val fetchedAt: Long, val periods: List<PeriodDTO>)

    // ---------- Fetching ----------

    private class Board(val players: List<LeaderboardPlayer>, val total: Int)

    private suspend fun fetchPage(division: Division, region: LeaderboardRegion, period: String, page: Int): Board {
        val url = "$API?product=tcg&region=${region.code}&region_type=${region.type}&division=${division.slug}" +
            "&period=$period&page_size=$PER_PAGE&page=$page&point_type=championship&sort_by=ranking_order:asc"
        var attempt = 0
        while (true) {
            try {
                val text = LimitlessClient.httpGet(url, "application/json", UA)
                return parse(text) ?: throw LeaderboardException("Pokémon's leaderboard didn't respond as expected.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: LeaderboardException) {
                throw e
            } catch (e: Exception) {
                attempt++
                if (attempt >= 3) throw LeaderboardException("Pokémon's leaderboard didn't respond. Check your connection and try again.")
                delay(800L * attempt)
            }
        }
    }

    private fun parse(text: String): Board? = runCatching {
        val root = LimitlessClient.json.parseToJsonElement(text).jsonObject
        val results = root["results"] as? JsonArray ?: return null
        val players = results.mapIndexedNotNull { i, el ->
            val o = el as? JsonObject ?: return@mapIndexedNotNull null
            LeaderboardPlayer(
                rank = o.int("rank") ?: (i + 1),
                name = o.str("display_name") ?: return@mapIndexedNotNull null,
                points = o.int("primary_point_total") ?: 0,
                country = o.str("player_country"),
                countryCode = o.str("player_country_code"),
            )
        }
        Board(players, root.int("count") ?: players.size)
    }.getOrNull()

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun JsonObject.int(key: String): Int? {
        val p = this[key] as? JsonPrimitive ?: return null
        return p.intOrNull ?: p.contentOrNull?.toIntOrNull()
    }

    /**
     * Indexes [division]. [onProgress] receives the players collected so far (unsorted) and the expected total.
     */
    suspend fun load(
        activity: Activity,
        division: Division,
        region: LeaderboardRegion,
        onProgress: suspend (List<LeaderboardPlayer>, Int) -> Unit,
    ): LeaderboardIndex {
        val period = currentPeriod(activity.applicationContext)
        val first = fetchPage(division, region, period.guid, 1)
        if (first.players.isEmpty()) {
            throw LeaderboardException("The ${division.label} ${if (region == LeaderboardRegion.Global) "" else "${region.label} "}leaderboard came back empty.")
        }
        val total = first.total
        val pageCount = ceil(total / PER_PAGE.toDouble()).toInt().coerceAtLeast(1)
        Log_d("${division.slug}: total=$total pages=$pageCount season=${period.label}")

        val collected = ConcurrentHashMap<String, LeaderboardPlayer>()
        first.players.forEach { collected[it.key] = it }
        onProgress(collected.values.toList(), total)

        val failed = AtomicInteger(0)
        val progressMutex = Mutex()
        if (pageCount > 1) {
            val semaphore = Semaphore(CONCURRENCY)
            coroutineScope {
                (2..pageCount).map { page ->
                    async {
                        semaphore.withPermit {
                            val result = runCatching { fetchPage(division, region, period.guid, page) }
                            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                            val players = result.getOrNull()?.players
                            if (players == null) failed.incrementAndGet() else players.forEach { collected[it.key] = it }
                            progressMutex.withLock { onProgress(collected.values.toList(), total) }
                        }
                    }
                }.awaitAll()
            }
        }
        val players = withContext(Dispatchers.Default) { collected.values.sortedBy { it.rank } }
        val index = LeaderboardIndex(
            division = division.slug,
            fetchedAt = System.currentTimeMillis(),
            total = total,
            complete = failed.get() == 0,
            players = players,
            season = period.label,
            region = region.code,
        )
        Log_d("${division.slug}: indexed ${players.size}/$total (failed pages ${failed.get()})")
        save(activity.applicationContext, division, region, index)
        return index
    }

    private fun Log_d(message: String) = android.util.Log.d("Leaderboard", message)

    // ---------- Search ----------

    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.US)

    private fun tokens(s: String): List<String> = normalize(s).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    /** Players whose name contains every token of [query], best rank first. */
    fun search(players: List<LeaderboardPlayer>, normalizedNames: List<String>, query: String, limit: Int = 200): List<LeaderboardPlayer> {
        val q = tokens(query)
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<LeaderboardPlayer>()
        for (i in players.indices) {
            val n = normalizedNames.getOrNull(i) ?: normalize(players[i].name)
            if (q.all { n.contains(it) }) out += players[i]
        }
        out.sortBy { it.rank }
        return if (out.size > limit) out.subList(0, limit) else out
    }
}
