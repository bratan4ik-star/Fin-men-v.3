package ua.bella.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IbkrTest {
    private val rates = mapOf("UAH" to 1.0, "USD" to 41.0, "EUR" to 48.0, "PLN" to 10.0)
    private val sendOk = """<FlexStatementResponse timestamp="06 November, 2026 10:00 AM EST"><Status>Success</Status><ReferenceCode>1234567890</ReferenceCode><Url>https://ndcdyn.interactivebrokers.com/AccountManagement/FlexWebService/GetStatement</Url></FlexStatementResponse>"""
    private val inProgress = """<FlexStatementResponse timestamp="x"><Status>Warn</Status><ErrorCode>1019</ErrorCode><ErrorMessage>Statement generation in progress. Please try again shortly.</ErrorMessage></FlexStatementResponse>"""
    private val badToken = """<FlexStatementResponse timestamp="x"><Status>Fail</Status><ErrorCode>1015</ErrorCode><ErrorMessage>Token is invalid.</ErrorMessage></FlexStatementResponse>"""
    private val statement = """<?xml version="1.0" encoding="UTF-8"?>
<FlexQueryResponse queryName="Bella" type="AF"><FlexStatements count="1">
<FlexStatement accountId="U1234567" fromDate="20251106" toDate="20261105" period="Last365CalendarDays" whenGenerated="20261106;100000">
<OpenPositions>
<OpenPosition accountId="U1234567" currency="EUR" assetCategory="STK" symbol="VWCE" description="VANGUARD FTSE ALL-WORLD UCITS" listingExchange="IBIS2" position="20" markPrice="125.5" costBasisPrice="104.2" levelOfDetail="SUMMARY"/>
<OpenPosition accountId="U1234567" currency="EUR" assetCategory="STK" symbol="VWCE" description="lot" listingExchange="IBIS2" position="10" markPrice="125.5" costBasisPrice="100" levelOfDetail="LOT"/>
<OpenPosition accountId="U1234567" currency="USD" assetCategory="STK" symbol="VWRA" description="VANGUARD FTSE ALL-WORLD USD ACC" listingExchange="LSEETF" position="5" markPrice="150.25" costBasisPrice="130" levelOfDetail="SUMMARY"/>
<OpenPosition accountId="U1234567" currency="GBP" assetCategory="STK" symbol="VUKE" description="UK" listingExchange="LSEETF" position="3" markPrice="40" costBasisPrice="38" levelOfDetail="SUMMARY"/>
<OpenPosition accountId="U1234567" currency="USD" assetCategory="OPT" symbol="SPY 261218C600" description="opt" listingExchange="CBOE" position="1" markPrice="2" costBasisPrice="1" levelOfDetail="SUMMARY"/>
</OpenPositions>
<CashReport>
<CashReportCurrency accountId="U1234567" currency="BASE_SUMMARY" endingCash="999"/>
<CashReportCurrency accountId="U1234567" currency="USD" endingCash="1,234.56"/>
<CashReportCurrency accountId="U1234567" currency="EUR" endingCash="10"/>
</CashReport>
<CashTransactions>
<CashTransaction currency="USD" symbol="VWRA" description="VWRA Cash Dividend" dateTime="20260625;202000" amount="10.5" type="Dividends" transactionID="111" levelOfDetail="DETAIL"/>
<CashTransaction currency="USD" symbol="VWRA" description="VWRA Withholding" dateTime="20260625;202000" amount="-1.58" type="Withholding Tax" transactionID="112" levelOfDetail="DETAIL"/>
<CashTransaction currency="USD" symbol="VWRA" description="dup" dateTime="20260625;202000" amount="10.5" type="Dividends" transactionID="111" levelOfDetail="DETAIL"/>
<CashTransaction currency="USD" symbol="" description="Deposit" dateTime="20260101" amount="1000" type="Deposits/Withdrawals" transactionID="113" levelOfDetail="DETAIL"/>
</CashTransactions>
</FlexStatement></FlexStatements></FlexQueryResponse>"""

    @Test fun statusResponses() {
        val ok = parseFlexStatus(sendOk)!!
        assertTrue(ok.ok); assertEquals("1234567890", ok.ref); assertTrue(ok.url.endsWith("GetStatement"))
        assertTrue(parseFlexStatus(inProgress)!!.retry)
        val bad = parseFlexStatus(badToken)!!
        assertFalse(bad.ok); assertFalse(bad.retry); assertTrue(flexError(bad).contains("недійсний"))
        assertNull(parseFlexStatus(statement))
    }

    @Test fun parsesPositionsCashAndDividends() {
        val f = parseFlexStatement(statement)
        assertEquals("U1234567", f.account); assertEquals("2026-11-05", f.asOf)
        assertEquals(listOf("VWCE", "VWRA", "VUKE", "SPY 261218C600"), f.positions.map { it.symbol }) // LOT-рядок відкинуто
        assertEquals(20.0, f.positions[0].qty, 1e-9); assertEquals(104.2, f.positions[0].cost, 1e-9)
        assertEquals(mapOf("USD" to 1234.56, "EUR" to 10.0), f.cash)
        val dv = f.dividends.single()
        assertEquals("2026-06-25", dv.date); assertEquals(10.5, dv.amount, 1e-9); assertEquals(-1.58, dv.tax, 1e-9)
    }

    @Test fun realIbkrErrorResponse() {
        // дослівна відповідь сервера IBKR на хибний токен (06.11.2026)
        val real = "<FlexStatementResponse timestamp='06 November, 2026 02:41 AM EST'>\n<Status>Fail</Status>\n<ErrorCode>1015</ErrorCode>\n<ErrorMessage>Token is invalid.</ErrorMessage>\n</FlexStatementResponse>\n"
        val st = parseFlexStatus(real)!!
        assertFalse(st.ok); assertEquals("1015", st.code); assertEquals("Token is invalid.", st.message)
    }

    @Test fun error1020IsExplained() {
        val st = parseFlexStatus("<FlexStatementResponse timestamp='x'><Status>Fail</Status><ErrorCode>1020</ErrorCode><ErrorMessage>Invalid request or unable to validate request.</ErrorMessage></FlexStatementResponse>")!!
        assertFalse(st.retry); assertTrue(flexError(st).contains("Query ID"))
    }

    @Test fun implausibleBuyFxIsDetectedAndNotKeptOnSync() {
        // VOO зі скріншота: курс купівлі ≈703 ₴/$ замість ≈45
        val voo = Pos("ETF", "VOO", 0.7332, 691.32 * 703, 673.97 * 45.44, cur = "USD", pxBuy = 691.32, pxNow = 673.97, buyFx = 703.0, ticker = "VOO")
        val r = mapOf("UAH" to 1.0, "USD" to 45.44)
        assertFalse(fxPlausible(703.0, 45.44)); assertTrue(fxPlausible(41.0, 45.44))
        assertTrue(fxFormError(voo, r).contains("не схожий"))
        assertEquals("", fxFormError(voo.copy(buyFx = 41.2), r))
        val xml = statement.replace("symbol=\"VWRA\"", "symbol=\"VOO\"")
        val d = AppData(txs = emptyList(), accs = emptyList(), debts = emptyList(), pos = listOf(voo), goals = emptyList(), demo = false)
        val synced = applyFlex(d, parseFlexStatement(xml), r + ("EUR" to 48.0), 0L).first.pos.first { it.name == "VOO" }
        assertEquals(45.44, synced.buyFx, 1e-9)
    }

    @Test fun queryIdValidation() {
        assertTrue(flexQueryError("U1234567").contains("номер рахунку"))
        assertTrue(flexQueryError("Bella").contains("лише цифри"))
        assertEquals("", flexQueryError(" 987654 "))
    }

    @Test fun autoSyncAtMostHourly() {
        val h = 3600_000L
        assertTrue(ibkrDue(0L, 0L, 10 * h))
        assertFalse(ibkrDue(10 * h - 10, 0L, 10 * h))        // щойно синхронізовано
        assertFalse(ibkrDue(0L, 10 * h - 60_000, 10 * h))    // щойно була невдала спроба
        assertTrue(ibkrDue(8 * h, 9 * h, 10 * h))
    }

    @Test fun dates() {
        assertEquals("2026-11-05", flexDate("20261105")); assertEquals("2026-11-05", flexDate("2026-11-05")); assertEquals("2026-11-05", flexDate("20261105;101500"))
        assertEquals("2026-11-05", flexDate("11/05/2026")); assertEquals("", flexDate("щось"))
    }

    @Test fun yahooTickers() {
        assertEquals("VWCE.DE", yahooTicker("VWCE", "IBIS2")); assertEquals("VWRA.L", yahooTicker("VWRA", "LSEETF"))
        assertEquals("SPY", yahooTicker("SPY", "ARCA")); assertEquals("BRK-B", yahooTicker("BRK B", "NYSE"))
    }

    @Test fun gbpPositionIsAcceptedWhenGbpRateIsKnown() {
        val d = AppData(txs = emptyList(), accs = emptyList(), debts = emptyList(), pos = emptyList(), goals = emptyList(), demo = false)
        val (nd, s) = applyFlex(d, parseFlexStatement(statement), rates + ("GBP" to 54.0), 0L)
        val vuke = nd.pos.first { it.ibSym == "VUKE" }
        assertEquals("GBP", vuke.cur); assertEquals(3 * 40 * 54.0, posValueUah(vuke, rates + ("GBP" to 54.0)), 1e-6)
        assertEquals(listOf("SPY 261218C600 (OPT)"), s.skipped)
        assertTrue("GBP" in CURRENCIES && "CHF" in CURRENCIES && RATES.containsKey("GBP") && RATES.containsKey("CHF"))
    }

    @Test fun mergeUpdatesByTickerAddsNewAndSkipsUnsupported() {
        val mine = Pos("ETF", "VWCE (Xetra)", 15.0, 4800.0, 5900.0, cur = "EUR", pxBuy = 100.0, pxNow = 120.0, buyFx = 46.0, ticker = "VWCE.DE")
        val manual = Pos("ETF", "SPY у Freedom", 2.0, 1.0, 1.0, ticker = "SPY")
        val d = AppData(txs = emptyList(), accs = emptyList(), debts = emptyList(), pos = listOf(mine, manual), goals = emptyList(), demo = false)
        val (nd, s) = applyFlex(d, parseFlexStatement(statement), rates, 1000L)
        assertEquals(1, s.updated); assertEquals(1, s.added)
        val vwce = nd.pos.first { it.ticker == "VWCE.DE" }
        assertEquals("VWCE (Xetra)", vwce.name); assertEquals(20.0, vwce.qty, 1e-9); assertEquals(125.5, vwce.pxNow, 1e-9); assertEquals(104.2, vwce.pxBuy, 1e-9)
        assertEquals(46.0, vwce.buyFx, 1e-9) // курс купівлі з ручної позиції зберігається
        assertEquals("ibkr", vwce.src)
        val vwra = nd.pos.first { it.ibSym == "VWRA" }
        assertEquals("VWRA.L", vwra.ticker); assertEquals("USD", vwra.cur); assertEquals(5 * 150.25 * 41, posValueUah(vwra, rates), 1e-6)
        assertTrue(nd.pos.contains(manual)) // ручний ETF не чіпаємо
        assertEquals(listOf("VUKE (GBP)", "SPY 261218C600 (OPT)"), s.skipped)
        assertTrue(s.gone.isEmpty())
    }

    @Test fun cashAccountsAndDividendsWithoutDuplicates() {
        val d = AppData(txs = emptyList(), accs = emptyList(), debts = emptyList(), pos = emptyList(), goals = emptyList(), demo = false)
        val (d1, s1) = applyFlex(d, parseFlexStatement(statement), rates, 0L)
        assertEquals(listOf("IBKR USD" to 1234.56, "IBKR EUR" to 10.0), d1.accs.map { it.name to it.bal })
        val div = d1.txs.single()
        assertEquals("income", div.type); assertEquals("", div.acc); assertEquals((10.5 - 1.58) * 41, div.amt, 1e-6)
        assertTrue(div.desc.startsWith("Дивіденди VWRA")); assertEquals("Дивіденди", investKind(div))
        assertEquals(1, s1.dividends); assertEquals(2, s1.cash)
        val (d2, s2) = applyFlex(d1, parseFlexStatement(statement.replace("1,234.56", "2000")), rates, 0L)
        assertEquals(1, d2.txs.size); assertEquals(0, s2.dividends)
        assertEquals(2000.0, d2.accs.first { it.id == "ibkr-USD" }.bal, 1e-9); assertEquals(2, d2.accs.size)
    }

    @Test fun reversedRepostedAndLateTaxDividendsAreNotDuplicated() {
        val d = AppData(txs = emptyList(), accs = emptyList(), debts = emptyList(), pos = emptyList(), goals = emptyList(), demo = false)
        val noTax = statement.replace(Regex("<CashTransaction [^>]*Withholding[^>]*/>"), "")
        val (d1, _) = applyFlex(d, parseFlexStatement(noTax), rates, 0L)
        assertEquals(10.5 * 41, d1.txs.single().amt, 1e-6)
        // наступного дня податок дописано, курс уже інший — запис уточнюється за курсом першого запису, а не додається
        val (d2, s2) = applyFlex(d1, parseFlexStatement(statement), rates + ("USD" to 45.0), 0L)
        assertEquals(1, d2.txs.size); assertEquals(0, s2.dividends); assertEquals(1, s2.divFixed)
        assertEquals((10.5 - 1.58) * 41, d2.txs.single().amt, 1e-6); assertTrue(d2.txs.single().desc.contains("податок"))
        // IBKR сторнував і провів дивіденд заново
        val repost = statement.replace("<CashTransaction currency=\"USD\" symbol=\"\"",
            "<CashTransaction currency=\"USD\" symbol=\"VWRA\" dateTime=\"20260625;202000\" amount=\"-10.5\" type=\"Dividends\" transactionID=\"120\" levelOfDetail=\"DETAIL\"/>" +
            "<CashTransaction currency=\"USD\" symbol=\"VWRA\" dateTime=\"20260625;202000\" amount=\"10.5\" type=\"Dividends\" transactionID=\"121\" levelOfDetail=\"DETAIL\"/>" +
            "<CashTransaction currency=\"USD\" symbol=\"\"")
        val (d3, s3) = applyFlex(d2, parseFlexStatement(repost), rates, 0L)
        assertEquals(1, d3.txs.size); assertEquals(0, s3.dividends); assertEquals(0, s3.divFixed)
        assertEquals((10.5 - 1.58) * 41, d3.txs.single().amt, 1e-6)
        val (d4, s4) = applyFlex(d3, parseFlexStatement(repost), rates, 0L)
        assertEquals(d3.txs, d4.txs); assertEquals(0, s4.dividends)
    }

    @Test fun oldDividendKeysAreMigratedWithoutRevaluation() {
        val old = Tx("Дивіденди VWRA (податок 1,58 \$)", "Надходження", "income", 360.0, "2026-06-25", "ibkr|div|111")
        val d = AppData(txs = listOf(old), accs = emptyList(), debts = emptyList(), pos = emptyList(), goals = emptyList(), demo = false)
        val (d1, s1) = applyFlex(d, parseFlexStatement(statement), rates, 0L)
        assertEquals(1, d1.txs.size); assertEquals(0, s1.dividends); assertEquals(0, s1.divFixed)
        assertEquals(360.0, d1.txs.single().amt, 1e-9); assertTrue(d1.txs.single().key.startsWith("ibkr|div|111+112|"))
    }

    @Test fun currencyGoneFromCashReportIsZeroed() {
        val d = AppData(txs = emptyList(), accs = emptyList(), debts = emptyList(), pos = emptyList(), goals = emptyList(), demo = false)
        val (d1, _) = applyFlex(d, parseFlexStatement(statement), rates, 0L)
        val noEur = statement.replace("<CashReportCurrency accountId=\"U1234567\" currency=\"EUR\" endingCash=\"10\"/>", "")
        val (d2, _) = applyFlex(d1, parseFlexStatement(noEur), rates, 0L)
        assertEquals(0.0, d2.accs.first { it.id == "ibkr-EUR" }.bal, 1e-9); assertEquals(1234.56, d2.accs.first { it.id == "ibkr-USD" }.bal, 1e-9)
        // звіт без розділу про гроші нічого не обнуляє
        val noCash = statement.replace(Regex("<CashReport>.*?</CashReport>", RegexOption.DOT_MATCHES_ALL), "")
        assertEquals(d1.accs, applyFlex(d1, parseFlexStatement(noCash), rates, 0L).first.accs)
    }

    @Test fun soldPositionsAreReportedAndRemovable() {
        val old = Pos("ETF", "EUNL", 3.0, 1.0, 1.0, cur = "EUR", pxBuy = 1.0, pxNow = 1.0, src = "ibkr", ibSym = "EUNL")
        val d = AppData(txs = emptyList(), accs = emptyList(), debts = emptyList(), pos = listOf(old), goals = emptyList(), demo = false)
        val (nd, s) = applyFlex(d, parseFlexStatement(statement), rates, 0L)
        assertEquals(listOf("EUNL"), s.gone)
        assertTrue(removeGone(nd, s.gone).pos.none { it.name == "EUNL" })
    }

    @Test fun fetchRetriesWhileInProgress() {
        val calls = mutableListOf<String>(); var n = 0
        val (f, err) = fetchFlex("tok", "42", sleep = {}, get = { u -> calls += u; when { u.contains("SendRequest") -> sendOk; n++ < 2 -> inProgress; else -> statement } })
        assertEquals("", err); assertEquals("U1234567", f!!.account)
        assertTrue(calls[0].contains("t=tok") && calls[0].contains("q=42") && calls[0].contains("v=3"))
        assertTrue(calls[1].contains("GetStatement?q=1234567890"))
        assertEquals(4, calls.size)
    }

    @Test fun firstCheckIsQuickAndWaitingStopsAfterAboutFourMinutes() {
        val sleeps = mutableListOf<Long>()
        val (f, err) = fetchFlex("tok", "42", sleep = { sleeps += it }, get = { u -> if (u.contains("SendRequest")) sendOk else inProgress })
        assertEquals(null, f); assertTrue(err.contains("4 хвилини"))
        assertEquals(1500L, sleeps.first()); assertTrue(sleeps.all { it <= 15000L })
        assertTrue(sleeps.sum() in 240_000L..256_000L)
    }

    @Test fun fetchReportsBadToken() {
        val (f, err) = fetchFlex("bad", "42", sleep = {}, get = { badToken })
        assertNull(f); assertTrue(err.contains("недійсний"))
    }
}
