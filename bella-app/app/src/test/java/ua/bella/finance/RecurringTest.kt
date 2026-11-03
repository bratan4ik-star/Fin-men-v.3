package ua.bella.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.LocalDate

class RecurringTest {
    private val rates = mapOf("UAH" to 1.0, "USD" to 40.0, "EUR" to 45.0, "PLN" to 10.0)
    private val mono = Account("Monobank", "UAH", 10000.0, id = "m")
    private val rent = Recurring("r1", "Оренда", "Житло", "expense", 5000.0, "m", 5000.0, 5, "2026-11-05")
    private fun data(vararg r: Recurring) = AppData(txs = emptyList(), accs = listOf(mono), debts = emptyList(), pos = emptyList(), goals = emptyList(), demo = false, recurring = r.toList())

    @Test fun nothingBeforeDate() { val d = data(rent); assertSame(d, processRecurring(d, "2026-11-04", rates)) }

    @Test fun createdOnDateAndMovesToNextMonth() {
        val r = processRecurring(data(rent), "2026-11-05", rates)
        assertEquals(1, r.txs.size); assertEquals("2026-11-05", r.txs[0].date); assertEquals("Оренда", r.txs[0].desc)
        assertEquals(5000.0, r.accs[0].bal, 1e-9); assertEquals("2026-12-05", r.recurring[0].next)
    }

    @Test fun missedMonthsAreCaughtUpOnce() {
        val r = processRecurring(data(rent), "2027-02-10", rates)
        assertEquals(listOf("2027-02-05", "2027-01-05", "2026-12-05", "2026-11-05"), r.txs.map { it.date })
        assertEquals(10000.0 - 4 * 5000.0, r.accs[0].bal, 1e-9); assertEquals("2027-03-05", r.recurring[0].next)
        assertSame(r, processRecurring(r, "2027-02-10", rates))
    }

    @Test fun pausedIsSkipped() { val d = data(rent.copy(active = false)); assertSame(d, processRecurring(d, "2027-02-10", rates)) }

    @Test fun duplicatesAreNotCreated() {
        val once = processRecurring(data(rent), "2026-11-05", rates)
        val back = once.copy(recurring = listOf(once.recurring[0].copy(next = "2026-11-05")))
        val again = processRecurring(back, "2026-11-05", rates)
        assertEquals(1, again.txs.size); assertEquals("2026-12-05", again.recurring[0].next)
    }

    @Test fun endOfMonthDayIsClamped() {
        assertEquals(LocalDate.parse("2027-02-28"), nextMonthly(LocalDate.parse("2027-01-31"), 31))
        assertEquals(LocalDate.parse("2027-03-31"), nextMonthly(LocalDate.parse("2027-02-28"), 31))
    }

    @Test fun deletedAccountGivesTxWithoutAccount() {
        val d = data(rent).copy(accs = emptyList())
        val r = processRecurring(d, "2026-11-05", rates)
        assertEquals("", r.txs[0].acc); assertEquals(0.0, r.txs[0].accAmt, 1e-9)
    }

    @Test fun templateFromTx() {
        val t = Tx("Netflix", "Розваги", "expense", 399.0, "2026-11-31".replace("31", "30"), "", "m", 399.0)
        val r = recurringFrom(t, 30)
        assertEquals("2026-12-30", r.next); assertEquals(399.0, r.amt, 1e-9); assertEquals("m", r.acc)
    }

    @Test fun historyDescribes() {
        val e = data()
        assertEquals("Додано повторювану операцію: Оренда", describeChange(e, data(rent)))
        assertEquals("Видалено повторювану операцію: Оренда", describeChange(data(rent), e))
    }
}
