package ua.bella.finance

import org.junit.Assert.assertEquals
import org.junit.Test

class FreeLeftTest {
    private val today = "2026-11-10"
    private val txs = listOf(
        Tx("Зарплата", "Зарплата", "income", 50000.0, "2026-11-01"),
        Tx("Сільпо", "Харчування", "expense", 4000.0, "2026-11-05"),
        Tx("Таксі", "Транспорт", "expense", 1000.0, "2026-11-06"),
        Tx("Минулий місяць", "Харчування", "expense", 9999.0, "2026-10-20"),
        Tx("Переказ", "Перекази", "transfer", 7000.0, "2026-11-07"),
    )
    private fun rec(cat: String, amt: Double, next: String, type: String = "expense", active: Boolean = true) =
        Recurring("r-$cat-$next", cat, cat, type, amt, day = next.takeLast(2).toInt(), next = next, active = active)

    @Test fun withoutRecurringAndLimitsIsIncomeMinusExpense() {
        val f = freeLeft(txs, emptyList(), emptyMap(), today)
        assertEquals(45000.0, f.freeUah, 1e-9)
        assertEquals(21, f.daysLeft) // 10..30 листопада
        assertEquals(45000.0 / 21, f.perDayUah, 1e-9)
    }

    @Test fun upcomingRecurringExpensesThisMonthAreReserved() {
        val recs = listOf(
            rec("Житло", 18000.0, "2026-11-20"),
            rec("Розваги", 400.0, "2026-12-01"),          // наступного місяця — не враховується
            rec("Зарплата", 9000.0, "2026-11-25", "income"), // дохід — не враховується
            rec("Спорт", 800.0, "2026-11-28", active = false), // призупинено
        )
        val f = freeLeft(txs, recs, emptyMap(), today)
        assertEquals(18000.0, f.recurringUah, 1e-9)
        assertEquals(27000.0, f.freeUah, 1e-9)
    }

    @Test fun unusedLimitsAreReservedWithoutDoubleCountingRecurring() {
        val recs = listOf(rec("Житло", 18000.0, "2026-11-20"))
        val limits = mapOf("Харчування" to 10000.0, "Транспорт" to 500.0, "Житло" to 20000.0, "Інше" to 0.0)
        val f = freeLeft(txs, recs, limits, today)
        // Харчування 10000−4000 = 6000; Транспорт перевищено → 0; Житло 20000−18000 = 2000
        assertEquals(8000.0, f.limitsUah, 1e-9)
        assertEquals(45000.0 - 18000.0 - 8000.0, f.freeUah, 1e-9)
    }

    @Test fun negativeFreeGivesZeroPerDay() {
        val f = freeLeft(txs, emptyList(), mapOf("Розваги" to 60000.0), today)
        assertEquals(-15000.0, f.freeUah, 1e-9)
        assertEquals(0.0, f.perDayUah, 1e-9)
    }

    @Test fun lastDayOfMonthHasOneDayLeft() {
        assertEquals(1, freeLeft(txs, emptyList(), emptyMap(), "2026-11-30").daysLeft)
    }
}
