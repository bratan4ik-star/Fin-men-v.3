package ua.bella.finance

import org.junit.Assert.assertEquals
import org.junit.Test

class OvdpCalcTest {
    private val base = CalcInput("UA4000238281", "1000", "2026-09-22", "2027-12-16", "1048.78", "477", "13.5", 0, "1081.75", "2027-12-16")

    @Test fun numbersFromScreenshot() {
        val c = calcOvdp(base)
        assertEquals(500268.06, c.inv, 0.01); assertEquals(79336.76, c.cpn, 0.01)
        assertEquals(556336.76, c.total, 0.01); assertEquals(-23268.06, c.disc, 0.01)
        assertEquals(9.10, c.ytm, 0.01)
    }

    @Test fun sellOnMaturityIsRedemptionWithCoupon() {
        val c = calcOvdp(base)
        assertEquals(477000.0, c.sTotal, 0.01)                 // номінал, а не ціна продажу
        assertEquals(56068.70, c.sProfit, 0.01)                // уся прибутковість із купоном
        assertEquals(c.ytm, c.sYield, 0.01)                    // збігається з дохідністю до погашення
    }

    @Test fun sellBeforeMaturityUsesSellPrice() {
        val c = calcOvdp(base.copy(freq = 6, sellD = "2027-03-16", sell = "1050"))
        assertEquals(477 * 1050.0, c.sTotal, 0.01)
        // купони до дати продажу враховуються (не менше одного півріччя)
        assertEquals(true, c.sProfit > 477 * 1050.0 - c.inv)
    }
}
