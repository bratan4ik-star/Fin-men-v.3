package ua.bella.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsTest {
    private val today = "2026-11-02"
    private val txs = listOf(
        Tx("Зарплата", "Зарплата", "income", 10000.0, "2026-11-01"),
        Tx("ВЕЛИКА ПОКУПКА", "Житло", "expense", 6000.0, "2026-11-01"),
        Tx("Кава", "Харчування", "expense", 100.0, "2026-11-01"),
        Tx("Переказ", "Перекази", "transfer", 5000.0, "2026-11-01"),
    )

    @Test fun runwayUsesAverageMonthlyExpense() {
        val r = smartAnalytics(txs, txs, 30000.0, today)
        // середні витрати за місяць = 6100, готівка 30000
        assertEquals(30000.0 / 6100.0, r.runwayMonths!!, 1e-9)
    }

    @Test fun projectionFollowsDailyBurnRate() {
        val r = smartAnalytics(txs, txs, 0.0, today)
        // 6100 за 2 дні, у листопаді 30 днів
        assertEquals(3050.0, r.burnPerDayUah, 1e-9)
        assertEquals(3050.0 * 30, r.projectedUah, 1e-9)
    }

    @Test fun insightsCoverSavingsConcentrationAndCushion() {
        val r = smartAnalytics(txs, txs, 30000.0, today)
        assertEquals(listOf("Норма заощаджень 39%", "Висока концентрація витрат", "Фінансова подушка"), r.insights.map { it.title })
        assertTrue(r.insights[0].ok)
        assertTrue(!r.insights[1].ok && r.insights[1].text.contains("«Житло»"))
        assertTrue(!r.insights[2].ok)
    }

    @Test fun topExpensesAreSortedAndExcludeTransfers() {
        val r = smartAnalytics(txs, txs, 0.0, today)
        assertEquals(listOf("ВЕЛИКА ПОКУПКА", "Кава"), r.top.map { it.desc })
    }

    @Test fun overspendingIsFlagged() {
        val bad = listOf(Tx("Дохід", "Зарплата", "income", 1000.0, "2026-11-01"), Tx("Витрата", "Інше", "expense", 3000.0, "2026-11-01"))
        val r = smartAnalytics(bad, bad, 0.0, today)
        assertEquals("Витрати перевищують доходи", r.insights.first().title)
    }

    @Test fun noExpensesGivesNoRunwayAndNoTop() {
        val onlyIncome = listOf(Tx("Дохід", "Зарплата", "income", 1000.0, "2026-11-01"))
        val r = smartAnalytics(onlyIncome, onlyIncome, 5000.0, today)
        assertNull(r.runwayMonths)
        assertTrue(r.top.isEmpty())
        assertNotNull(r.insights.firstOrNull { it.title.startsWith("Норма заощаджень") })
    }

    @Test fun emptyDataDoesNotCrash() {
        val r = smartAnalytics(emptyList(), emptyList(), 0.0, today)
        assertNull(r.runwayMonths)
        assertTrue(r.insights.isEmpty() && r.top.isEmpty())
    }

    @Test fun longRunwayIsCapped() {
        assertEquals("99+", fmtMonths(250.0))
        assertEquals("4,9", fmtMonths(4.92))
    }
}
