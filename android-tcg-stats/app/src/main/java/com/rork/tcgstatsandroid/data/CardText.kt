package com.rork.tcgstatsandroid.data

import java.text.Normalizer
import java.util.Locale

/** Text helpers shared by card matching, indexing and display. */
object CardText {
    private val marks = Regex("\\p{Mn}+")

    fun normalize(s: String): String {
        val folded = Normalizer.normalize(s.replace('’', '\''), Normalizer.Form.NFD)
            .replace(marks, "")
            .lowercase(Locale.US)
        val out = StringBuilder(folded.length)
        var lastSpace = true
        for (ch in folded) {
            if (ch.isLetterOrDigit() || ch == '\'' || ch == '-') {
                out.append(ch)
                lastSpace = false
            } else if (!lastSpace) {
                out.append(' ')
                lastSpace = true
            }
        }
        return out.toString().trim()
    }

    fun key(name: String): String = normalize(name)

    fun ordinal(n: Int?): String {
        if (n == null) return "—"
        val suffix = when {
            n % 100 in 11..13 -> "th"
            n % 10 == 1 -> "st"
            n % 10 == 2 -> "nd"
            n % 10 == 3 -> "rd"
            else -> "th"
        }
        return "$n$suffix"
    }

    fun percent(v: Double, digits: Int = 1): String =
        String.format(Locale.US, "%.${digits}f%%", v * 100)
}
