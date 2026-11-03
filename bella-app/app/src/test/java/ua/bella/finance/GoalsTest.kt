package ua.bella.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoalsTest {
    private val rates = mapOf("UAH" to 1.0, "USD" to 40.0, "EUR" to 45.0, "PLN" to 10.0)
    private val d = AppData(txs = emptyList(), accs = listOf(Account("Monobank", "UAH", 10000.0, id = "m"), Account("Готівка USD", "USD", 100.0, cash = true, id = "u")),
        debts = emptyList(), pos = emptyList(), goals = listOf(Goal("Подушка", 1000.0, 50000.0, "shield", 1000.0)), demo = false)

    @Test fun depositFromAccountMovesMoney() {
        val (r, e) = moveGoal(d, 0, 3000.0, "m", rates)
        assertEquals("", e)
        assertEquals(4000.0, r!!.goals[0].saved, 1e-9); assertEquals(7000.0, r.accs[0].bal, 1e-9)
    }

    @Test fun foreignAccountUsesItsCurrency() {
        val (r, _) = moveGoal(d, 0, 2000.0, "u", rates) // 2000 ₴ = 50 $
        assertEquals(50.0, r!!.accs[1].bal, 1e-9); assertEquals(3000.0, r.goals[0].saved, 1e-9)
    }

    @Test fun withdrawReturnsToAccount() {
        val (r, _) = moveGoal(d, 0, -500.0, "m", rates)
        assertEquals(500.0, r!!.goals[0].saved, 1e-9); assertEquals(10500.0, r.accs[0].bal, 1e-9)
    }

    @Test fun withoutAccountOnlyGoalChanges() {
        val (r, _) = moveGoal(d, 0, 700.0, "", rates)
        assertEquals(1700.0, r!!.goals[0].saved, 1e-9); assertEquals(d.accs, r.accs)
    }

    @Test fun errors() {
        assertEquals("Недостатньо коштів на рахунку «Monobank»", moveGoal(d, 0, 20000.0, "m", rates).second)
        assertEquals("У цілі лише 1 000 ₴", moveGoal(d, 0, -2000.0, "m", rates).second)
        assertNull(moveGoal(d, 5, 100.0, "m", rates).first)
        assertEquals("Вкажіть назву цілі", goalFormError(" ", 10.0, 0.0, 1.0, ""))
        assertEquals("Вкажіть суму цілі більше нуля", goalFormError("Ціль", 0.0, 0.0, 1.0, ""))
        assertEquals("Крок внеску має бути більше нуля", goalFormError("Ціль", 10.0, 0.0, 0.0, ""))
        assertEquals("Дедлайн у форматі РРРР-ММ-ДД", goalFormError("Ціль", 10.0, 0.0, 1.0, "31.12.2027"))
        assertEquals("", goalFormError("Ціль", 10.0, 0.0, 1.0, "2027-12-31"))
    }

    @Test fun oldGoalsWithoutDeadlineLoad() {
        assertEquals("", Goal("x", 0.0, 1.0, "flag", 1.0).deadline)
    }
}
