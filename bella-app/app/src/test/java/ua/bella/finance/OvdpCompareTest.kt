package ua.bella.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** «Порівняти ОВДП» (OvdpCompare.kt). */
class OvdpCompareTest {
    private fun d(s: String) = LocalDate.parse(s)
    private val rates = mapOf("USD" to 41.0, "EUR" to 45.0)
    private val depo by lazy { parseDepoSecurities(javaClass.classLoader!!.getResource("nbu/depo_securities_sample.json")!!.readText(), d("2026-11-07")).associateBy { it.isin } }
    /** Безкупонний випуск: лише погашення номіналу 1000. */
    private fun zero(isin: String, mat: String, cur: String = "UAH") = NbuBond(isin, cur, 1000.0, 0.0, mat, pays = listOf(NbuPay(mat, 2, 1000.0)))

    // ---------- XIRR / YTM ----------
    @Test fun xirrMatchesExcelExample() {
        // приклад з довідки Excel XIRR: 0,373362535
        val f = listOf(Flow(d("2008-01-01"), -10000.0), Flow(d("2008-03-01"), 2750.0), Flow(d("2008-10-30"), 4250.0), Flow(d("2009-02-15"), 3250.0), Flow(d("2009-04-01"), 2750.0))
        assertEquals(0.373362535, xirr(f)!!, 1e-8)
        assertNull(xirr(listOf(Flow(d("2026-01-01"), 100.0), Flow(d("2027-01-01"), 50.0))))
    }

    @Test fun zeroCouponYieldIsExact() {
        // 900 сьогодні → 1000 через 365 днів: 11,111…%
        assertEquals(1000.0 / 900 - 1, xirr(listOf(Flow(d("2029-01-01"), -900.0), Flow(d("2030-01-01"), 1000.0)))!!, 1e-10)
        val r = cmpResult("Z", listOf(buyLeg(zero("UA4000000009", "2030-01-01"), 900.0, 90_000.0, d("2029-01-01"), rates)), 90_000.0, d("2029-01-01"), rates)
        assertEquals(100, r.legs.single().qty); assertEquals(0.0, r.leftoverUah, 1e-9)
        assertEquals(1000.0 / 900 - 1, r.ytm!!, 1e-10); assertEquals(10_000.0, r.incomeUah, 1e-9)
        assertEquals(10_000.0 / 90_000 * 100_000, r.per100k, 1e-6); assertEquals(365L, r.days); assertEquals(d("2030-01-01"), r.returnDate)
        assertEquals(1000.0 / 900 - 1, r.simple, 1e-10)
        assertEquals(0.0, r.legs.single().coupons, 0.0)
    }

    @Test fun priceAtYieldIsInverseOfXirr() {
        val b = depo.getValue("UA4000239149"); val settle = d("2026-11-07")
        val p = priceAtYield(b, settle, 0.151)
        assertTrue(p in 1000.0..1100.0)
        val leg = buyLeg(b, p, p * 10, settle, rates)
        assertEquals(10, leg.qty)
        assertEquals(0.151, cmpResult("x", listOf(leg), p * 10, settle, rates).ytm!!, 1e-9)
    }

    // ---------- кількість ----------
    @Test fun quantityIsRoundedDownWithLeftover() {
        val b = depo.getValue("UA4000239149"); val settle = d("2026-11-07")
        val leg = buyLeg(b, 1012.40, 338_000.0, settle, rates)
        assertEquals(333, leg.qty); assertEquals(333 * 1012.40, leg.invested, 1e-6); assertEquals(338_000 - 333 * 1012.40, leg.leftover, 1e-6)
        // рівно на 2 шт — без похибки округлення
        assertEquals(2, buyLeg(b, 1000.1, 2000.2, settle, rates).qty)
        assertEquals(0, buyLeg(b, 1000.0, 999.0, settle, rates).qty)
        // офіційний графік: 3 купони по 75,5 і номінал, лише після дати купівлі
        assertEquals(listOf("2027-01-20", "2027-07-21", "2028-01-19"), leg.flows.map { it.date.toString() })
        assertEquals(333 * 75.5, leg.flows[0].amount, 1e-6); assertEquals(333 * (75.5 + 1000), leg.flows[2].amount, 1e-6)
        assertEquals(2, buyLeg(b, 1000.0, 2500.0, d("2027-01-20"), rates).flows.size)  // купон у день купівлі вже не наш
    }

