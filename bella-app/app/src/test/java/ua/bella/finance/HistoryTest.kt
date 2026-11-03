package ua.bella.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryTest {
    private val e = AppData(txs = emptyList(), accs = emptyList(), debts = emptyList(), pos = emptyList(), goals = emptyList(), demo = false)

    @Test fun describesChanges() {
        val t = Tx("Сільпо", "Харчування", "expense", 100.0, "2026-11-03")
        assertEquals("Додано операцію: Сільпо", describeChange(e, e.copy(txs = listOf(t))))
        assertEquals("Видалено операцію: Сільпо", describeChange(e.copy(txs = listOf(t)), e))
        assertEquals("Змінено операцію: Сільпо", describeChange(e.copy(txs = listOf(t.copy(amt = 1.0))), e.copy(txs = listOf(t))))
        val g = Goal("Подушка", 0.0, 1.0, "flag", 1.0)
        assertEquals("Додано ціль: Подушка", describeChange(e, e.copy(goals = listOf(g))))
        assertEquals("Зміна цілі: Подушка", describeChange(e.copy(goals = listOf(g)), e.copy(goals = listOf(g.copy(saved = 5.0)))))
        assertEquals("Додано рахунок: A", describeChange(e, e.copy(accs = listOf(Account("A", "UAH", 1.0)))))
        assertEquals("Зміна лімітів і категорій", describeChange(e, e.copy(limits = mapOf("Житло" to 1.0))))
    }

    @Test fun undoSeveralSteps() {
        val snaps = listOf("s0", "s1", "s2", "s3") // стани «до» кожної з 4 дій; поточний — після останньої
        assertEquals("s3" to listOf("s0", "s1", "s2"), undoSteps(snaps, 1))
        assertEquals("s1" to listOf("s0"), undoSteps(snaps, 3))
        assertEquals("s0" to emptyList<String>(), undoSteps(snaps, 4))
        assertNull(undoSteps(snaps, 5)); assertNull(undoSteps(snaps, 0))
    }
}
