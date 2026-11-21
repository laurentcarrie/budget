package com.perso.budget

object Parser {
    private val skip = listOf(
        "ticket", "facture", "tel", "tél", "siret", "www", "http", "merci", "caisse", "date", "tva", "total", "net",
        "carte", "reçu", "recu", "terminal", "acquereur", "acquéreur", "autorisation", "transaction", "paiement",
        "amount", "montant", "rue", "avenue", "boulevard", "paris", "france", "cedex", "visa", "mastercard", "cb",
        "sans contact", "signature", "brut", "ref", "id ", "customn", "custom", "pan", "emv", "aid", "atc", "contact"
    )

    private val amountRe1 = Regex("""(\d{1,5})\s?[.,]\s?(\d{1,2})(?!\d)""")
    private val euroRe = Regex("""€|\b[EF][UIL]R\b""", RegexOption.IGNORE_CASE)

    // "14.8" is read as 14.80: a single decimal digit is a tenth, not a cent.
    private fun nums(s: String) = amountRe1.findAll(s).map {
        val d = it.groupValues[2]
        "${it.groupValues[1]}.${if (d.length == 1) d + "0" else d}".toDouble()
    }.toList()

    private fun isDateOrTime(l: String) = l.contains(':') || l.contains('/') || Regex("""\d{1,2}[.:]\d{2}[.:]\d{2}""").containsMatchIn(l)

    fun amount(text: String): Double? {
        val lines = text.lines().filter { !isDateOrTime(it) }
        // The OCR often splits "Total" from its value, so also look at amounts carrying a currency sign.
        val euro = lines.filter { euroRe.containsMatchIn(it) }.flatMap { nums(it) }
        if (euro.isNotEmpty()) {
            val top = euro.groupingBy { it }.eachCount().maxBy { it.value }.value
            return euro.filter { v -> euro.count { it == v } == top }.max()
        }
        val strong = lines.filter { l -> l.lowercase().let { it.contains("total") || it.contains("payer") || it.contains("montant") } }
            .flatMap { nums(it) }
        if (strong.isNotEmpty()) return strong.max()
        return lines.flatMap { nums(it) }.maxOrNull()
    }

    private val dateLineRe = Regex("""(?i)^\s*le\s+\d{1,2}\s*[/.]\s*[0-9OIl]{1,2}\s*[/.]\s*\d{2,4}""")
    private val footerWords = setOf("debit", "credit", "ticket", "client", "conserver", "comptant", "montant", "total", "net", "merci",
        "carte", "bancaire", "contact", "paiement", "visa", "mastercard", "autorisation", "auto", "terminal", "transaction", "signature")

    /** On card-terminal receipts the shop is printed under the "LE 14/11/26 A 17:56:36" line, before the footer. */
    private fun belowDateLine(text: String): String? {
        val lines = text.lines().map { it.trim() }
        val i = lines.indexOfFirst { dateLineRe.containsMatchIn(it) }
        if (i < 0) return null
        return lines.drop(i + 1).take(3).firstOrNull { l ->
            l.count { it.isLetter() } >= 3 && l.length <= 40 && !Regex("^[\\d\\s]+$").matches(l) &&
                l.lowercase().split(Regex("[^a-z\u00e0-\u00ff]+")).none { it in footerWords }
        }
    }

    fun shop(text: String): String? {
        belowDateLine(text)?.let { return it }
        val cands = text.lines().map { it.trim() }.filter { l ->
            val low = l.lowercase()
            l.count { it.isLetter() } >= 4 && l.length <= 40 && !l[0].isDigit() &&
                skip.none { low.contains(it) } && l.count { it.isDigit() } <= 2
        }
        // Shop names are usually printed in capitals, on several words.
        val caps = cands.filter { l -> l.contains(' ') && l.none { it.isDigit() } && l.filter { it.isLetter() }.all { it.isUpperCase() } }
            .maxByOrNull { it.length }
        return caps ?: cands.firstOrNull()
    }

    // Letters the OCR commonly swaps: replacing one by the other costs little.
    private val confusable = listOf("it", "il", "i1", "tl", "0o", "oq", "8b", "5s", "mh", "ef", "cg", "uv", "z2", "ao", "rn", "un")
        .map { setOf(it[0], it[1]) }

