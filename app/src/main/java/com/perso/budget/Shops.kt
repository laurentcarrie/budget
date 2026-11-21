package com.perso.budget

import java.text.Normalizer

/**
 * Shop names as the user sees them: "alimentation", "alimentation " (trailing space), "Alimentation" and "pub"/"pub 🍻"
 * are the same shop. [distinct] gives one spelling per shop for the dropdown, [canonical] gives the spelling to save.
 */
object Shops {
    /** The categories offered before any ticket has one (the ones already used come first). The icon is part of the name. */
    val DEFAULT_CATEGORIES = listOf("alimentation \uD83D\uDED2", "restaurant \uD83C\uDF7D\uFE0F", "pub \uD83C\uDF7B", "p\u00e9age \uD83D\uDEE3\uFE0F",
        "essence \u26FD", "transport \uD83D\uDE86", "sant\u00e9 \uD83D\uDC8A", "loisirs \uD83C\uDFAD", "v\u00eatements \uD83D\uDC55",
        "maison \uD83C\uDFE0", "cadeaux \uD83C\uDF81", "divers \uD83D\uDCE6")

    /** What makes two names the same shop: letters and digits only, without case or accents. */
    fun key(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().filter { it.isLetterOrDigit() }

    /** One entry per shop, the most used first; the spelling shown is the one used on most tickets. [perTicket] has one name per ticket. */
    fun distinct(perTicket: List<String>): List<String> {
        val names = perTicket.map { it.trim() }.filter { it.isNotEmpty() }
        return names.groupBy { key(it).ifEmpty { it.lowercase() } }.values
            .map { variants ->
                val spelling = variants.groupingBy { it }.eachCount().entries.maxWithOrNull(compareBy({ it.value }, { -variants.indexOf(it.key) }))!!.key
                spelling to variants.size
            }
            .sortedWith(compareBy({ -it.second }, { it.first.lowercase() }))
            .map { it.first }
    }

    /** The name to save: the known spelling when the same shop is already known, else the name without surrounding spaces. */
    fun canonical(name: String, known: List<String>): String {
        val n = name.trim()
        val k = key(n)
        return if (k.isEmpty()) n else known.firstOrNull { key(it) == k } ?: n
    }
}
