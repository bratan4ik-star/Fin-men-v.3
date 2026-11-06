package ua.bella.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun depositEquivalentYieldReflects23PercentTax() {
        val c = calcOvdp(real)
        // Deposit equivalent should be YTM / (1 - 0.23) = YTM / 0.77
        assertEquals(c.ytm / 0.77, c.depositEqYield, 0.001)
        org.junit.Assert.assertTrue("Deposit eq yield must exceed YTM", c.depositEqYield > c.ytm)
    }

    @Test fun presetsContainUahUsdAndEurOptions() {
        val currencies = POPULAR_OVDP_PRESETS.map { it.cur }.toSet()
        org.junit.Assert.assertTrue("Presets should have UAH", currencies.contains("UAH"))
        org.junit.Assert.assertTrue("Presets should have USD", currencies.contains("USD"))
        org.junit.Assert.assertTrue("Presets should have EUR", currencies.contains("EUR"))
    }

    @Test fun buildOvdpPortfolioCashFlowAggregatesPositionsByMonth() {
        val p1 = Pos(
            group = "ОВДП",
            name = "Військові 1",
            qty = 10.0,
            buy = 1000.0,
            now = 1000.0,
            mat = "2027-03-15",
            rate = 15.0,
            next = "2027-03-15",
            cur = "UAH",
            calc = CalcExtra("UA111", 1000.0, 6, "", "", "2026-03-15", "UAH")
        )
        val p2 = Pos(
            group = "ОВДП",
            name = "USD Bond",
            qty = 5.0,
            buy = 1000.0,
            now = 1000.0,
            mat = "2027-03-20",
            rate = 4.5,
            next = "2027-03-20",
            cur = "USD",
            calc = CalcExtra("UA222", 1000.0, 6, "", "", "2026-03-20", "USD")
        )
        val rates = mapOf("UAH" to 1.0, "USD" to 40.0, "EUR" to 45.0)
        val flows = buildOvdpPortfolioCashFlow(listOf(p1, p2), rates, java.time.LocalDate.parse("2026-01-01"))
        org.junit.Assert.assertFalse("Flows should not be empty", flows.isEmpty())
        val mar2027 = flows.find { it.ym == java.time.YearMonth.parse("2027-03") }
        org.junit.Assert.assertNotNull("March 2027 should have cash flows", mar2027)
        // Both p1 and p2 mature in March 2027
        // p1 principal: 10 * 1000 = 10,000 UAH
        // p2 principal: 5 * 1000 USD * 40 UAH/USD = 200,000 UAH
        assertEquals(210000.0, mar2027!!.principalUah, 0.01)
    }

    @Test fun computeOvdpCurrencyDistribution_calculatesSharesAccurately() {
        val p1 = Pos("ОВДП", "Гривня 1", 100.0, 1000.0, 1000.0, "2027-01-01", 16.0, cur = "UAH")
        val p2 = Pos("ОВДП", "Долар 1", 2.0, 1000.0, 1000.0, "2027-01-01", 4.5, cur = "USD")
        val rates = mapOf("UAH" to 1.0, "USD" to 40.0)
        // p1: 100 * 1000 = 100,000 UAH
        // p2: 2 * 1000 USD * 40 = 80,000 UAH
        // Total = 180,000 UAH
        // UAH share = 100,000 / 180,000 = 55.55%
        // USD share = 80,000 / 180,000 = 44.44%
        val slices = computeOvdpCurrencyDistribution(listOf(p1, p2), rates)
        assertEquals(2, slices.size)
        val uahSlice = slices.find { it.id == "UAH" }!!
        val usdSlice = slices.find { it.id == "USD" }!!
        assertEquals(100000.0, uahSlice.valueUah, 0.01)
        assertEquals(80000.0, usdSlice.valueUah, 0.01)
        assertEquals(55.55, uahSlice.percentage, 0.1)
        assertEquals(44.44, usdSlice.percentage, 0.1)
    }

    @Test fun computeOvdpMaturityDistribution_bucketsByMaturityDate() {
        val today = java.time.LocalDate.parse("2026-01-01")
        // < 180 days: 2026-04-01 is 90 days away -> m1 (short)
        val pShort = Pos("ОВДП", "Коротка", 10.0, 1000.0, 1000.0, "2026-04-01", 15.0, cur = "UAH")
        // 181..365 days: 2026-10-01 is 273 days away -> m2 (up to 1 yr)
        val pMedium = Pos("ОВДП", "Річна", 20.0, 1000.0, 1000.0, "2026-10-01", 16.0, cur = "UAH")
        // > 730 days: 2028-06-01 is > 2 yrs -> m4 (long)
        val pLong = Pos("ОВДП", "Довга", 30.0, 1000.0, 1000.0, "2028-06-01", 17.0, cur = "UAH")

        val rates = mapOf("UAH" to 1.0)
        val slices = computeOvdpMaturityDistribution(listOf(pShort, pMedium, pLong), rates, today)
        assertEquals(3, slices.size)
        val ids = slices.map { it.id }.toSet()
        org.junit.Assert.assertTrue("Should contain short bucket m1", ids.contains("m1"))
        org.junit.Assert.assertTrue("Should contain medium bucket m2", ids.contains("m2"))
        org.junit.Assert.assertTrue("Should contain long bucket m4", ids.contains("m4"))
    }

    @Test fun calculateReinvestmentCurve_demonstratesCompoundGrowthBenefit() {
        val points = calculateReinvestmentCurve(
            initialCapital = 100000.0,
            couponRatePct = 16.0,
            monthlyAddition = 0.0,
            years = 5
        )

        assertEquals(6, points.size) // year 0..5
        val y0 = points[0]
        assertEquals(0, y0.year)
        assertEquals(100000.0, y0.capitalWith, 0.01)
        assertEquals(100000.0, y0.capitalWithout, 0.01)
        assertEquals(0.0, y0.extraBenefit, 0.01)

        val y1 = points[1]
        assertEquals(1, y1.year)
        // Without: 2 x 8,000 withdrawn = 16,000; total = 116,000
        assertEquals(16000.0, y1.totalWithdrawn, 0.01)
        assertEquals(116000.0, y1.totalValueWithout, 0.01)
        // With: (100,000 * 1.08) * 1.08 = 116,640
        assertEquals(116640.0, y1.capitalWith, 0.01)
        assertEquals(640.0, y1.extraBenefit, 0.01)

        val y5 = points[5]
        org.junit.Assert.assertTrue("Reinvestment must yield higher capital than simple payout", y5.capitalWith > y5.totalValueWithout)
        org.junit.Assert.assertTrue("Extra benefit must be substantial over 5 years", y5.extraBenefit > 30000.0)
    }

    @Test fun calculateReinvestmentCurve_supportsMonthlyContributions() {
        val points = calculateReinvestmentCurve(
            initialCapital = 50000.0,
            couponRatePct = 15.0,
            monthlyAddition = 5000.0,
            years = 3
        )
        val y3 = points[3]
        // In 3 years (36 months), user contributed 50,000 + 36 * 5,000 = 230,000
        // Capital with compounding must exceed 230,000
        org.junit.Assert.assertTrue(y3.capitalWith > 280000.0)
        org.junit.Assert.assertTrue(y3.totalProfitWith > 50000.0)
    }

    @Test fun maturedPresetsAreHidden() {
        val t = java.time.LocalDate.parse("2026-11-06")
        val a = activeOvdpPresets(t)
        assertTrue(a.isNotEmpty() && a.all { pd(it.mat)!!.isAfter(t) })
        assertTrue(a.size < POPULAR_OVDP_PRESETS.size)
    }
}
