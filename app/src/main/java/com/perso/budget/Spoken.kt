package com.perso.budget

import java.text.Normalizer

/**
 * Understands what a French speaker says for a ticket: the amount ("quatorze euros quatre-vingts", "14 euros 80",
 * "douze cinquante", "14,80") and the shop ("chez Carbone", "à la boulangerie").
 */
object Spoken {
    private val units = mapOf(
        "zero" to 0, "un" to 1, "une" to 1, "deux" to 2, "trois" to 3, "quatre" to 4, "cinq" to 5, "six" to 6, "sept" to 7,
        "huit" to 8, "neuf" to 9, "dix" to 10, "onze" to 11, "douze" to 12, "treize" to 13, "quatorze" to 14, "quinze" to 15,
        "seize" to 16)
    private val tens = mapOf(
        "vingt" to 20, "vingts" to 20, "trente" to 30, "quarante" to 40, "cinquante" to 50, "soixante" to 60,
        "septante" to 70, "huitante" to 80, "nonante" to 90)

    private sealed class Tok {
        class Num(val v: Int, val digits: Boolean) : Tok()
        object Euro : Tok()
        object Cents : Tok()
        object Sep : Tok()
        object Other : Tok()
    }

    private sealed class Item {
        class Group(val v: Int) : Item()
        object Euro : Item()
        object Cents : Item()
        object Sep : Item()
    }

