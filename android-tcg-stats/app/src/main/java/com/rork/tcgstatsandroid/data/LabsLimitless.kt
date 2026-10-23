package com.rork.tcgstatsandroid.data

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Limitless Labs (labs.limitlesstcg.com): full standings and every player's
 * decklist for official Regionals, Internationals, Special Events and Worlds.
 * Data comes from the JSON API the Labs site itself uses.
 */
object LabsLimitless {
    private const val API = "https://mew.limitlesstcg.com/labs/data/tcg"
    const val ID_PREFIX = "labs-"
    /** Requests in flight at once; HTTP/2 multiplexes them over a single connection. */
    const val CONCURRENCY = 64

    /** One shared client: HTTP/2, kept-alive connection, transparent gzip. */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequests = CONCURRENCY; maxRequestsPerHost = CONCURRENCY })
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private suspend fun fetch(url: String): String = suspendCancellableCoroutine { cont ->
        val call = client.newCall(
            Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("User-Agent", OfficialLimitless.USER_AGENT)
                .build()
        )
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = runCatching { it.body?.string() }.getOrNull()
                    when {
                        !cont.isActive -> Unit
                        it.isSuccessful && body != null -> cont.resume(body)
                        else -> cont.resumeWithException(LimitlessException("Limitless Labs returned ${it.code}."))
                    }
                }
            }
        })
    }

    data class LabsTournament(val id: String, val name: String, val date: Long, val completed: Boolean)

    data class LabsStanding(
        val tpId: Int,
        val name: String,
        val placing: Int?,
        val wins: Int,
        val losses: Int,
        val ties: Int,
        val deckName: String,
        val icons: List<String>,
        val hasList: Boolean,
    )

    fun isLabsId(id: String) = id.startsWith(ID_PREFIX)
    fun labsId(recordId: String) = recordId.removePrefix(ID_PREFIX)
    fun pageUrl(recordId: String) = "https://labs.limitlesstcg.com/${labsId(recordId)}/standings"

    data class PlayerRef(val tournament: String, val player: Int)

    /** Labs tournament and player ids encoded in a Labs player entry id (`labs-0076-445`). */
    fun playerRef(entryId: String): PlayerRef? {
        if (!isLabsId(entryId)) return null
        val parts = entryId.removePrefix(ID_PREFIX).split("-")
        if (parts.size != 2) return null
        val player = parts[1].toIntOrNull() ?: return null
        return PlayerRef(parts[0], player)
    }

    fun playerUrl(ref: PlayerRef) = "https://labs.limitlesstcg.com/${ref.tournament}/player/${ref.player}"

    enum class MatchResult { Win, Loss, Tie }

    data class LabsMatch(
        val round: Int,
        val isTopCut: Boolean,
        /** Null for a bye. */
        val opponent: String?,
        val opponentDeck: String,
        val opponentIcons: List<String>,
        val opponentRecord: String,
        val result: MatchResult,
    )

    /** Every round a player played, latest first. */
    suspend fun matches(ref: PlayerRef): List<LabsMatch> {
        val list = get("matches?tournamentId=${ref.tournament}&playerId=${ref.player}") as? JsonArray
            ?: throw LimitlessException("Matches unavailable.")
        return list.mapNotNull { element ->
            val m = element as? JsonObject ?: return@mapNotNull null
            val round = m.int("round") ?: return@mapNotNull null
            val isP1 = m.int("p1_id") == ref.player
            val opp = if (isP1) "p2" else "p1"
            val oppId = m.int("${opp}_id")
            val winner = m.int("winner")
            val result = when {
                oppId == null || winner == ref.player -> MatchResult.Win
                winner == 0 -> MatchResult.Tie
                else -> MatchResult.Loss
            }
            val w = m.int("${opp}_wins") ?: 0
            val l = m.int("${opp}_losses") ?: 0
            val t = m.int("${opp}_ties") ?: 0
            LabsMatch(
                round = round,
                isTopCut = (m.int("phase") ?: 1) >= 3,
                opponent = if (oppId == null) null else m.str("${opp}_name") ?: "Player",
                opponentDeck = m.str("${opp}_deck_name")?.takeIf { it.isNotBlank() } ?: if (oppId == null) "Bye" else "Unknown deck",
                opponentIcons = m.str("${opp}_icons").orEmpty().split(" ").filter { it.isNotBlank() },
                opponentRecord = if (t > 0) "$w-$l-$t" else "$w-$l",
                result = result,
            )
        }.sortedByDescending { it.round }
    }

    private suspend fun get(path: String): Any? {
        val text = fetch("$API/$path")
        val root = LimitlessClient.json.parseToJsonElement(text) as? JsonObject ?: return null
        if ((root["ok"] as? JsonPrimitive)?.contentOrNull != "true") return null
        return root["message"]
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }

    private fun typeLabel(type: String?) = when (type) {
        "regional" -> "Regionals"
        "international" -> "Internationals"
        "special" -> "Special Event"
        "worlds" -> "World Championship"
        else -> "Championship"
    }

    suspend fun tournaments(): List<LabsTournament> {
        val list = get("tournaments") as? JsonArray ?: return emptyList()
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return list.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val id = o.int("id") ?: return@mapNotNull null
            val date = o.str("utc_start")?.let { runCatching { format.parse(it)?.time }.getOrNull() } ?: return@mapNotNull null
            val name = o.str("name")?.takeIf { it.isNotBlank() } ?: "${o.str("city") ?: "Tournament"} ${typeLabel(o.str("type"))}"
            LabsTournament(id.toString().padStart(4, '0'), name, date, o.int("completed") == 1)
        }
    }

    suspend fun standings(labsId: String): List<LabsStanding> {
        val list = get("standings?tournamentId=$labsId&division=MA") as? JsonArray ?: return emptyList()
        return list.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val tp = o.int("tp_id") ?: return@mapNotNull null
            LabsStanding(
                tpId = tp,
                name = o.str("name") ?: "Player",
                placing = o.int("placement"),
                wins = o.int("wins") ?: 0,
                losses = o.int("losses") ?: 0,
                ties = o.int("ties") ?: 0,
                deckName = o.str("deck_name")?.takeIf { it.isNotBlank() } ?: "Other",
                icons = o.str("icons").orEmpty().split(" ").filter { it.isNotBlank() },
                hasList = o.int("decklist") == 1,
            )
        }.sortedBy { it.placing ?: Int.MAX_VALUE }
    }

    suspend fun decklist(labsId: String, tpId: Int): List<DeckCard> {
        val o = get("decklist?tournamentId=$labsId&playerId=$tpId") as? JsonObject ?: return emptyList()
        return listOf(
            "pokemon" to CardCategory.Pokemon,
            "trainer" to CardCategory.Trainer,
            "energy" to CardCategory.Energy,
        ).flatMap { (field, category) ->
            (o[field] as? JsonArray).orEmpty().mapNotNull { item ->
                val c = item as? JsonObject ?: return@mapNotNull null
                val name = c.str("name") ?: return@mapNotNull null
                DeckCard(c.int("count") ?: 1, name, c.str("set") ?: "", c.str("number") ?: "", category)
            }
        }
    }
}