    // ---------- валютний випуск ----------
    @Test fun foreignCurrencyBondIsBoughtAtNbuRate() {
        val usd = depo.getValue("UA4000236541"); val settle = d("2026-11-07")
        val r = cmpResult(usd.isin, listOf(buyLeg(usd, 1000.0, 338_000.0, settle, rates)), 338_000.0, settle, rates)
        val leg = r.legs.single()
        assertEquals("USD", r.cur); assertTrue(r.fxRisk)
        assertEquals(8, leg.qty)                                   // 338 000 / 41 = 8 243,90 $ → 8 шт
        assertEquals(8000.0, leg.invested, 1e-9); assertEquals(338_000.0 / 41 - 8000, leg.leftover, 1e-9)
        assertEquals(8000 * 41.0, r.investedUah, 1e-6); assertEquals(338_000 - 328_000.0, r.leftoverUah, 1e-6)
        assertEquals(8 * (20.85 + 1000) * 41, r.flowsUah.single().amount, 1e-6)
        // YTM — у доларах: 20,85 на 1000 за 89 днів
        assertEquals((1020.85 / 1000).let { Math.pow(it, 365.0 / 89) - 1 }, r.ytm!!, 1e-9)
    }

    // ---------- ставка беззбитковості ----------
    @Test fun breakEvenRateForTwoZeroCoupons() {
        // 72 000 ₴: коротший 900 → 1000 через рік (80 шт → 80 000), довший 800 → 1000 через 2 роки (90 шт → 90 000)
        // 80 000 × (1 + r) = 90 000 → r = 12,5%
        val settle = d("2029-01-01")
        val res = compareOvdp(listOf(CmpPick(zero("UA4000000001", "2031-01-01"), 800.0), CmpPick(zero("UA4000000002", "2030-01-01"), 900.0)), 72_000.0, settle, rates)
        val s = res[0]; val l = res[1]
        assertEquals("відсортовано за погашенням", "UA4000000002", s.title); assertEquals("UA4000000001", l.title)
        val be = breakEvenRate(s, l)!!
        assertEquals(0.125, be, 1e-9)
        assertTrue(valueAt(l, l.returnDate, 0.10) > valueAt(s, l.returnDate, 0.10))   // нижче — вигідніший довший
        assertTrue(valueAt(s, l.returnDate, 0.15) > valueAt(l, l.returnDate, 0.15))   // вище — коротший
        assertEquals(90_000.0, valueAt(l, l.returnDate, 0.2), 1e-9)                   // довший без проміжних виплат
        val text = compareConclusion(res, 0.11)
        assertTrue(text, text.contains("Довший UA4000000001 вигідніший") && text.contains("12,50%") && text.contains("01.01.2030"))
        // драбина: по 36 000 ₴ — 40 і 45 шт; за r = 12,5% підсумок рівний обом
        val ladder = res[2]
        assertEquals("Драбина", ladder.title); assertEquals(listOf(40, 45), ladder.legs.map { it.qty })
        assertEquals(90_000.0, valueAt(ladder, l.returnDate, 0.125), 1e-6)
    }

    @Test fun couponBondsHaveBreakEvenAndCurveStartsAtAmount() {
        val settle = d("2026-11-07")
        val res = compareOvdp(listOf(CmpPick(depo.getValue("UA4000239149"), 1012.40), CmpPick(depo.getValue("UA4000239131"), 1005.0)), 338_000.0, settle, rates)
        assertEquals(3, res.size)
        val be = breakEvenRate(res[0], res[1])
        assertNotNull(be); assertTrue("ставка беззбитковості правдоподібна: $be", be!! in 0.05..0.40)
        val curve = valueCurve(res[0], settle, res[1].returnDate, 0.15)
        assertEquals(338_000.0, curve.first().amount, 1e-3)                      // на дату купівлі — уся сума
        assertEquals(valueAt(res[0], res[1].returnDate, 0.15), curve.last().amount, 1e-6)
        assertTrue(curve.zipWithNext().all { (a, b) -> b.amount >= a.amount - 1e-6 })
    }

    @Test fun taxReducesCouponsAndDiscount() {
        val b = depo.getValue("UA4000239149"); val settle = d("2026-11-07")
        val free = buyLeg(b, 990.0, 99_000.0, settle, rates)
        val taxed = buyLeg(b, 990.0, 99_000.0, settle, rates, taxPct = 10.0)
        assertEquals(0.0, free.tax, 0.0)
        // 10% з купонів (3 × 75,5 × 100) і дисконту (1000 − 990) × 100
        assertEquals((3 * 75.5 * 100 + 10 * 100) * 0.10, taxed.tax, 1e-6)
        assertEquals(free.flows.sumOf { it.amount } - taxed.tax, taxed.flows.sumOf { it.amount }, 1e-6)
    }
}
