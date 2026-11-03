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

    @Test fun parsesYahooResponse() {
        val body = """{"chart":{"result":[{"meta":{"currency":"EUR","symbol":"VWCE.DE","regularMarketPrice":167.8}}],"error":null}}"""
        assertEquals(Quote(167.8, "EUR"), parseYahooQuote(body))
        assertNull(parseYahooQuote("""{"chart":{"result":null,"error":{"code":"Not Found"}}}"""))
        assertNull(parseYahooQuote("not json"))
    }

    @Test fun quoteUpdatesFxPosition() {
        val (p, e) = applyQuote(vwce, Quote(170.0, "EUR"), r40, 123L)
        assertEquals("", e); assertEquals(170.0, p!!.pxNow, 1e-9); assertEquals(170 * 44.0, p.now, 1e-9); assertEquals(123L, p.pxAt)
        assertEquals(150.0, p.pxBuy, 1e-9) // ціна купівлі не змінюється
    }

    @Test fun quoteConvertsLegacyUahPosition() {
        val old = Pos("ETF", "SPY", 2.0, 30000.0, 30000.0, ticker = "SPY")
        val (p, _) = applyQuote(old, Quote(752.0, "USD"), r40, 1L)
        assertTrue(isFxPos(p!!)); assertEquals("USD", p.cur)
        assertEquals(750.0, p.pxBuy, 1e-9); assertEquals(40.0, p.buyFx, 1e-9) // 30000 ₴ / 40 = 750 $
        assertEquals(posCostUah(old), posCostUah(p), 1e-9) // вартість купівлі не змінилась
    }

    @Test fun unsupportedOrMismatchedCurrencyIsRejected() {
        assertEquals("VWCE: валюта котирування GBp не підтримується", applyQuote(vwce, Quote(1.0, "GBp"), r40, 1L).second)
        assertEquals("VWCE: котирування в USD, а позиція в EUR", applyQuote(vwce, Quote(1.0, "USD"), r40, 1L).second)
    }
}
