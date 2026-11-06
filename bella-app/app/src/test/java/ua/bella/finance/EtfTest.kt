package ua.bella.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EtfTest {
    private val r40 = mapOf("UAH" to 1.0, "USD" to 40.0, "EUR" to 44.0, "PLN" to 10.0)
    private val r46 = r40 + ("EUR" to 46.0)
    // 10 шт. VWCE куплено по 150 € при курсі 44, зараз 160 €
    private val vwce = Pos("ETF", "VWCE", 10.0, 150 * 44.0, 160 * 44.0, cur = "EUR", pxBuy = 150.0, pxNow = 160.0, buyFx = 44.0, ticker = "VWCE.DE")

    @Test fun valueFollowsRate() {
        assertEquals(10 * 160 * 44.0, posValueUah(vwce, r40), 1e-9)
        assertEquals(10 * 160 * 46.0, posValueUah(vwce, r46), 1e-9)
        assertEquals(10 * 150 * 44.0, posCostUah(vwce), 1e-9)
    }

    @Test fun profitIsSplitIntoPriceAndFx() {
        val s = plSplit(vwce, r46)
        assertEquals(10 * 10 * 44.0, s.price, 1e-9)       // ціна +10 € за курсом купівлі
        assertEquals(10 * 160 * 2.0, s.fx, 1e-9)          // курс +2 ₴ на поточну вартість
        assertEquals(posValueUah(vwce, r46) - posCostUah(vwce), s.total, 1e-9)
    }

    @Test fun legacyUahPositionIsUnchanged() {
        val old = Pos("ETF", "VWCE", 20.0, 2300.0, 2480.0)
        assertFalse(isFxPos(old))
        assertEquals(20 * 2480.0, posValueUah(old, r46), 1e-9)
        assertEquals(PlSplit(20 * 180.0, 20 * 180.0, 0.0), plSplit(old, r46))
    }

    @Test fun fundCurrencyDisplay() {
        assertEquals("€", curSign("EUR")); assertEquals("$", curSign("USD")); assertEquals("zł", curSign("PLN"))
        assertEquals(1600.0, posValueCur(vwce), 1e-9); assertEquals(1500.0, posCostCur(vwce), 1e-9)
        assertEquals(100.0 / 15, plCurPct(vwce), 1e-9) // 150 → 160 €: +6,67% без впливу курсу
        assertTrue(fxMoney(1600.0, "EUR").endsWith(" €"))
        val uah = Pos("ETF", "Old", 1.0, 100.0, 120.0)
        assertEquals(0.0, posValueCur(uah), 1e-9); assertEquals(0.0, plCurPct(uah), 1e-9)
    }

    @Test fun usdBondFromCalculatorIsValuedOnceInHryvnia() {
        val r = mapOf("UAH" to 1.0, "USD" to 41.0, "EUR" to 48.0)
        val bond = Pos("ОВДП", "ОВДП USD", 2.0, 1000.0, 1010.0, "2027-04-20", 4.5, cur = "USD")
        assertTrue(isBondCurPos(bond)); assertFalse(isFxPos(bond))
        assertEquals(2 * 1010.0 * 41, posValueUah(bond, r), 1e-9)
        val uah = Pos("ОВДП", "ОВДП UAH", 2.0, 1000.0, 1010.0)
        assertFalse(isBondCurPos(uah)); assertEquals(2020.0, posValueUah(uah, r), 1e-9)
    }
}
