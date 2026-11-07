package ua.bella.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IbkrChartsTest {
    private val xml = """<?xml version="1.0" encoding="UTF-8"?>
<FlexQueryResponse queryName="Bella" type="AF"><FlexStatements count="1">
<FlexStatement accountId="U1" fromDate="20260820" toDate="20260930" period="Last365CalendarDays">
<AccountInformation accountId="U1" currency="USD"/>
<EquitySummaryInBase>
<EquitySummaryByReportDateInBase reportDate="20260912" total="100"/>
<EquitySummaryByReportDateInBase reportDate="20260913" total="300"/>
<EquitySummaryByReportDateInBase reportDate="20260914" total="297"/>
</EquitySummaryInBase>
<CashTransactions>
<CashTransaction currency="USD" fxRateToBase="1" dateTime="20260913;101500" amount="200" type="Deposits/Withdrawals" transactionID="900" levelOfDetail="DETAIL"/>
<CashTransaction currency="EUR" fxRateToBase="1.1" dateTime="20260914" amount="-10" type="Deposits/Withdrawals" transactionID="901" levelOfDetail="DETAIL"/>
<CashTransaction currency="USD" dateTime="20260913" amount="5" type="Dividends" transactionID="902" levelOfDetail="DETAIL"/>
</CashTransactions>
</FlexStatement></FlexStatements></FlexQueryResponse>"""

    @Test fun parsesNavBaseAndDeposits() {
        val f = parseFlexStatement(xml)
        assertEquals("USD", f.base); assertEquals("2026-08-20", f.fromDate)
        assertEquals(listOf(HistPoint("2026-09-12", 100.0), HistPoint("2026-09-13", 300.0), HistPoint("2026-09-14", 297.0)), f.nav)
        assertEquals(2, f.flows.size); assertEquals(200.0, f.flows[0].amount, 1e-9); assertEquals(-11.0, f.flows[1].amount, 1e-9)
    }

    @Test fun windowSeparatesDepositsFromReturn() {
        val f = parseFlexStatement(xml)
        val (nav, flows) = mergeIbHist(emptyList(), emptyList(), f, null, "2026-09-14")
        val w = ibWindow(nav, flows, "1Y")
        assertTrue(w.capped)
        assertEquals(100.0, w.start, 1e-9); assertEquals(297.0, w.value, 1e-9)
        assertEquals(189.0, w.deposits, 1e-9)               // +200 і −11
        assertEquals(8.0, w.gain, 1e-9)                     // 297 − 100 − 189
        assertEquals(289.0, w.pts.last().invested, 1e-9)
        // 13.09: (300 − 200) / 100 = 1 → 0%; 14.09: (297 + 11) / 300 → +2,67%
        assertEquals(308.0 / 300.0 - 1, w.twr, 1e-9)
        val w7 = ibWindow(nav, flows, "7D"); assertTrue(w7.capped)
    }

    @Test fun depositDatedBeforeNavReflectsItDoesNotBreakTwr() {
        // поповнення 200 датоване 13.09, а вартість зросла лише 14.09
        val nav = listOf(HistPoint("2026-09-12", 100.0), HistPoint("2026-09-13", 101.0), HistPoint("2026-09-14", 302.0), HistPoint("2026-09-15", 305.0))
        val w = ibWindow(nav, listOf(IbFlow("1", "2026-09-13", 200.0)), "1Y")
        // 13.09: +1%; 14.09: (302 − 200) / 101 → +0,99%; 15.09: 305 / 302
        assertEquals(1.01 * (102.0 / 101.0) * (305.0 / 302.0) - 1, w.twr, 1e-9)
        assertEquals(300.0, w.pts[1].invested, 1e-9)   // «внесено» — за датою операції
        assertEquals(5.0, w.gain, 1e-9)
    }

    @Test fun mergeKeepsOldHistoryAndAddsSnapshotWithoutDuplicates() {
        val f = parseFlexStatement(xml)
        val old = listOf(HistPoint("2026-08-01", 50.0), HistPoint("2026-09-12", 1.0))
        val oldFlows = listOf(IbFlow("800", "2026-08-01", 50.0), IbFlow("900", "2026-09-13", 200.0), IbFlow("x", "2026-09-01", 7.0))
        val (nav, flows) = mergeIbHist(old, oldFlows, f, 310.0, "2026-09-20")
        assertEquals(listOf("2026-08-01", "2026-09-12", "2026-09-13", "2026-09-14", "2026-09-20"), nav.map { it.d })
        assertEquals(100.0, nav[1].v, 1e-9)                 // NAV зі звіту замінює старий знімок
        assertEquals(310.0, nav.last().v, 1e-9)
        // до початку звіту (20.08) лишається старе; у межах звіту — лише зі звіту
        assertEquals(listOf("800", "900", "901"), flows.map { it.id })
        val (nav2, flows2) = mergeIbHist(nav, flows, f, 310.0, "2026-09-20")
        assertEquals(nav, nav2); assertEquals(flows, flows2)
    }

    @Test fun windowFromStartOfPeriod() {
        val nav = (0..40).map { HistPoint(java.time.LocalDate.parse("2026-09-01").plusDays(it.toLong()).toString(), 100.0 + it) }
        val w = ibWindow(nav, emptyList(), "7D")
        assertFalse(w.capped); assertEquals("2026-10-04", w.pts.first().d); assertEquals(8, w.pts.size)
        assertEquals(140.0 / 133.0 - 1, w.twr, 1e-9); assertEquals(7.0, w.gain, 1e-9)
        assertEquals("2026-05-11", periodStart("2026-11-11", "6M")); assertEquals("2021-11-11", periodStart("2026-11-11", "5Y"))
    }

    @Test fun pricesFromPriorPeriodPositionsAndMarkPrice() {
        val x = xml.replace("<CashTransactions>", """<OpenPositions><OpenPosition currency="USD" assetCategory="STK" symbol="VOO" position="1" markPrice="605" costBasisPrice="500" levelOfDetail="SUMMARY"/></OpenPositions>
<PriorPeriodPositions>
<PriorPeriodPosition currency="USD" assetCategory="STK" symbol="VOO" date="20260928" price="550"/>
<PriorPeriodPosition currency="USD" assetCategory="STK" symbol="VOO" date="20260921" price="500"/>
<PriorPeriodPosition currency="USD" assetCategory="STK" symbol="VOO" date="bad" price="1"/>
</PriorPeriodPositions>
<CashTransactions>""")
        val f = parseFlexStatement(x)
        assertEquals(listOf(HistPoint("2026-09-21", 500.0), HistPoint("2026-09-28", 550.0)), f.prices["VOO"])
        val m = mergeIbPrices(mapOf("VOO" to listOf(HistPoint("2026-09-01", 480.0), HistPoint("2026-09-21", 1.0))), f)
        // старі дні лишаються, дні зі звіту замінюють, ціна на дату звіту (30.09) — з Open Positions
        assertEquals(listOf("2026-09-01" to 480.0, "2026-09-21" to 500.0, "2026-09-28" to 550.0, "2026-09-30" to 605.0), m["VOO"]!!.map { it.d to it.v })
        val w = priceWindow(m["VOO"]!!, "7D")
        assertEquals(listOf("2026-09-21", "2026-09-28", "2026-09-30"), w.map { it.d }); assertEquals(605.0 / 500 - 1, w.last().v, 1e-9)
        assertEquals(m, mergeIbPrices(m, f))
    }

    @Test fun thinKeepsEndsAndLimitsPoints() {
        val h = (0 until 1300).map { HistPoint(java.time.LocalDate.parse("2021-11-01").plusDays(it.toLong()).toString(), it.toDouble()) }
        val t = thin(h)
        assertTrue(t.size <= 301); assertEquals(h.first(), t.first()); assertEquals(h.last(), t.last())
        assertEquals(h.take(10), thin(h.take(10)))
    }

    @Test fun allocationAndPnlFromIbkrPositions() {
        val r = mapOf("UAH" to 1.0, "USD" to 40.0, "EUR" to 48.0, "PLN" to 10.0)
        val d = AppData(txs = emptyList(), debts = emptyList(), goals = emptyList(), demo = false,
            accs = listOf(Account("IBKR USD", "USD", 31.89, id = "ibkr-USD"), Account("Моно", "UAH", 1000.0)),
            pos = listOf(
                Pos("ETF", "VOO", 0.7332, 0.0, 0.0, cur = "USD", pxBuy = 702.95, pxNow = 680.56, buyFx = 41.0, src = "ibkr", ibSym = "VOO"),
                Pos("ETF", "VEA", 1.114, 0.0, 0.0, cur = "USD", pxBuy = 72.25, pxNow = 68.08, buyFx = 41.0, src = "ibkr", ibSym = "VEA"),
                Pos("ETF", "VWCE", 2.0, 0.0, 0.0, cur = "EUR", pxBuy = 100.0, pxNow = 120.0, buyFx = 45.0)))
        val a = ibkrAlloc(d, r, "USD")
        assertEquals(listOf("VOO", "VEA", "Готівка"), a.map { it.name })
        assertEquals(0.7332 * 680.56, a[0].value, 1e-6); assertEquals(31.89, a[2].value, 1e-9)
        assertEquals(0.7332 * 680.56 + 1.114 * 68.08 + 31.89, ibkrSnapshot(d, r, "USD")!!, 1e-6)
        val p = ibkrPnl(d)
        assertEquals(listOf("VOO", "VEA"), p.map { it.name })
        assertEquals(0.7332 * (680.56 - 702.95), p[0].pnl, 1e-9); assertTrue(p[0].pct < 0)
    }
}
