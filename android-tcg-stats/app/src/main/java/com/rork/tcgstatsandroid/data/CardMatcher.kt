package com.rork.tcgstatsandroid.data

/** A line of text recognized on a camera frame. [midY] is normalized (0 bottom, 1 top). */
data class RecognizedLine(val text: String, val midY: Double)

/** Matches OCR text from a card photo to a known competitive card. */
class CardMatcher(dataset: CardDataset) {
    private class Candidate(val key: String, val padded: String, val basePadded: String?)

    /** Matching runs on plain names; variants of a name are resolved afterwards. */
    private val keys: Set<String> = dataset.keysByName.keys
    private val candidates: List<Candidate> = keys.map { key ->
        val base = if (key.endsWith(" ex")) key.dropLast(3) else null
        Candidate(key, " $key ", base?.let { " $it " })
    }
    private val keysByName = dataset.keysByName
    private val variantMoves: Map<String, List<String>> =
        dataset.variantMoves.mapValues { (_, moves) -> moves.map(CardText::normalize).filter { it.length >= 3 } }
    private val printIndex = dataset.printIndex
    private val setCodes = dataset.setCodes
    private val numberRegex = Regex("(\\d{1,3})\\s*/\\s*(\\d{2,3})")
    private val splitRegex = Regex("[^A-Za-z0-9]+")

    fun match(lines: List<RecognizedLine>): String? {
        if (lines.isEmpty()) return null
        val fullRaw = lines.joinToString(" ") { it.text }
        val full = CardText.normalize(fullRaw)

        matchPrint(fullRaw)?.let { return it }

        val hasExEvidence = full.contains("ex rule") || full.contains("pokemon ex") || full.contains(" ex ")
        var bestKey: String? = null
        var bestScore = 0.0

        for (line in lines) {
            val norm = CardText.normalize(line.text)
            if (norm.length < 3) continue
            val padded = " $norm "
            val topBonus = line.midY * 12
            for (c in candidates) {
                var score: Double? = null
                if (padded.contains(c.padded)) {
                    score = c.key.length + topBonus + if (norm == c.key) 6.0 else 0.0
                } else if (c.basePadded != null && padded.contains(c.basePadded)) {
                    val baseKey = c.key.dropLast(3)
                    if (baseKey in keys && !hasExEvidence) continue
                    score = baseKey.length + topBonus + 0.5
                }
                if (score != null && score > bestScore) {
                    bestScore = score
                    bestKey = c.key
                }
            }
        }
        val name = if (bestScore >= 7) bestKey else null
        return name?.let { resolveVariant(it, full) }
    }

    /** Picks the variant whose attack or ability names appear on the card, else the most played. */
    private fun resolveVariant(nameKey: String, full: String): String? {
        val options = keysByName[nameKey] ?: return null
        if (options.size == 1) return options.first()
        val padded = " $full "
        return options.maxByOrNull { key ->
            variantMoves[key].orEmpty().count { padded.contains(" $it ") }
        }?.takeIf { key -> variantMoves[key].orEmpty().any { padded.contains(" $it ") } }
            ?: options.first()
    }

    private fun matchPrint(fullRaw: String): String? {
        val numbers = numberRegex.findAll(fullRaw).mapNotNull { it.groupValues[1].toIntOrNull()?.toString() }.toList()
        if (numbers.isEmpty()) return null
        val tokens = fullRaw.split(splitRegex).filter {
            it.length in 2..4 && it == it.uppercase() && it in setCodes
        }
        for (set in tokens) for (n in numbers) printIndex["$set-$n"]?.let { return it }
        return null
    }
}
