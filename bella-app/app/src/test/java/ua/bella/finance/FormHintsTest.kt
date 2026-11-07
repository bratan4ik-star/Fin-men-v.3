package ua.bella.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormHintsTest {
    private val orig = Form(tAmt = "500.0", tDesc = "Сільпо", tDate = "2026-11-01", tToAmt = "12.5", name = "Monobank", bal = "1000.0",
        pName = "VWCE", pQty = "20.0", pBuy = "2300.0", pNow = "2480.0", pRate = "2.0", pMat = "2027-05-12", pNext = "2026-12-20")

    @Test fun blankFieldsKeepOriginalValues() {
        val cur = orig.copy(tAmt = "", tDesc = " ", tDate = "", name = "", bal = "", pName = "", pQty = "", pBuy = "", pNow = "", pRate = "", pMat = "", pNext = "")
        assertEquals(orig, fillBlanks(cur, orig))
    }

    @Test fun changedFieldsWin() {
        val cur = orig.copy(tAmt = "750", tDesc = "АТБ", name = "Моно Чорна", pQty = "25")
        val r = fillBlanks(cur, orig)
        assertEquals("750", r.tAmt); assertEquals("АТБ", r.tDesc); assertEquals("Моно Чорна", r.name); assertEquals("25", r.pQty)
        assertEquals("2026-11-01", r.tDate)
    }

    @Test fun blankReceivedAmountStaysBlankForAutoRate() {
        assertEquals("", fillBlanks(orig.copy(tToAmt = ""), orig).tToAmt)
    }

    @Test fun positionDatesAreValidated() {
        assertEquals("", posDatesError("", ""))
        assertEquals("", posDatesError("2026-12-01", "2027-03-01"))
        assertEquals("", posDatesError("2027-03-01", "2027-03-01"))
        assertEquals("Дата виплати у форматі РРРР-ММ-ДД", posDatesError("01.12.2026", ""))
        assertEquals("Дата повернення у форматі РРРР-ММ-ДД", posDatesError("", "2027-13-01"))
        assertEquals("Дата виплати не може бути пізніше дати повернення", posDatesError("2027-04-01", "2027-03-01"))
    }

    @Test fun savedAtIsFormatted() {
        val kyiv = java.time.ZoneId.of("Europe/Kyiv")
        // 2026-11-03 12:05 UTC = 14:05 за Києвом (зимовий час, UTC+2)
        val ms = java.time.Instant.parse("2026-11-03T12:05:00Z").toEpochMilli()
        assertEquals("03.11.2026 о 14:05", fmtSavedAt(ms, kyiv))
        assertEquals("", fmtSavedAt(0L, kyiv))
    }

    @Test fun debtFieldsKeepOriginalWhenBlank() {
        val o = Form(who = "Іван", amt = "12300", due = "2026-12-20", dIdx = 0)
        val r = fillBlanks(o.copy(who = "", amt = "", due = ""), o)
        assertEquals("Іван", r.who); assertEquals("12300", r.amt)
        assertEquals("", r.due) // порожній термін означає «без терміну»
    }

    @Test fun missingPositionFieldsAreHighlighted() {
        // нова ОВДП після вибору випуску: лишились кількість і ціна
        val picked = Form(group = "ОВДП", pName = "ОВДП UA4000239149", pRate = "15.1", pMat = "2028-01-19", pNext = "2027-01-20")
        assertEquals(setOf("qty", "buy"), posMissing(picked, null))
        assertEquals(setOf("buy"), posMissing(picked.copy(pQty = "10"), null))
        assertEquals(setOf("qty"), posMissing(picked.copy(pQty = "0", pBuy = "985,5"), null))  // нуль — ще не заповнено
        assertTrue(posMissing(picked.copy(pQty = "10", pBuy = "985.5"), null).isEmpty())
        assertEquals(setOf("name", "qty", "buy"), posMissing(Form(group = "ETF"), null))
        // депозит: кількості немає; для щомісячного — ще ставка й дата виплати
        assertEquals(setOf("name", "buy"), posMissing(Form(group = "Депозити"), null))
        assertEquals(setOf("rate", "next"), posMissing(Form(group = "Депозити", pName = "Моно", pBuy = "10000", pMonthly = true), null))
        // редагування: порожнє поле = старе значення, не підсвічується
        val orig = picked.copy(pQty = "10", pBuy = "985.5")
        assertTrue(posMissing(orig.copy(pQty = "", pBuy = ""), orig).isEmpty())
        assertEquals(setOf("qty"), posMissing(orig.copy(pQty = ""), orig.copy(pQty = "")))
    }
}
