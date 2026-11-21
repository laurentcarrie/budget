package com.perso.budget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    private fun ticket(date: String, shop: String, amount: Double, description: String = "") =
        Receipt(1, "u", date, shop, amount, "", true, 0, false, null, description)

    private val pub = ticket("2026-11-07 18:26", "pub 🍻", 13.0, "Maman poissy")
    private val bakery = ticket("2026-11-07 18:29", "boulangerie", 4.8)
    private val carbone = ticket("2026-11-20 23:52", "CARBONE 14", 14.0)
    private val frog = ticket("2026-11-21 16:44", "THE FROG & BRITI", 7.0)
    private val cafe = ticket("2026-11-12 12:41", "Café de la gare", 3.2)

    @Test fun anEmptyQueryKeepsEverything() = assertTrue(Search.matches("  ", pub))

    @Test fun aWordOfTheShopCaseAndEmojiIgnored() {
        assertTrue(Search.matches("pub", pub))
        assertTrue(Search.matches("PUB", pub))
        assertFalse(Search.matches("pub", bakery))
    }

    @Test fun aTypoIsTolerated() {
        assertTrue(Search.matches("boulangrie", bakery))
        assertTrue(Search.matches("carbonne", carbone))
        assertTrue(Search.matches("poisy", pub))
    }

    @Test fun aWordNotFinishedStillMatches() {
        assertTrue(Search.matches("boulang", bakery))
        assertTrue(Search.matches("carb", carbone))
    }

    @Test fun everyWordMustBeFound() {
        assertTrue(Search.matches("frog brit", frog))
        assertTrue(Search.matches("pub maman", pub))
        assertFalse(Search.matches("pub boulangerie", pub))
    }

    @Test fun accentsDoNotMatter() {
        assertTrue(Search.matches("cafe", cafe))
        assertTrue(Search.matches("café", cafe))
        assertTrue(Search.matches("gare", cafe))
    }

    @Test fun theAmountWithADotOrAComma() {
        assertTrue(Search.matches("14", carbone))
        assertTrue(Search.matches("4.8", bakery))
        assertTrue(Search.matches("4,8", bakery))
        assertTrue(Search.matches("13.00", pub))
        assertFalse(Search.matches("13.5", pub))
    }

    @Test fun theDate() {
        assertTrue(Search.matches("2026-11-07", pub))
        assertTrue(Search.matches("2026-11-07", bakery))
        assertFalse(Search.matches("2026-11-07", carbone))
        assertTrue(Search.matches("2026-11", frog))
    }

    @Test fun theDescription() = assertTrue(Search.matches("maman", pub))

    @Test fun numbersAreNotFuzzy() = assertFalse(Search.matches("15", carbone)) // 14.00 and 2026-11-20 23:52 have no "15"

    @Test fun unrelatedTextDoesNotMatch() {
        assertFalse(Search.matches("zzzz", pub))
        assertFalse(Search.matches("restaurant", bakery))
    }

    @Test fun theCategory() {
        val toll = ticket("2026-11-10 08:00", "VINCI", 21.8).copy(category = "p\u00e9age")
        assertTrue(Search.matches("peage", toll))
        assertTrue(Search.matches("p\u00e9age", toll))
        assertFalse(Search.matches("peage", pub))
    }
}
