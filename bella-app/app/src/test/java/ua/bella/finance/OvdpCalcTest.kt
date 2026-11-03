package ua.bella.finance

import org.junit.Assert.assertEquals
import org.junit.Test

class OvdpCalcTest {
    // Дані з довідки брокера: 477 шт., ціна 1048,78, 13,5%, погашення 16.12.2026, купон нараховується від 12.05.2026
    private val real = CalcInput("UA4000238281", "1000", "2026-09-22", "2026-12-16", "1048.78", "477", "13.5", 0, "1081.75", "2026-12-16", "2026-05-12")

    @Test fun matchesBrokerStatement() {
        val c = calcOvdp(real)
        assertEquals(500268.06, c.inv, 0.01)
        assertEquals(38994.75, c.cpn, 0.01)                    // купонний дохід
        assertEquals(515994.75, c.total, 0.01)                 // загальна сума виплат
        assertEquals(15726.69, c.total - c.inv, 0.01)          // прибуток
    }

    @Test fun sellOnMaturityIsRedemption() {
        val c = calcOvdp(real)
        assertEquals(477000.0, c.sTotal, 0.01); assertEquals(15726.69, c.sProfit, 0.01)
        assertEquals(c.ytm, c.sYield, 0.01)
    }

    @Test fun withoutIssueDateCouponStartsFromBuy() {
        val c = calcOvdp(real.copy(issue = ""))
        assertEquals(477000 * 0.135 * 85 / 360.0, c.cpn, 0.01)
    }

    @Test fun sellBeforeMaturityUsesSellPrice() {
        val c = calcOvdp(real.copy(freq = 6, mat = "2027-12-16", sellD = "2027-03-16", sell = "1050", issue = ""))
        assertEquals(477 * 1050.0, c.sTotal, 0.01)
    }
}
