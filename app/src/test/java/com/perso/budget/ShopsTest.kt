package com.perso.budget

import org.junit.Assert.assertEquals
import org.junit.Test

class ShopsTest {
    @Test fun aTrailingSpaceIsTheSameShop() =
        assertEquals(listOf("alimentation"), Shops.distinct(listOf("alimentation", "alimentation ", "alimentation")))

    @Test fun caseAccentsAndEmojiDoNotMakeAnotherShop() {
        assertEquals(1, Shops.distinct(listOf("Café", "cafe", "CAFE ")).size)
        assertEquals(1, Shops.distinct(listOf("pub", "pub 🍻")).size)
    }

    @Test fun theMostUsedSpellingIsShown() =
        assertEquals(listOf("pub \uD83C\uDF7B"), Shops.distinct(listOf("pub", "pub \uD83C\uDF7B", "pub \uD83C\uDF7B")))

    @Test fun theMostUsedShopComesFirst() =
        assertEquals(listOf("pub", "boulangerie", "CAVALE"), Shops.distinct(listOf("CAVALE", "boulangerie ", "pub", "pub", "boulangerie", "pub")))

    @Test fun differentShopsStayApart() =
        assertEquals(2, Shops.distinct(listOf("CARBONE 14", "CARBONE 15")).size)

    @Test fun blanksAreDropped() = assertEquals(listOf("pub"), Shops.distinct(listOf("", "  ", "pub")))

    @Test fun savingReusesTheKnownSpelling() {
        val known = listOf("alimentation", "pub 🍻", "Café")
        assertEquals("alimentation", Shops.canonical("Alimentation ", known))
        assertEquals("pub 🍻", Shops.canonical("pub", known))
        assertEquals("Café", Shops.canonical("cafe", known))
    }

    @Test fun aNewShopIsOnlyTrimmed() = assertEquals("Chez Paul", Shops.canonical("  Chez Paul ", listOf("pub")))

    @Test fun theStarterCategoriesHaveIconsAndTypingTheWordGivesTheIcon() {
        val d = Shops.DEFAULT_CATEGORIES
        assertEquals(d.size, d.map { Shops.key(it) }.toSet().size) // no two starters are the same category
        assertEquals("pub \uD83C\uDF7B", d.first { Shops.key(it) == "pub" })
        assertEquals("pub \uD83C\uDF7B", Shops.canonical("pub", d))
        assertEquals("p\u00e9age \uD83D\uDEE3\uFE0F", Shops.canonical("Peage", d))
        assertEquals("alimentation \uD83D\uDED2", Shops.canonical("Alimentation ", d))
    }
}
