package ua.bella.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Перенесено зі StatementParserTest (New-fin-manager-BELLA-): розбір сум, дат і рядків CSV-виписки. */
class StatementParseTest {
    private fun n(s: String) = Importer.num(s)!!

    @Test fun plainAndDecimalComma() {
        assertEquals(-720.50, n("-720.50"), 1e-9)
        assertEquals(-450.50, n("-450,50"), 1e-9)
        assertEquals(1234.50, n("1 234,50"), 1e-9)
        assertEquals(-230.0, n("−230"), 1e-9) // юнікодний мінус
    }

    @Test fun thousandsSeparators() {
        assertEquals(1234.50, n("1,234.50"), 1e-9)
        assertEquals(-1234.50, n("-1.234,50"), 1e-9)   // раніше читалось як −1,2345
        assertEquals(38500.0, n("38.500,00"), 1e-9)    // раніше читалось як 38,5
        assertEquals(1234567.0, n("1.234.567"), 1e-9)
        assertEquals(1234567.0, n("1,234,567"), 1e-9)
    }

    @Test fun hryvniaMarksAreIgnored() {
        assertEquals(450.0, n("450.00 грн"), 1e-9)
        assertEquals(-1450.0, n("-1450,00 ₴"), 1e-9)
        assertEquals(99.9, n("99,90 UAH"), 1e-9)
        assertEquals(12.0, n("12 грн."), 1e-9)
    }

    @Test fun notANumber() {
        assertNull(Importer.num(""))
        assertNull(Importer.num("Сільпо"))
        assertNull(Importer.num("500.00 $")) // інша валюта: не підміняємо мовчки гривнями
    }

    @Test fun datesWithTimeAndInvalidDates() {
        assertEquals("2026-10-14", Importer.toIso("14.10.2026 15:30"))
        assertEquals("2026-10-16", Importer.toIso("2026-10-16"))
        assertNull(Importer.toIso("2026-13-45"))
        assertNull(Importer.toIso("45.13.2026"))
    }

    @Test fun csvSemicolonSample() {
        val r = Importer.parseCsv("2026-10-16; Сільпо супермаркет; -720.50\n2026-10-12; Зарахування зарплати; 35000.00", 1.0)
        assertEquals(2, r.txs.size)
        val (silpo, salary) = r.txs
        assertEquals("Сільпо супермаркет", silpo.desc); assertEquals("expense", silpo.type); assertEquals("Харчування", silpo.cat); assertEquals(720.50, silpo.amt, 1e-9)
        assertEquals("income", salary.type); assertEquals("Зарплата", salary.cat); assertEquals("2026-10-12", salary.date)
    }

    @Test fun csvEuropeanAmountAndHryvniaMark() {
        val r = Importer.parseCsv("12.10.2026; Сільпо; -1.234,50 грн", 1.0)
        assertEquals(1234.50, r.txs.single().amt, 1e-9)
    }

    @Test fun csvSkipsZeroInvalidAndJunkLines() {
        val r = Importer.parseCsv("це не операція\n2026-10-14; Щось; 0\n2026-13-45; Аптека; -100\n2026-10-14; Аптека; -50", 1.0)
        assertEquals(1, r.txs.size)
        assertEquals(50.0, r.txs[0].amt, 1e-9); assertEquals("Здоров'я", r.txs[0].cat)
        assertEquals(3, r.bad)
    }

    @Test fun csvAmountsUseViewCurrencyRate() {
        val r = Importer.parseCsv("2026-10-14; Uber; -10", 41.5)
        assertEquals(415.0, r.txs.single().amt, 1e-9); assertEquals("Транспорт", r.txs.single().cat)
    }
}
