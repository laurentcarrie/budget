package com.perso.budget

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParserTest {
    private val today = LocalDate.of(2026, 11, 21)
    private fun date(s: String) = Parser.date(s, today)

    @Test fun numericDateWithTime() = assertEquals("2026-11-19 22:18", date("REF. 024641\n19/11/2026 22:18:39\nPAIEMENT"))

    @Test fun droppedMonthDigitTakesTheClosestPastMonth() = assertEquals("2026-11-19 18:52", date("Le 19/1/2026 a 18:52:57"))

    @Test fun frenchMonthName() = assertEquals("2026-11-12 20:59", date("Tel : 01.48.24.02.90\njeudi 12 novembre 2026 à 20:59:43\nServi par : Antoine"))

    @Test fun frenchMonthNameUpperCaseNoAccent() = assertEquals("2026-02-03 12:30", date("MARDI 3 FEVRIER 2026 A 12:30"))

    @Test fun frenchAbbreviatedMonth() = assertEquals("2026-01-05", date("5 janv. 2026"))

    @Test fun juneAndJuly() {
        assertEquals("2026-06-30", date("30 juin 2026"))
        assertEquals("2026-07-14", date("14 juillet 2026"))
    }

    @Test fun marchIsNotMay() = assertEquals("2026-03-08", date("8 mars 2026"))

    @Test fun futureDateIsRejected() = assertNull(date("25/12/2026 10:00"))

    @Test fun noDate() = assertNull(date("TOTAL 36,80 EUR"))
}
