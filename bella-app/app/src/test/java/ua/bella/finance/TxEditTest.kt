package ua.bella.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TxEditTest {
    private val rates = mapOf("UAH" to 1.0, "USD" to 40.0, "EUR" to 45.0, "PLN" to 10.0)
    private val mono = Account("Monobank", "UAH", 10000.0, id = "m")
    private val privat = Account("ПриватБанк", "UAH", 5000.0, id = "p")
    private val usd = Account("Готівка USD", "USD", 100.0, cash = true, id = "u")
    private val base = AppData(txs = emptyList(), accs = listOf(mono, privat, usd), debts = emptyList(), pos = emptyList(), goals = emptyList(), demo = false)
    private fun bal(d: AppData, id: String) = d.accs.first { it.id == id }.bal
    private fun tx(inp: TxInput) = buildTx(inp, base.accs, rates, 1.0).first!!

    @Test fun expenseOnAccountReducesBalance() {
        val d = addTx(base, tx(TxInput("expense", 500.0, "Харчування", "Сільпо", "2026-11-03", acc = "m")))
        assertEquals(9500.0, bal(d, "m"), 1e-9)
        assertEquals(500.0, d.txs.first().amt, 1e-9)
    }

    @Test fun movingExpenseToAnotherAccountMovesMoney() {
        val d1 = addTx(base, tx(TxInput("expense", 500.0, "Харчування", "Сільпо", "2026-11-03", acc = "m")))
        val d2 = replaceTx(d1, 0, tx(TxInput("expense", 500.0, "Харчування", "Сільпо", "2026-11-03", acc = "p")))
        assertEquals(10000.0, bal(d2, "m"), 1e-9)
        assertEquals(4500.0, bal(d2, "p"), 1e-9)
    }

    @Test fun changingAmountAndTypeIsRecalculated() {
        val d1 = addTx(base, tx(TxInput("expense", 500.0, "Інше", "", "2026-11-03", acc = "m")))
        val d2 = replaceTx(d1, 0, tx(TxInput("income", 800.0, "Зарплата", "", "2026-11-03", acc = "m")))
        assertEquals(10800.0, bal(d2, "m"), 1e-9)
    }

    @Test fun foreignCurrencyAccountUsesAccountCurrency() {
        val t = tx(TxInput("expense", 10.0, "Інше", "Кава", "2026-11-03", acc = "u"))
        assertEquals(400.0, t.amt, 1e-9) // у гривнях для бюджету
        assertEquals(90.0, bal(addTx(base, t), "u"), 1e-9)
    }

    @Test fun deleteRestoresBalance() {
        val d1 = addTx(base, tx(TxInput("expense", 500.0, "Інше", "", "2026-11-03", acc = "m")))
        val d2 = deleteTx(d1, 0)
        assertTrue(d2.txs.isEmpty())
        assertEquals(10000.0, bal(d2, "m"), 1e-9)
    }

    @Test fun unboundTxDoesNotTouchBalancesUntilAssigned() {
        val legacy = Tx("Старий запис", "Інше", "expense", 300.0, "2026-10-01")
        val d1 = base.copy(txs = listOf(legacy))
        val d2 = replaceTx(d1, 0, legacy.copy(desc = "Перейменовано"))
        assertEquals(base.accs, d2.accs)
        val d3 = replaceTx(d2, 0, tx(TxInput("expense", 300.0, "Інше", "Перейменовано", "2026-10-01", acc = "p")))
        assertEquals(4700.0, bal(d3, "p"), 1e-9)
        assertEquals(10000.0, bal(d3, "m"), 1e-9)
    }

    @Test fun transferMovesMoneyAndCanBeEdited() {
        val t = tx(TxInput("transfer", 4000.0, "", "", "2026-11-03", acc = "m", toAcc = "u"))
        assertEquals("Переказ: Monobank → Готівка USD", t.desc)
        assertEquals(100.0, t.toAmt, 1e-9) // 4000 ₴ за курсом 40
        val d1 = addTx(base, t)
        assertEquals(6000.0, bal(d1, "m"), 1e-9); assertEquals(200.0, bal(d1, "u"), 1e-9)
        val d2 = replaceTx(d1, 0, tx(TxInput("transfer", 1000.0, "", "", "2026-11-03", acc = "m", toAcc = "p")))
        assertEquals(9000.0, bal(d2, "m"), 1e-9); assertEquals(6000.0, bal(d2, "p"), 1e-9); assertEquals(100.0, bal(d2, "u"), 1e-9)
        assertEquals(base.accs.map { it.bal }, deleteTx(d2, 0).accs.map { it.bal })
    }

    @Test fun transferWithManualReceivedAmount() {
        val t = tx(TxInput("transfer", 4100.0, "", "", "2026-11-03", acc = "m", toAcc = "u", toAmount = 99.5))
        assertEquals(199.5, bal(addTx(base, t), "u"), 1e-9)
    }

    @Test fun validationErrors() {
        assertEquals("Вкажіть суму більше нуля", buildTx(TxInput("expense", 0.0, "Інше", "", "2026-11-03"), base.accs, rates, 1.0).second)
        assertEquals("Дата у форматі РРРР-ММ-ДД", buildTx(TxInput("expense", 10.0, "Інше", "", "03.11.2026"), base.accs, rates, 1.0).second)
        assertEquals("Дата у форматі РРРР-ММ-ДД", buildTx(TxInput("expense", 10.0, "Інше", "", "2026-02-30"), base.accs, rates, 1.0).second)
        assertEquals("Оберіть різні рахунки", buildTx(TxInput("transfer", 10.0, "", "", "2026-11-03", acc = "m", toAcc = "m"), base.accs, rates, 1.0).second)
        assertEquals("Оберіть обидва рахунки", buildTx(TxInput("transfer", 10.0, "", "", "2026-11-03", acc = "m"), base.accs, rates, 1.0).second)
        assertNull(buildTx(TxInput("expense", 10.0, "Інше", "", "bad"), base.accs, rates, 1.0).first)
    }

    @Test fun unboundAmountUsesViewCurrency() {
        val (t, _) = buildTx(TxInput("expense", 10.0, "Інше", "", "2026-11-03"), base.accs, rates, 40.0)
        assertEquals(400.0, t!!.amt, 1e-9); assertEquals("", t.acc); assertEquals(0.0, t.accAmt, 1e-9)
    }

    @Test fun idsAreAssignedAndCarriedOverOnImport() {
        val d = ensureAccIds(base.copy(accs = listOf(Account("A", "UAH", 1.0), Account("B", "UAH", 2.0))))
        assertTrue(d.accs.all { it.id.isNotEmpty() }); assertNotEquals(d.accs[0].id, d.accs[1].id)
        assertTrue(ensureAccIds(d) === d)
        val old = listOf(Account("Картка •1234", "UAH", 0.0, id = "x"), Account("Моно", "UAH", 0.0, id = "y"))
        val new = carryAccIds(old, listOf(Account("Monobank •1234", "UAH", 5.0), Account("Моно", "UAH", 7.0), Account("Нова", "UAH", 1.0)))
        assertEquals(listOf("x", "y", ""), new.map { it.id })
    }
}
