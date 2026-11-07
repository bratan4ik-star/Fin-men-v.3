package ua.bella.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Купони ОВДП у дохід автоматично і погашення номіналу на рахунок (OvdpPayouts.kt). */
class OvdpPayoutsTest {
    private val rates = mapOf("USD" to 41.0, "EUR" to 45.0)
    private val reg by lazy {
        parseDepoSecurities(javaClass.classLoader!!.getResource("nbu/depo_securities_sample.json")!!.readText(), LocalDate.parse("2026-11-07")).associateBy { it.isin }
    }
    private val uah = Account("Моно", "UAH", 1000.0, id = "mono")
    private val usd = Account("Готівка USD", "USD", 100.0, id = "usd", cash = true)
    private fun data(vararg p: Pos) = AppData(txs = emptyList(), accs = listOf(uah, usd), debts = emptyList(), pos = p.toList(), goals = emptyList(), demo = false, recurring = emptyList())
    private val bond = Pos("ОВДП", "ОВДП UA4000239149", 10.0, 985.0, 990.0, "2028-01-19", 15.1, "2027-01-20", autoCpn = true, autoFrom = "2027-01-01", payAcc = "mono")

    @Test fun couponsFromEnablingDateGoToAccountAsIncome() {
        val d = processOvdpPayouts(data(bond), "2027-07-25", rates, reg)
        assertEquals(listOf("2027-07-21", "2027-01-20"), d.txs.map { it.date })  // новіші — першими
        d.txs.forEach { t -> assertEquals("income", t.type); assertEquals("Купон: ОВДП UA4000239149", t.desc); assertEquals(755.0, t.amt, 1e-9); assertEquals("mono", t.acc); assertEquals(755.0, t.accAmt, 1e-9) }
        assertEquals(1000.0 + 1510.0, d.accs.first { it.id == "mono" }.bal, 1e-9)
        assertEquals("2027-07-26", d.pos.single().autoFrom); assertEquals(10.0, d.pos.single().qty, 0.0)
        // повторний запуск того ж дня нічого не змінює
        assertSame(d, processOvdpPayouts(d, "2027-07-25", rates, reg))
    }

    @Test fun earlierCouponsAreNotBackfilled() {
        // увімкнено 21.01.2027 — січневий купон (20.01) уже минув і не дораховується
        val d = processOvdpPayouts(data(bond.copy(autoFrom = "2027-01-21")), "2027-07-25", rates, reg)
        assertEquals(listOf("2027-07-21"), d.txs.map { it.date })
        // увімкнено сьогодні, а купон завтра — поки нічого
        val d0 = data(bond.copy(autoFrom = "2027-01-19"))
        assertSame(d0, processOvdpPayouts(d0, "2027-01-19", rates, reg))
    }

    @Test fun maturityMovesNominalToAccountNotIncome() {
        val d = processOvdpPayouts(data(bond.copy(autoFrom = "2028-01-01")), "2028-01-20", rates, reg)
        val cpn = d.txs.single { it.type == "income" }; val red = d.txs.single { it.type == "transfer" }
        assertEquals(755.0, cpn.amt, 1e-9)
        assertEquals("Погашення: ОВДП UA4000239149", red.desc); assertEquals("", red.acc); assertEquals("mono", red.toAcc); assertEquals(10_000.0, red.toAmt, 1e-9); assertEquals("Перекази", red.cat)
        assertEquals(1000.0 + 10_755.0, d.accs.first { it.id == "mono" }.bal, 1e-9)
        val p = d.pos.single(); assertEquals(0.0, p.qty, 0.0); assertTrue(ovdpRedeemed(p, "2028-01-20"))
        // у доходах місяця — лише купон, номінал не дохід
        assertEquals(755.0, monthlyFlows(d.txs, "2028-01", 1).single().inc, 1e-9)
        // погашена позиція далі не обробляється
        assertSame(d, processOvdpPayouts(d, "2028-06-01", rates, reg))
    }

    @Test fun foreignCurrencyIsConvertedForAccount() {
        val p = Pos("ОВДП", "Валютна", 3.0, 1000.0, 1000.0, "2027-02-04", 4.17, "", CalcExtra("UA4000236541", 1000.0, 6, "", "", "2026-08-01", "USD"), cur = "USD",
            autoCpn = true, autoFrom = "2026-11-07", payAcc = "usd")
        val d = processOvdpPayouts(data(p), "2027-02-04", rates, reg)
        val cpn = d.txs.single { it.type == "income" }
        assertEquals(62.55 * 41.0, cpn.amt, 1e-6); assertEquals(62.55, cpn.accAmt, 1e-9)             // на доларовий рахунок — у доларах
        assertEquals(3000.0, d.txs.single { it.type == "transfer" }.toAmt, 1e-9)
        assertEquals(100.0 + 3062.55, d.accs.first { it.id == "usd" }.bal, 1e-9)
        // на гривневий рахунок — за курсом НБУ
        val d2 = processOvdpPayouts(data(p.copy(payAcc = "mono")), "2027-02-04", rates, reg)
        assertEquals(1000.0 + 3062.55 * 41.0, d2.accs.first { it.id == "mono" }.bal, 1e-6)
    }

    @Test fun deletedOperationIsNotRecreatedAndNoAccountMeansNothing() {
        val d = processOvdpPayouts(data(bond), "2027-01-20", rates, reg)
        assertEquals(1, d.txs.size)
        val del = deleteTx(d, 0)
        assertSame(del, processOvdpPayouts(del, "2027-01-25", rates, reg))
        // рахунок видалено або не вибрано — нічого не створюється, дата обробки не зсувається
        val noAcc = data(bond.copy(payAcc = "gone"))
        assertSame(noAcc, processOvdpPayouts(noAcc, "2027-07-25", rates, reg))
        // вимкнено — як раніше
        val off = data(bond.copy(autoCpn = false))
        assertSame(off, processOvdpPayouts(off, "2027-07-25", rates, reg))
    }

    @Test fun withoutRegistryUsesPositionDates() {
        val manual = Pos("ОВДП", "ОВДП A", 100.0, 960.0, 985.0, "2027-05-12", 15.5, "2026-12-10", autoCpn = true, autoFrom = "2026-11-07", payAcc = "mono")
        val d = processOvdpPayouts(data(manual), "2026-12-10", rates)
        assertEquals(100 * 1000 * 15.5 / 100 / 2, d.txs.single().amt, 1e-9)
    }

    @Test fun missingAccountIsHighlighted() {
        val f = Form(group = "ОВДП", pName = "ОВДП UA4000239149", pQty = "10", pBuy = "985", pAuto = true, pPayAcc = "")
        assertEquals(setOf("acc"), posMissing(f, null))
        assertTrue(posMissing(f.copy(pPayAcc = "mono"), null).isEmpty())
        // погашена — кількість 0 не підсвічується
        assertTrue(posMissing(f.copy(pQty = "0.0", pPayAcc = "mono"), null, redeemed = true).isEmpty())
    }
}