    private fun subst(a: Char, b: Char): Double = when {
        a == b -> 0.0
        setOf(a, b) in confusable -> 0.3
        else -> 1.0
    }

    private fun distance(a: String, b: String): Double {
        var prev = DoubleArray(b.length + 1) { it.toDouble() }
        for (i in 1..a.length) {
            val cur = DoubleArray(b.length + 1); cur[0] = i.toDouble()
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + subst(a[i - 1], b[j - 1]))
            prev = cur
        }
        return prev[b.length]
    }

    /** A previously saved shop that matches a line of the OCR text, tolerating about 20% of OCR typos. */
    fun knownShop(text: String, known: List<String>): String? {
        val lines = text.lines().map { it.trim().lowercase() }.filter { it.length >= 3 }
        return known.filter { it.isNotBlank() && it != "Unknown" }
            .map { k -> k to (lines.minOfOrNull { distance(it, k.lowercase()) } ?: Double.MAX_VALUE) }
            .filter { (k, d) -> d <= k.length / 5.0 }
            .minByOrNull { it.second }?.first
    }

    private val dateRe = Regex("""([0-9OIl]{1,2})\s?[/.\-]\s?([0-9OIl]{1,2})\s?[/.\-]\s?((?:20)?[0-9OIl]{2})(.{0,25})""")
    // "12 novembre 2026", "3 janv. 2026": the month is matched on its first letters, accents removed.
    private val months = listOf("jan", "fev", "mar", "avr", "mai", "juin", "juil", "aou", "sep", "oct", "nov", "dec")
    private val dateWordsRe = Regex("""\b([0-3]?\d)\s+(juin|juil|jan|fev|mar|avr|mai|aou|sep|oct|nov|dec)[a-z]*\.?\s+((?:20)?\d{2})\b(.{0,25})""")
    private val timeRe = Regex("""\b([0-2]?\d)\s?[:.h]\s?([0-5]\d)""")

    private fun digits(s: String) = s.replace('O', '0').replace('o', '0').replace('I', '1').replace('l', '1').toIntOrNull()

    private fun unaccent(s: String) =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /**
     * The ticket date as "yyyy-MM-dd" or "yyyy-MM-dd HH:mm". Dates in the future are rejected, and when the
     * OCR drops a digit of a month ("19/1/2026" for 19/11/2026) the candidate closest to today wins.
     */
    fun date(text: String, today: java.time.LocalDate = java.time.LocalDate.now()): String? {
        val found = mutableListOf<Pair<java.time.LocalDate, String?>>()
        fun add(d: Int, months: List<Int>, year: Int, tail: String) {
            val y = if (year < 100) year + 2000 else year
            val t = timeRe.find(tail)?.let { "%02d:%s".format(it.groupValues[1].toInt(), it.groupValues[2]) }
            for (month in months) {
                val dt = try { java.time.LocalDate.of(y, month, d) } catch (e: Exception) { continue }
                if (!dt.isAfter(today) && y in 2000..2100) found += dt to t
            }
        }
        for (m in dateRe.findAll(text)) {
            val d = digits(m.groupValues[1]) ?: continue
            val mo = digits(m.groupValues[2]) ?: continue
            val y = digits(m.groupValues[3]) ?: continue
            add(d, if (mo == 1) listOf(1, 10, 11, 12) else listOf(mo), y, m.groupValues[4])
        }
        for (m in dateWordsRe.findAll(unaccent(text).lowercase())) {
            val d = m.groupValues[1].toInt()
            val mo = months.indexOf(m.groupValues[2]) + 1
            val y = m.groupValues[3].toIntOrNull() ?: continue
            if (mo > 0) add(d, listOf(mo), y, m.groupValues[4])
        }
        val best = found.groupingBy { it }.eachCount().entries
            .maxWithOrNull(compareBy<Map.Entry<Pair<java.time.LocalDate, String?>, Int>> { it.key.first }.thenBy { it.value })
            ?: return null
        val (dt, t) = best.key
        return if (t != null) "$dt $t" else dt.toString()
    }
}
