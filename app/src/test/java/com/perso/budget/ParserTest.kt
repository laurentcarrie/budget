package com.perso.budget

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    // ---- card-terminal receipts: the shop is under the "LE jj/mm/aa A hh:mm:ss" line, not in the footer ----
    private val matelyReceipt = "CARTE BANCAIRE\nSANS CONTACT\nA0000000421010\nCB COMPTANT\nLE 14/11/26 A 17:56:36\nLE MATELY S\n92 COURBEVOIE\n8874182\n" +
        "17515\n############4564\nNO AUTO:\nMONTANT:\n8,90 EUR\nDEBIT\nTICKET CLIENT\nA CONSERVER"
    private val cafeReceipt = "CARTE BANCAIRE\nSANS CONTACT\nA0000000421010\nCB COMPTANT\nLE 10/11/26 A 18:27:58\nHV\n92HV CAFE\n4492759\n30004\n" +
        "############4564\nN\u00b0 AUTO : 731502\nMONTANT\n9,80 EUR\nDEBIT\nTICKET CLIENT A CONSERVER"
    private val soirReceipt = "CARTE BANCAIRE\nCIC\nA0000000421010\nCB COMPTANT\nLE 05/11/26 A 22:38:10\nSOIR SERVICE\nCOURBEVOIE\n92400\n7268345\n" +
        "NO AUTO: 116235\nMONTANT:\n7,70 EUR\nDEBIT\nTICKET CLIENT\nA CONSERVER"

    @Test fun theShopIsUnderTheDateLine() {
        assertEquals("LE MATELY S", Parser.shop(matelyReceipt))
        assertEquals("SOIR SERVICE", Parser.shop(soirReceipt))
    }

    @Test fun aShortLineUnderTheDateIsSkipped() = assertEquals("92HV CAFE", Parser.shop(cafeReceipt))

    @Test fun theFooterIsNeverTheShop() =
        listOf(matelyReceipt, cafeReceipt, soirReceipt).forEach { assertNotEquals("A CONSERVER", Parser.shop(it)) }

    @Test fun aDateLineWithAnOcrSlip() = assertEquals("THE FROG & BRITI", Parser.shop("CB COMPTANT\nLe 19/I/2026 a 18:52:57\nTHE FROG & BRITI\nPARIS\n75013"))

    @Test fun receiptsWithoutTheLeDateLineKeepTheOldRule() =
        assertEquals("VTD MARKET", Parser.shop("VTD MARKET\nCOURBEVOIE\n92400\n3391579\n19/11/2026 22:18:39\nREF. 024641 REC ID 000278"))
}
