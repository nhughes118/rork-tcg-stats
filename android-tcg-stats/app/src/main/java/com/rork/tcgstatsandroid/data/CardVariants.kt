package com.rork.tcgstatsandroid.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Gameplay identity of a printed Pokémon card, scraped from its Limitless card page. */
@Serializable
data class CardFingerprint(val type: String, val hp: String, val moves: List<String>) {
    val signature: String get() = "$type|$hp|${moves.joinToString(",")}"
}

/** A distinct gameplay version of a Pokémon that shares its name with other cards. */
data class CardVariant(val key: String, val note: String, val moves: List<String>)

/**
 * Different Pokémon cards often share a name (e.g. Fire Chi-Yu from Twilight Masquerade
 * vs Darkness Chi-Yu from Pitch Black). Decklists only carry name + print, so this
 * looks up each print's type, HP and attacks and splits names into separate variants,
 * while keeping true reprints (same gameplay text) merged.
 */
object CardVariants {
    private const val FILE = "card_fingerprints.json"
    private val serializer = MapSerializer(String.serializer(), CardFingerprint.serializer())

    fun printId(set: String, number: String): String =
        "${set.uppercase()}-${number.toIntOrNull()?.toString() ?: number}"

    private fun load(context: Context): MutableMap<String, CardFingerprint> {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return ConcurrentHashMap()
        return runCatching { ConcurrentHashMap(LimitlessClient.json.decodeFromString(serializer, file.readText())) }
            .getOrElse { ConcurrentHashMap() }
    }

    private fun save(context: Context, map: Map<String, CardFingerprint>) {
        runCatching { File(context.filesDir, FILE).writeText(LimitlessClient.json.encodeToString(serializer, map)) }
    }

    private val tags = Regex("<[^>]+>")
    private val space = Regex("\\s+")
    private val titleRegex = Regex("""<p class="card-text-title">(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
    private val moveRegex = Regex("""<p class="card-text-(?:attack|ability)-info">(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
    private val symbolRegex = Regex("""<span class="ptcg-symbol">.*?</span>""", RegexOption.DOT_MATCHES_ALL)
    private val trailingDamage = Regex("""\s+\d+[+×x\-]?$""")

    private fun clean(html: String): String =
        space.replace(tags.replace(html, " ").replace("&amp;", "&").replace("&#039;", "'"), " ").trim()

    fun parse(html: String): CardFingerprint? {
        val title = titleRegex.find(html)?.groupValues?.get(1)?.let(::clean) ?: return null
        val parts = title.split(" - ").map { it.trim() }
        val type = parts.getOrNull(1).orEmpty()
        val hp = parts.firstOrNull { it.endsWith("HP") }?.removeSuffix("HP")?.trim().orEmpty()
        val moves = moveRegex.findAll(html).map { m ->
            val text = clean(symbolRegex.replace(m.groupValues[1], " ")).removePrefix("Ability:").trim()
            trailingDamage.replace(text, "").trim()
        }.filter { it.isNotEmpty() }.toList()
        if (type.isEmpty() && moves.isEmpty()) return null
        return CardFingerprint(type, hp, moves)
    }

    /**
     * Returns print id → variant for every Pokémon name that maps to more than one
     * distinct card. Prints without a known fingerprint fall back to the plain name.
     */
    suspend fun resolve(
        context: Context,
        records: List<TournamentRecord>,
        progress: (Double) -> Unit = {},
    ): Map<String, CardVariant> = coroutineScope {
        val printsByName = HashMap<String, MutableSet<String>>()
        for (t in records) for (e in t.entries) for (c in e.cards) {
            if (c.category != CardCategory.Pokemon || c.set.isEmpty() || c.number.isEmpty()) continue
            printsByName.getOrPut(c.key) { HashSet() } += printId(c.set, c.number)
        }
        val ambiguous = printsByName.filterValues { it.size > 1 }
        if (ambiguous.isEmpty()) return@coroutineScope emptyMap()

        val known = load(context)
        val missing = ambiguous.values.flatten().filter { it !in known }
        if (missing.isNotEmpty()) {
            val semaphore = Semaphore(8)
            val done = AtomicInteger(0)
            missing.map { id ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val (set, number) = id.split("-", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                        runCatching { parse(OfficialLimitless.fetchPage("/cards/$set/$number")) }
                            .onFailure { android.util.Log.w("TCGStats", "Card page $id failed: ${it.javaClass.simpleName}") }
                            .getOrNull()?.let { known[id] = it }
                        progress(done.incrementAndGet().toDouble() / missing.size)
                    }
                }
            }.awaitAll()
            save(context, known)
        }

        val result = HashMap<String, CardVariant>()
        for ((nameKey, prints) in ambiguous) {
            val groups = prints.mapNotNull { id -> known[id]?.let { id to it } }.groupBy { it.second.signature }
            if (groups.size < 2) continue
            for ((_, members) in groups) {
                val fp = members.first().second
                val label = fp.moves.firstOrNull() ?: fp.type
                val slug = CardText.normalize(fp.moves.joinToString(" ").ifEmpty { "${fp.type} ${fp.hp}" })
                    .filter { it.isLetterOrDigit() || it == ' ' }.trim().replace(' ', '-')
                val note = listOf(fp.type, fp.moves.firstOrNull()).filter { !it.isNullOrEmpty() }.joinToString(" · ")
                val variant = CardVariant("$nameKey~$slug", note.ifEmpty { label }, fp.moves)
                members.forEach { (id, _) -> result[id] = variant }
            }
        }
        result
    }
}