    private fun unaccent(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    private fun tokens(text: String): List<Tok> {
        val words = unaccent(text.lowercase()).replace("€", " euro ").split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        val out = mutableListOf<Tok>()
        var i = 0
        while (i < words.size) {
            val w = words[i]
            when {
                w == "quatre" && words.getOrNull(i + 1).let { it == "vingt" || it == "vingts" } -> { out += Tok.Num(80, false); i++ }
                w.all { it.isDigit() } && w.length <= 4 -> out += Tok.Num(w.toInt(), true)
                w in units -> out += Tok.Num(units.getValue(w), false)
                w in tens -> out += Tok.Num(tens.getValue(w), false)
                w == "cent" || w == "cents" -> out += Tok.Num(100, false)
                w == "euro" || w == "euros" || w == "eur" -> out += Tok.Euro
                w == "centime" || w == "centimes" || w == "cts" -> out += Tok.Cents
                w == "virgule" -> out += Tok.Sep
                w == "et" -> {}
                else -> out += Tok.Other
            }
            i++
        }
        return out
    }

    /** The number written in words [g] can be extended with [v] ("vingt" + "cinq", "soixante" + "onze", "cent" + "vingt"). */
    private fun join(g: Int, v: Int): Int? {
        val low = g % 100
        return when {
            v == 100 -> if (g in 1..9) g * 100 else null
            v in 1..9 -> if (low in listOf(20, 30, 40, 50, 60, 70, 80, 90) || (low == 10 && v >= 7) || (g > 0 && low == 0)) g + v else null
            v in 10..19 -> if (low == 60 || low == 80 || (g > 0 && low == 0)) g + v else null
            v >= 20 -> if (g > 0 && low == 0) g + v else null
            else -> null
        }
    }

    private fun items(toks: List<Tok>): List<Item> {
        val out = mutableListOf<Item>()
        var g = 0; var started = false
        fun flush() { if (started) out += Item.Group(g); g = 0; started = false }
        for (t in toks) when (t) {
            is Tok.Num -> when {
                t.digits -> { flush(); out += Item.Group(t.v) }
                !started -> { g = t.v; started = true }
                else -> { val j = join(g, t.v); if (j != null) g = j else { flush(); g = t.v; started = true } }
            }
            Tok.Euro -> { flush(); out += Item.Euro }
            Tok.Cents -> { flush(); out += Item.Cents }
            Tok.Sep -> { flush(); out += Item.Sep }
            Tok.Other -> flush()
        }
        flush()
        return out
    }

    /** The amount in euros, or null when no amount was understood. */
    fun amount(text: String): Double? {
        // "14,80" or "14.8" as the recogniser writes them
        Regex("""(\d{1,4})\s*[.,]\s*(\d{1,2})(?!\d)""").find(text)?.let {
            val d = it.groupValues[2]
            return finish("${it.groupValues[1]}.${if (d.length == 1) d + "0" else d}".toDouble())
        }
        val items = items(tokens(text))
        val marker = items.indexOfFirst { it is Item.Euro || it is Item.Sep }
        if (marker >= 0) {
            val euros = (items.take(marker).lastOrNull { it is Item.Group } as? Item.Group)?.v ?: return null
            val cents = (items.drop(marker + 1).firstOrNull { it is Item.Group } as? Item.Group)?.v
            val frac = when {
                cents == null -> 0.0
                items[marker] is Item.Sep && cents < 10 -> cents / 10.0 // "14 virgule 5" is 14.50
                else -> cents / 100.0
            }
            return finish(euros + frac)
        }
        val groups = items.filterIsInstance<Item.Group>().map { it.v }
        return when {
            groups.isEmpty() -> null
            items.any { it is Item.Cents } -> finish(groups.first() / 100.0)
            groups.size >= 2 && groups[1] in 0..99 -> finish(groups[0] + groups[1] / 100.0) // "douze cinquante"
            else -> finish(groups.first().toDouble())
        }
    }

    private fun finish(x: Double): Double? = if (x > 0 && x < 10000) Math.round(x * 100) / 100.0 else null

    private val articleWithSpace = Regex("^(?:chez|aux|au|[àa] la|[àa])\\s+", RegexOption.IGNORE_CASE)
    private val articleElided = Regex("^(?:[àa] l'|l')\\s*", RegexOption.IGNORE_CASE)
    private val article = Regex("^(?:le|la|les)\\s+", RegexOption.IGNORE_CASE)

    private fun cleanShop(s: String): String {
        var t = s.trim()
        repeat(2) { t = t.replace(articleWithSpace, "").replace(articleElided, "").replace(article, "").trim() }
        return t
    }

    /**
     * The shop to propose: a known shop that matches what was said (exactly, or as the start of its name, or with
     * a few recognition typos), otherwise what was said, with a capital letter. Null when nothing was understood.
     */
    fun shop(candidates: List<String>, known: List<String>): String? {
        val cleaned = candidates.map(::cleanShop).filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return null
        val shops = known.filter { it.isNotBlank() && it != "Unknown" }
        for (c in cleaned) {
            val lc = unaccent(c.lowercase())
            shops.firstOrNull { unaccent(it.lowercase()) == lc }?.let { return it }
            if (lc.length >= 3) shops.firstOrNull { unaccent(it.lowercase()).startsWith(lc) }?.let { return it }
        }
        Parser.knownShop(cleaned.joinToString("\n"), shops)?.let { return it }
        return cleaned.first().replaceFirstChar { it.uppercase() }
    }

    // ---- Dates said aloud: "hier", "le 7 novembre", "samedi dernier", "il y a deux jours" ----

    /** What is left of [text] once the date words are removed, and the date they meant (null when there was none). */
    data class Dated(val text: String, val date: java.time.LocalDate?)

    private val weekdays = mapOf(
        "lundi" to java.time.DayOfWeek.MONDAY, "mardi" to java.time.DayOfWeek.TUESDAY, "mercredi" to java.time.DayOfWeek.WEDNESDAY,
        "jeudi" to java.time.DayOfWeek.THURSDAY, "vendredi" to java.time.DayOfWeek.FRIDAY, "samedi" to java.time.DayOfWeek.SATURDAY,
        "dimanche" to java.time.DayOfWeek.SUNDAY)

    private val monthNames = mapOf(
        "janvier" to 1, "janv" to 1, "fevrier" to 2, "fevr" to 2, "mars" to 3, "avril" to 4, "avr" to 4, "mai" to 5,
        "juin" to 6, "juillet" to 7, "juil" to 7, "aout" to 8, "septembre" to 9, "sept" to 9, "octobre" to 10, "oct" to 10,
        "novembre" to 11, "nov" to 11, "decembre" to 12, "dec" to 12)

    fun withoutDate(text: String, today: java.time.LocalDate = java.time.LocalDate.now()): Dated {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val n = words.map { unaccent(it.lowercase()).replace('\u2019', '\'').trim(',', '.', ';', '!', '?') }
        fun num(i: Int): Int? = n.getOrNull(i)?.let { it.toIntOrNull() ?: units[it] }
        fun hit(from: Int, to: Int, date: java.time.LocalDate): Dated {
            val start = if (from > 0 && n[from - 1] == "le") from - 1 else from // "le 7 novembre", "le samedi"
            return Dated(words.filterIndexed { i, _ -> i < start || i > to }.joinToString(" "), date)
        }
        for (i in words.indices) {
            val w = n[i]
            when {
                w == "avant" && n.getOrNull(i + 1) == "hier" -> return hit(i, i + 1, today.minusDays(2))
                w == "avant-hier" -> return hit(i, i, today.minusDays(2))
                w == "hier" -> return hit(i, i, today.minusDays(1))
                w == "aujourd'hui" || w == "aujourdhui" -> return hit(i, i, today)
                w == "il" && n.getOrNull(i + 1) == "y" && n.getOrNull(i + 2) == "a" && num(i + 3) != null &&
                    n.getOrNull(i + 4)?.startsWith("jour") == true -> return hit(i, i + 4, today.minusDays(num(i + 3)!!.toLong()))
                w in weekdays -> {
                    val last = n.getOrNull(i + 1).let { it == "dernier" || it == "derniere" }
                    var d = today.minusDays(((today.dayOfWeek.value - weekdays.getValue(w).value + 7) % 7).toLong())
                    if (last && d == today) d = today.minusDays(7)
                    return hit(i, if (last) i + 1 else i, d)
                }
                w.toIntOrNull() in 1..31 && n.getOrNull(i + 1) in monthNames -> {
                    val month = monthNames.getValue(n[i + 1])
                    val year = n.getOrNull(i + 2)?.takeIf { it.length == 4 }?.toIntOrNull()?.takeIf { it in 2000..2100 }
                    var d = try { java.time.LocalDate.of(year ?: today.year, month, w.toInt()) } catch (e: Exception) { null } ?: continue
                    if (year == null && d.isAfter(today)) d = d.minusYears(1) // "le 25 decembre" said in november is last year's
                    return hit(i, if (year != null) i + 2 else i + 1, d)
                }
            }
        }
        return Dated(text, null)
    }
}
