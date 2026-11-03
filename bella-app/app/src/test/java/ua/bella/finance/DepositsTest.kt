package ua.bella.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DepositsTest {
    private val rates = mapOf("UAH" to 1.0, "USD" to 40.0, "EUR" to 45.0, "PLN" to 10.0)
    private val mono = Account("Monobank", "UAH", 1000.0, id = "m")
    private val dep = Pos("Депозити", "Депозит Моно", 1.0, 120000.0, 120000.0, mat = "2027-03-01", rate = 10.0, next = "2026-11-05", monthly = true, payAcc = "m")
    private fun data(p: Pos) = AppData(txs = emptyList(), accs = listOf(mono), debts = emptyList(), pos = listOf(p), goals = emptyList(), demo = false)

    @Test fun monthlyNetAfterTax() {
        // 120 000 × 10% / 12 = 1000, після 23% податку = 770
        assertEquals(770.0, depositMonthlyNet(dep), 1e-9)
    }

    @Test fun noPayoutBeforeDate() {
        val d = data(dep)
        assertSame(d, processDepositPayouts(d, "2026-11-04", rates))
    }

    @Test fun payoutOnDateGoesToAccountAndMovesNext() {
        val r = processDepositPayouts(data(dep), "2026-11-05", rates)
        assertEquals(1, r.txs.size)
        val t = r.txs[0]
        assertEquals("Відсотки: Депозит Моно", t.desc); assertEquals("income", t.type); assertEquals("2026-11-05", t.date)
        assertEquals(770.0, t.amt, 1e-9); assertEquals("m", t.acc)
        assertEquals(1770.0, r.accs[0].bal, 1e-9)
        assertEquals("2026-12-05", r.pos[0].next)
    }

    @Test fun missedMonthsAreCaughtUpOnceAndStopAtReturnDate() {
        val r = processDepositPayouts(data(dep), "2027-06-01", rates)
        assertEquals(listOf("2027-02-05", "2027-01-05", "2026-12-05", "2026-11-05"), r.txs.map { it.date })
        assertEquals("", r.pos[0].next) // після дати повернення виплат немає
        assertEquals(1000.0 + 4 * 770.0, r.accs[0].bal, 1e-9)
        assertSame(r, processDepositPayouts(r, "2027-06-01", rates)) // повторний запуск нічого не додає
    }

    @Test fun duplicatesAreNotCreatedWhenNextWasResetManually() {
        val r1 = processDepositPayouts(data(dep), "2026-11-05", rates)
        val back = r1.copy(pos = listOf(r1.pos[0].copy(next = "2026-11-05")))
        val r2 = processDepositPayouts(back, "2026-11-05", rates)
        assertEquals(1, r2.txs.size); assertEquals("2026-12-05", r2.pos[0].next)
    }

    @Test fun withoutAccountIncomeDoesNotChangeBalances() {
        val r = processDepositPayouts(data(dep.copy(payAcc = "")), "2026-11-05", rates)
        assertEquals("", r.txs[0].acc); assertEquals(1000.0, r.accs[0].bal, 1e-9)
    }

    @Test fun nonMonthlyAndNonDepositsAreIgnored() {
        val plain = data(dep.copy(monthly = false))
        assertSame(plain, processDepositPayouts(plain, "2027-01-01", rates))
        assertTrue(processDepositPayouts(data(dep.copy(group = "ОВДП")), "2027-01-01", rates).txs.isEmpty())
    }

    @Test fun endOfMonthDatesStayValid() {
        val r = processDepositPayouts(data(dep.copy(next = "2027-01-31", mat = "")), "2027-03-01", rates)
        assertEquals(listOf("2027-02-28", "2027-01-31"), r.txs.map { it.date })
        assertEquals("2027-03-28", r.pos[0].next)
    }

    @Test fun formValidation() {
        assertEquals("Вкажіть назву депозиту", posFormError("Депозити", " ", 1.0, 1000.0, false, 10.0, "", ""))
        assertEquals("Вкажіть суму депозиту", posFormError("Депозити", "Д", 1.0, 0.0, false, 10.0, "", ""))
        assertEquals("Вкажіть кількість більше нуля", posFormError("ETF", "VWCE", 0.0, 100.0, false, 0.0, "", ""))
        assertEquals("Вкажіть ціну покупки", posFormError("ETF", "VWCE", 1.0, 0.0, false, 0.0, "", ""))
        assertEquals("Для щомісячних виплат вкажіть дату виплати", posFormError("Депозити", "Д", 1.0, 1000.0, true, 10.0, "", ""))
        assertEquals("Для щомісячних виплат вкажіть ставку", posFormError("Депозити", "Д", 1.0, 1000.0, true, 0.0, "2026-12-01", ""))
        assertEquals("", posFormError("Депозити", "Д", 1.0, 1000.0, true, 10.0, "2026-12-01", "2027-06-01"))
    }
}
