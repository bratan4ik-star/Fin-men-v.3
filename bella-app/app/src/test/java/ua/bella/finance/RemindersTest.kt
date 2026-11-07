package ua.bella.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RemindersTest {
    private fun d(s: String) = LocalDate.parse(s)
    private val manual = Pos("ОВДП", "ОВДП A", 100.0, 960.0, 985.0, "2027-05-12", 15.5, "2026-12-10")
    private val fromCalc = Pos("ОВДП", "ОВДП B", 10.0, 1000.0, 1000.0, "2027-06-01", 16.0, "", CalcExtra("UA1", 1000.0, 6, "", "", "2026-11-01"))

    @Test fun manualPositionCouponsAndMaturity() {
        val ev = ovdpEvents(listOf(manual), d("2026-11-04"), d("2027-12-31"))
        assertEquals(listOf("2026-12-10", "2027-05-12"), ev.map { it.date.toString() })
        assertEquals(100 * 1000 * 15.5 / 100 * 6 / 12, ev[0].coupon, 1e-6); assertEquals(0.0, ev[0].principal, 0.0)
        assertTrue(ev[1].maturity); assertEquals(100_000.0, ev[1].principal, 0.0)
    }

    @Test fun calcPositionGoesBackFromMaturity() {
        val ev = ovdpEvents(listOf(fromCalc), d("2026-11-04"), d("2027-12-31"))
        assertEquals(listOf("2026-12-01", "2027-06-01"), ev.map { it.date.toString() })
        assertEquals(800.0, ev[0].coupon, 1e-9); assertEquals(10_800.0, ev[1].total, 1e-9)
    }

    @Test fun zeroCouponOnlyMaturity() {
        val p = fromCalc.copy(calc = fromCalc.calc!!.copy(freq = 0))
        val ev = ovdpEvents(listOf(p), d("2026-11-04"), d("2027-12-31"))
        assertEquals(1, ev.size); assertEquals(0.0, ev[0].coupon, 0.0); assertEquals(10_000.0, ev[0].principal, 0.0)
    }

    @Test fun otherGroupsIgnored() { assertTrue(ovdpEvents(listOf(manual.copy(group = "Депозити")), d("2026-01-01"), d("2030-01-01")).isEmpty()) }

    @Test fun remindThreeDaysAheadAndOnTheDay() {
        val ev = ovdpEvents(listOf(manual), d("2026-11-01"), d("2027-12-31"))
        assertTrue(remindersDue(ev, d("2026-12-06"), emptySet()).isEmpty())
        val soon = remindersDue(ev, d("2026-12-07"), emptySet()).single()
        assertEquals(false, soon.today); assertEquals(3L, soon.daysLeft); assertEquals("Через 3 дні купон ОВДП", reminderTitle(soon))
        assertTrue(remindersDue(ev, d("2026-12-08"), setOf(soon.key)).isEmpty())
        val day = remindersDue(ev, d("2026-12-10"), setOf(soon.key)).single()
        assertEquals("Сьогодні купон ОВДП", reminderTitle(day))
        assertTrue(remindersDue(ev, d("2026-12-10"), setOf(soon.key, day.key)).isEmpty())
    }

    @Test fun missedSoonIsCaughtUp() { assertEquals(1L, remindersDue(ovdpEvents(listOf(manual), d("2026-11-01"), d("2027-12-31")), d("2026-12-09"), emptySet()).single().daysLeft) }

    @Test fun pruneOldKeys() {
        val k = setOf("day|A|2026-01-01", "soon|B|2026-12-10", "junk")
        assertEquals(setOf("soon|B|2026-12-10"), pruneNotified(k, d("2026-11-04")))
    }

    @Test fun autoBackupRotationKeepsNewest() {
        val names = (1..10).map { autoBackupName("2026-%02d-01".format(it)) } + "bella-backup-2026-01-01.json" + "notes.txt"
        assertEquals(listOf(autoBackupName("2026-02-01"), autoBackupName("2026-01-01")), autoBackupsToDelete(names))
    }

    @Test fun autoBackupWeekly() {
        val week = 7 * 24 * 3600_000L
        assertTrue(autoBackupDue(0L, 1000L)); assertTrue(!autoBackupDue(1000L, 1000L + 3 * 24 * 3600_000L)); assertTrue(autoBackupDue(1000L, 1000L + week))
    }

    @Test fun monthlyFlowsIncludeEmptyMonths() {
        val tx = listOf(Tx("a", "Зарплата", "income", 100.0, "2026-11-01"), Tx("b", "Їжа", "expense", 40.0, "2026-09-03"), Tx("c", "Перекази", "transfer", 999.0, "2026-11-02"))
        val f = monthlyFlows(tx, "2026-11", 3)
        assertEquals(listOf("2026-09", "2026-10", "2026-11"), f.map { it.month })
        assertEquals(40.0, f[0].exp, 0.0); assertEquals(0.0, f[1].inc, 0.0); assertEquals(100.0, f[2].inc, 0.0); assertEquals(0.0, f[2].exp, 0.0)
    }

    @Test fun histPeriodFilters() {
        val h = listOf(HistPoint("2026-01-01", 1.0), HistPoint("2026-10-20", 2.0), HistPoint("2026-11-04", 3.0))
        assertEquals(2, histPeriod(h, 30, "2026-11-04").size); assertEquals(3, histPeriod(h, 0, "2026-11-04").size)
    }

    @Test fun reminderText_formatsForeignCurrencyWithHryvniaEquivalent() {
        val ev = OvdpEvent(
            date = LocalDate.parse("2027-04-20"),
            name = "Бердянськ (USD)",
            coupon = 45.0,
            principal = 1000.0,
            cur = "USD"
        )
        val rem = Reminder("soon|Бердянськ (USD)|2027-04-20", ev, false, 3L)
        val rates = mapOf("UAH" to 1.0, "USD" to 40.0)
        val text = reminderText(rem, rates)
        // Expected to contain $ sign and ≈ 41 800 ₴
        assertTrue("Must contain USD sign", text.contains("$"))
        assertTrue("Must contain UAH equivalent", text.contains("41") || text.contains("41 800") || text.contains("41800"))
        assertEquals("Через 3 дні погашення ОВДП", reminderTitle(rem))
    }
}
