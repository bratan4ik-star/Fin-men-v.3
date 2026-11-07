package ua.bella.finance

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupingTest {
    private val accs = listOf(
        Account("Mono", "UAH", 1.0), Account("Готівка USD", "USD", 2.0, cash = true), Account("Скарбничка", "UAH", 3.0, sav = true),
        Account("Приват", "UAH", 4.0), Account("Готівка UAH", "UAH", 5.0, cash = true),
    )

    @Test fun accountsAreGroupedWithOriginalIndexes() {
        val g = groupAccounts(accs)
        assertEquals(listOf(AccGroup.CARDS, AccGroup.CASH, AccGroup.SAVINGS), g.map { it.first })
        assertEquals(listOf(0, 3), g[0].second.map { it.index })
        assertEquals(listOf(1, 4), g[1].second.map { it.index })
    }

    @Test fun emptyGroupsAreSkipped() {
        assertEquals(listOf(AccGroup.CARDS), groupAccounts(listOf(Account("A", "UAH", 1.0))).map { it.first })
        assertEquals(emptyList<Any>(), groupAccounts(emptyList()))
    }

    @Test fun savingsWinsOverCash() { assertEquals(AccGroup.SAVINGS, accGroup(Account("X", "UAH", 1.0, cash = true, sav = true))) }

    @Test fun debtsGroupedByDirectionWithOpenTotals() {
        val d = listOf(Debt("A", true, 100.0), Debt("B", false, 50.0), Debt("C", true, 30.0, done = true), Debt("D", true, 20.0))
        val g = groupDebts(d)
        assertEquals(listOf("Мені винні", "Я винен"), g.map { it.title })
        assertEquals(120.0, g[0].openTotal, 1e-9); assertEquals(3, g[0].items.size); assertEquals(1, g[1].items.size)
        assertEquals(listOf("Мені винні"), groupDebts(listOf(Debt("A", true, 1.0))).map { it.title })
    }

    @Test fun everyScreenHasHelpSection() {
        // розділ довідки — саме про цей екран (раніше після додавання розділів індекси з'їхали: «Калькулятор» відкривав «Нагадування»)
        val title = mapOf(Screen.Home to "Головна", Screen.Acc to "Капітал та Рахунки", Screen.Bud to "Бюджет", Screen.Inv to "Інвестиції",
            Screen.Goals to "Цілі та пасивний дохід", Screen.Pass to "Цілі та пасивний дохід", Screen.Calc to "Калькулятор ОВДП")
        Screen.values().forEach { s -> assertEquals(s.name, title[s], HELP[helpSectionFor(s)].second.first) }
    }

    @Test fun returnedDebtIsRemoved() {
        val d = listOf(Debt("A", true, 1.0), Debt("B", false, 2.0), Debt("C", true, 3.0))
        assertEquals(listOf("A", "C"), settleDebt(d, 1).map { it.who })
        assertEquals(d, settleDebt(d, 9))
    }
}
