package com.perso.budget

import java.text.Normalizer
import java.util.Locale

/**
 * Fuzzy search of the ticket list. Every word typed must be found in the date, the shop, the amount or the
 * description of a ticket. Case, accents and emoji do not matter, a word can be cut short ("boulang"), and a word of
 * three letters or more can have a typo ("boulangrie", "carbonne").
 */
object Search {
    private fun plain(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

    private fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    private fun fuzzyWord(t: String, w: String): Boolean {
        val tol = if (t.length <= 5) 1 else 2
        // the word itself, or its beginning (the word may not be finished)
        return listOf(w, w.take(t.length), w.take(t.length + 1), w.take(maxOf(t.length - 1, 1))).any { distance(t, it) <= tol }
    }

    fun matches(query: String, text: String): Boolean {
        val tokens = plain(query).replace(',', '.').split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return true
        val hay = plain(text)
        val words by lazy { hay.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() } }
        return tokens.all { t ->
            hay.contains(t) || (t.length >= 3 && t.none { it.isDigit() } && words.any { fuzzyWord(t, it) })
        }
    }

    fun matches(query: String, r: Receipt) =
        matches(query, "${r.date} ${r.shop} ${r.category} ${"%.2f".format(Locale.US, r.amount)} ${r.description}")
}
