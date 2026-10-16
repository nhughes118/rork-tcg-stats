package com.rork.tcgstatsandroid.data

/** A line of text recognized on a camera frame. [midY] is normalized (0 bottom, 1 top). */
data class RecognizedLine(val text: String, val midY: Double)

/** Matches OCR text from a card photo to a known competitive card. */
class CardMatcher(dataset: CardDataset) {
    private class Candidate(val key: String, val padded: String, val basePadded: String?)

    private val keys: Set<String> = dataset.cards.keys
    private val candidates: List<Candidate> = dataset.cards.keys.map { key ->
        val base = if (key.endsWith(" ex")) key.dropLast(3) else null
        Candidate(key, " $key ", base?.let { " $it " })
    }
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
        return if (bestScore >= 7) bestKey else null
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
