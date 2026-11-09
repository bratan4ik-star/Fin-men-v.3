package ua.bella.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Перекласифікація імпортованих операцій (Reclass.kt). Рядки знеособлені. */
class ReclassTest {
    private fun imp(desc: String, cat: String, type: String, amt: Double, a: Double, acc: String = "") =
        Tx(desc, cat, type, amt, "2026-10-01", "2026-10-01|5168 **** **** 1983|$a|$desc", acc, amt)

    @Test fun oldImportsGetNewCategories() {
        val txs = listOf(
            imp("Купівля державних облігацій", "Інше", "expense", 500000.0, -500000.0),
            imp("На картку", "Перекази", "expense", 1000.0, -1000.0),
            imp("Прізвище І.", "Перекази", "expense", 404.0, -404.0),
            imp("Зарплата, X. Коментар: Hroshove zabezpechennia-za veresen'", "Надходження", "income", 21470.0, 21470.0),
            imp("Прізвище І.", "Надходження", "income", 500.0, 500.0),
        )
        val ch = reclassifyImported(txs)
        assertEquals(5, ch.size)
        assertEquals(Triple("transfer", "Інвестиції", 0), ch[0].new.let { Triple(it.type, it.cat, ch[0].index) })
        assertEquals("Перекази на картки (перевірити)", ch[1].new.cat)
        assertEquals("Перекази людям", ch[2].new.cat)
        assertEquals(Pair("income", "Грошове забезпечення"), ch[3].new.type to ch[3].new.cat)
        assertEquals(Pair("income", "Перекази від людей"), ch[4].new.type to ch[4].new.cat)
    }

    @Test fun manualAndUpToDateAreNotTouched() {
        val manual = Tx("Сільпо", "Харчування", "expense", 500.0, "2026-10-01")          // ключ порожній
        val upToDate = imp("Зарплата, X. Коментар: Hroshove", "Грошове забезпечення", "income", 21470.0, 21470.0)
        assertTrue(reclassifyImported(listOf(manual, upToDate)).isEmpty())
    }

    @Test fun amountsAndBalancesUnchanged() {
        // операція прив'язана до рахунку: після перекласифікації баланс той самий
        val acc = Account("Картка", "UAH", 900.0, id = "a")
        val old = imp("Купівля державних облігацій", "Інше", "expense", 100.0, -100.0, acc = "a").copy(accAmt = 100.0)
        val d = AppData(txs = listOf(old), accs = listOf(acc), debts = emptyList(), pos = emptyList(), goals = emptyList(), demo = false)
        val after = applyReclass(d, reclassifyImported(d.txs))
        assertEquals(900.0, after.accs.single().bal, 1e-9)
        assertEquals(100.0, after.txs.single().amt, 1e-9)
        assertEquals("transfer", after.txs.single().type)
    }
}
