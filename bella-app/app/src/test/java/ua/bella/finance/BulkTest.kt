package ua.bella.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BulkTest {
    private val accs = listOf(Account("Моно", "UAH", 10000.0, id = "a"), Account("Готівка", "USD", 100.0, id = "b"))
    private val txs = listOf(
        Tx("Сільпо", "Інше", "expense", 500.0, "2026-11-01", acc = "a", accAmt = 500.0),
        Tx("Зарплата", "Зарплата", "income", 3000.0, "2026-11-01", acc = "a", accAmt = 3000.0),
        Tx("Обмін", "Перекази", "transfer", 4150.0, "2026-11-02", acc = "a", accAmt = 4150.0, toAcc = "b", toAmt = 100.0),
        Tx("АТБ", "Інше", "expense", 200.0, "2026-11-03"),
    )
    private val d = AppData(txs = txs, accs = accs, debts = emptyList(), pos = emptyList(), goals = emptyList(), demo = false)

    @Test fun setCategorySkipsTransfersAndKeepsBalances() {
        val r = bulkSetCat(d, setOf(0, 2, 3), "Харчування")
        assertEquals(listOf("Харчування", "Зарплата", "Перекази", "Харчування"), r.txs.map { it.cat })
        assertEquals(d.accs, r.accs)
    }

    @Test fun deleteRevertsEveryAccountEffect() {
        val r = bulkDelete(d, setOf(0, 1, 2))
        assertEquals(listOf("АТБ"), r.txs.map { it.desc })
        // Моно: +500 (витрата) −3000 (дохід) +4150 (переказ), Готівка: −100
        assertEquals(10000.0 + 500 - 3000 + 4150, r.accs[0].bal, 1e-9)
        assertEquals(0.0, r.accs[1].bal, 1e-9)
    }

    @Test fun emptySelectionChangesNothing() {
        assertSame(d, bulkDelete(d, emptySet()))
        assertSame(d, bulkSetCat(d, emptySet(), "Харчування"))
    }

    @Test fun historyLabelsCountBulkChanges() {
        assertEquals("Видалено операцій: 2", describeChange(d, bulkDelete(d, setOf(0, 3))))
        assertEquals("Змінено операцій: 2", describeChange(d, bulkSetCat(d, setOf(0, 3), "Харчування")))
        assertEquals("Видалено операцію: Сільпо", describeChange(d, bulkDelete(d, setOf(0))))
    }
}
