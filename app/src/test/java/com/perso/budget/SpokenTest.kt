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

    // ---- dates said aloud (today is Saturday 21 November 2026) ----
    private val today = java.time.LocalDate.of(2026, 11, 21)
    private fun d(s: String) = Spoken.withoutDate(s, today)
    private fun date(y: Int, m: Int, day: Int) = java.time.LocalDate.of(y, m, day)

    @Test fun yesterdayAndTheDayBefore() {
        assertEquals(Spoken.Dated("quatorze euros", date(2026, 11, 20)), d("quatorze euros hier"))
        assertEquals(date(2026, 11, 19), d("avant-hier").date)
        assertEquals(date(2026, 11, 19), d("avant hier").date)
        assertEquals(today, d("aujourd'hui").date)
    }

    @Test fun aDayAndAMonth() {
        assertEquals(Spoken.Dated("", date(2026, 11, 7)), d("le 7 novembre"))
        assertEquals(date(2026, 11, 7), d("7 novembre").date)
        assertEquals(date(2025, 11, 7), d("le 7 novembre 2025").date)
        assertEquals(date(2026, 1, 3), d("le 3 janv.").date)
        assertEquals(date(2026, 2, 28), d("28 f\u00e9vrier").date)
        assertEquals(date(2025, 12, 25), d("le 25 d\u00e9cembre").date) // not yet this year
    }

    @Test fun theDateWordsDoNotDisturbTheAmountOrTheShop() {
        assertEquals(13.0, Spoken.amount(d("treize euros le 7 novembre").text)!!, 0.001)
        assertEquals("chez Carbone", d("chez Carbone hier").text)
        assertEquals("pub", d("pub le 7 novembre").text)
        assertEquals(14.8, Spoken.amount(d("quatorze euros quatre-vingts hier").text)!!, 0.001)
    }

    @Test fun weekdays() {
        assertEquals(date(2026, 11, 14), d("samedi dernier").date) // today is a Saturday
        assertEquals(today, d("samedi").date)
        assertEquals(date(2026, 11, 20), d("vendredi dernier").date)
        assertEquals(date(2026, 11, 16), d("lundi").date)
        assertEquals(date(2026, 11, 15), d("dimanche dernier").date)
        assertEquals(Spoken.Dated("caf\u00e9", date(2026, 11, 18)), d("caf\u00e9 le mercredi"))
    }

    @Test fun someDaysAgo() {
        assertEquals(date(2026, 11, 19), d("il y a deux jours").date)
        assertEquals(date(2026, 11, 18), d("il y a 3 jours").date)
        assertEquals(date(2026, 11, 20), d("il y a un jour").date)
    }

    @Test fun noDateKeepsTheText() {
        assertEquals(Spoken.Dated("quatorze euros quatre-vingts", null), d("quatorze euros quatre-vingts"))
        assertEquals(Spoken.Dated("chez Carbone", null), d("chez Carbone"))
        assertNull(d("12 euros").date)
        assertNull(d("31 f\u00e9vrier").date)
    }
}
