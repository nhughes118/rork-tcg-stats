package com.rork.tcgstatsandroid.data

import kotlinx.serialization.json.JsonArray
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

    private suspend fun get(path: String): Any? {
        val text = LimitlessClient.httpGet("$API/$path", "application/json", OfficialLimitless.USER_AGENT)
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
