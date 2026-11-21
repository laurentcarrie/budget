package com.perso.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpokenTest {
    private fun amount(s: String) = Spoken.amount(s)

    @Test fun digitsWithDecimalSeparator() {
        assertEquals(14.8, amount("14,80")!!, 0.001)
        assertEquals(14.8, amount("14.8")!!, 0.001)
        assertEquals(12.5, amount("12,50 euros")!!, 0.001)
    }

    @Test fun digitsWithEurosWord() {
        assertEquals(14.8, amount("14 euros 80")!!, 0.001)
        assertEquals(8.5, amount("8 euros 50")!!, 0.001)
        assertEquals(36.0, amount("36 euros")!!, 0.001)
        assertEquals(12.5, amount("12 50")!!, 0.001)
        assertEquals(14.8, amount("14 € 80")!!, 0.001)
    }

    @Test fun wordsWithEurosAndCents() {
        assertEquals(14.8, amount("quatorze euros quatre-vingts")!!, 0.001)
        assertEquals(14.8, amount("quatorze euros quatre vingt")!!, 0.001)
        assertEquals(3.3, amount("trois euros trente")!!, 0.001)
        assertEquals(1.5, amount("un euro cinquante")!!, 0.001)
        assertEquals(8.5, amount("huit euros cinquante")!!, 0.001)
        assertEquals(12.05, amount("douze euros cinq")!!, 0.001)
        assertEquals(14.8, amount("quatorze euros et quatre-vingts")!!, 0.001)
    }

    @Test fun wordsWithoutEurosWord() {
        assertEquals(12.5, amount("douze cinquante")!!, 0.001)
        assertEquals(25.0, amount("vingt-cinq")!!, 0.001)
        assertEquals(0.75, amount("soixante quinze centimes")!!, 0.001)
    }

    @Test fun frenchNumbers() {
        assertEquals(25.0, amount("vingt-cinq euros")!!, 0.001)
        assertEquals(21.0, amount("vingt et un euros")!!, 0.001)
        assertEquals(18.0, amount("dix-huit euros")!!, 0.001)
        assertEquals(17.0, amount("dix sept euros")!!, 0.001)
        assertEquals(70.0, amount("soixante-dix euros")!!, 0.001)
        assertEquals(71.0, amount("soixante-et-onze euros")!!, 0.001)
        assertEquals(77.0, amount("soixante-dix-sept euros")!!, 0.001)
        assertEquals(80.0, amount("quatre-vingts euros")!!, 0.001)
        assertEquals(81.0, amount("quatre vingt un euros")!!, 0.001)
        assertEquals(91.0, amount("quatre-vingt-onze euros")!!, 0.001)
        assertEquals(99.99, amount("quatre-vingt-dix-neuf euros quatre-vingt-dix-neuf")!!, 0.001)
        assertEquals(120.0, amount("cent vingt euros")!!, 0.001)
        assertEquals(200.0, amount("deux cents euros")!!, 0.001)
        assertEquals(250.0, amount("deux cent cinquante euros")!!, 0.001)
        assertEquals(7.0, amount("sept euros")!!, 0.001)
    }

    @Test fun nothingUnderstood() {
        assertNull(amount("bonjour"))
        assertNull(amount(""))
        assertNull(amount("chez carbone"))
    }

    private val known = listOf("CARBONE 14", "boulangerie", "VTD MARKET", "restaurant entreprise RSS")

    @Test fun shopFromAKnownNameWithAnArticle() {
        assertEquals("boulangerie", Spoken.shop(listOf("à la boulangerie"), known))
        assertEquals("boulangerie", Spoken.shop(listOf("chez la boulangerie"), known))
        assertEquals("CARBONE 14", Spoken.shop(listOf("chez Carbone"), known))
    }

    @Test fun shopWithARecognitionTypo() = assertEquals("VTD MARKET", Spoken.shop(listOf("vtd harket"), known))

    @Test fun shopTriesEveryAlternative() = assertEquals("boulangerie", Spoken.shop(listOf("bou langerie", "la boulangerie"), known))

    @Test fun unknownShopKeepsWhatWasSaidWithACapital() {
        assertEquals("Pub", Spoken.shop(listOf("au pub"), known))
        assertEquals("Café de la gare", Spoken.shop(listOf("au café de la gare"), known))
    }

    @Test fun noShop() {
        assertNull(Spoken.shop(emptyList(), known))
        assertNull(Spoken.shop(listOf("  "), known))
    }
}
