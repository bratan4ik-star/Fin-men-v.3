package ua.bella.finance

import org.junit.Assert.assertEquals
import org.junit.Test

/** Перенесено з OvdpMathTest (New-fin-manager-BELLA-) і доповнено: базова математика ОВДП. */
class OvdpMathTest {
    private val par = CalcInput("UA0000000001", "1000", "2026-01-01", "2027-01-01", "1000", "1", "10", 12, "", "", "")

    @Test fun ytmEqualsCouponRateWhenBoughtAtPar() {
        val c = calcOvdp(par)
        assertEquals(10.0, c.ytm, 0.1)
        assertEquals(100.0, c.cpn, 1e-9)
        assertEquals(100.0, c.total - c.inv, 1e-9)
        assertEquals(0.0, c.disc, 1e-9)
    }

    @Test fun semiAnnualCouponsAtParGiveSameIncome() {
        val c = calcOvdp(par.copy(freq = 6))
        assertEquals(listOf("2026-07-01", "2027-01-01"), c.sched.map { it.date.toString() })
        assertEquals(listOf(50.0, 1050.0), c.sched.map { it.amount })
        assertEquals(100.0, c.cpn, 1e-9)
        assertEquals(10.0, c.ytm, 0.1)
    }

    @Test fun discountRaisesYieldAboveCoupon() {
        val c = calcOvdp(par.copy(price = "980", qty = "10"))
        assertEquals(9800.0, c.inv, 1e-9)
        assertEquals(200.0, c.disc, 1e-9)
        // (1000 купон + 200 дисконт) / 9800 за ≈1 рік
        assertEquals(1200.0 / 9800 * 100, c.ytm, 0.1)
    }

    @Test fun zeroCouponBondEarnsOnlyDiscount() {
        val c = calcOvdp(par.copy(price = "950", coupon = "0", freq = 0))
        assertEquals(0.0, c.cpn, 1e-9)
        assertEquals(50.0, c.total - c.inv, 1e-9)
        assertEquals(50.0 / 950 * 100, c.ytm, 0.1)
    }

    @Test fun noSellPriceMeansNoSellResult() {
        val c = calcOvdp(par)
        assertEquals(false, c.hasSell)
        assertEquals(0.0, c.sProfit, 1e-9)
    }
}
