package ua.bella.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AlertsTest {
    private val today = LocalDate.parse("2026-11-10")
    private fun exp(cat: String, amt: Double, date: String = "2026-11-05") = Tx(cat, cat, "expense", amt, date)

    @Test fun limitAt80PercentOnceAMonth() {
        val txs = listOf(exp("Харчування", 8500.0), exp("Харчування", 9999.0, "2026-10-20"))
        val a = limitAlerts(txs, mapOf("Харчування" to 10000.0), today, emptySet()).single()
        assertEquals("lim|Харчування|80|2026-11-30", a.key)
        assertEquals("Ліміт «Харчування»: 85%", a.title)
        assertTrue(a.text.contains("лишилось 1 500 ₴"))
        assertTrue(limitAlerts(txs, mapOf("Харчування" to 10000.0), today, setOf(a.key)).isEmpty())
    }

    @Test fun overLimitSends100AndCloses80() {
        val txs = listOf(exp("Розваги", 2300.0))
        val a = limitAlerts(txs, mapOf("Розваги" to 2000.0), today, emptySet()).single()
        assertEquals("Ліміт «Розваги» перевищено", a.title)
        assertTrue(a.text.endsWith("(+300 ₴)"))
        val sent = alertKeys(a).toSet()
        assertEquals(setOf("lim|Розваги|100|2026-11-30", "lim|Розваги|80|2026-11-30"), sent)
        assertTrue(limitAlerts(txs, mapOf("Розваги" to 2000.0), today, sent).isEmpty())
    }

    @Test fun after80Comes100() {
        val txs = listOf(exp("Транспорт", 3100.0))
        val a = limitAlerts(txs, mapOf("Транспорт" to 3000.0), today, setOf("lim|Транспорт|80|2026-11-30")).single()
        assertTrue(a.key.contains("|100|"))
    }

    @Test fun belowThresholdOrNoLimitIsSilent() {
        val txs = listOf(exp("Житло", 100.0), exp("Інше", 99999.0))
        assertTrue(limitAlerts(txs, mapOf("Житло" to 20000.0, "Інше" to 0.0), today, emptySet()).isEmpty())
    }

    @Test fun limitKeysAreKeptUntilMonthIsOver() {
        val k = setOf("lim|Харчування|80|2026-11-30")
        assertEquals(k, pruneNotified(k, LocalDate.parse("2026-11-30")))
        assertTrue(pruneNotified(k, LocalDate.parse("2027-01-15")).isEmpty())
    }

    @Test fun debtSoonAndDueDay() {
        val debts = listOf(
            Debt("Іван", true, 12300.0, false, "2026-11-12"),
            Debt("Оренда", false, 7500.0, false, "2026-11-10"),
            Debt("Далеко", false, 100.0, false, "2026-12-20"),
            Debt("Повернено", true, 100.0, true, "2026-11-11"),
            Debt("Без дати", true, 100.0, false, ""),
        )
        val a = debtAlerts(debts, today, emptySet())
        assertEquals(listOf("Через 2 дні мають повернути вам борг", "Сьогодні термін повернення боргу"), a.map { it.title })
        assertEquals("Іван: 12 300 ₴ · 12.11.2026", a[0].text)
        assertTrue(debtAlerts(debts, today, a.map { it.key }.toSet()).isEmpty())
    }

    @Test fun prevMonthUsesSameDaysForCurrentMonth() {
        val txs = listOf(exp("Харчування", 100.0, "2026-10-03"), exp("Харчування", 50.0, "2026-10-10"), exp("Харчування", 999.0, "2026-10-11"), exp("Таксі", 70.0, "2026-10-01"))
        assertEquals(mapOf("Харчування" to 150.0, "Таксі" to 70.0), prevMonthByCat(txs, "2026-11", "2026-11-10"))
        // минулий місяць — повністю
        assertEquals(1149.0, prevMonthByCat(txs, "2026-11", "2026-12-02")["Харчування"]!!, 1e-9)
    }

    @Test fun prevMonthClampsToShorterMonth() {
        val txs = listOf(exp("Харчування", 10.0, "2026-02-28"))
        assertEquals(10.0, prevMonthByCat(txs, "2026-03", "2026-03-31")["Харчування"]!!, 1e-9)
    }

    @Test fun changePercent() {
        assertEquals(50, changePct(150.0, 100.0)); assertEquals(-25, changePct(75.0, 100.0)); assertNull(changePct(10.0, 0.0))
    }
}
