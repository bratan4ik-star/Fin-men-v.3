package ua.bella.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/** 1.29.5: повтори мережі, переказ латиницею, відсотки, ключ з «|», прив'язка Скарбнички за цифрами. Рядки знеособлені. */
class Release1295Test {
    private val card = "5168 **** **** 1983"

    // ---------- мережа ----------
    @Test fun retriesOnServerErrorsAndTimeoutsOnly() {
        assertTrue(nbuShouldRetry(504, null)); assertTrue(nbuShouldRetry(500, null))
        assertTrue(nbuShouldRetry(null, SocketTimeoutException("read")))
        assertTrue(nbuShouldRetry(null, IOException("reset")))
        assertFalse(nbuShouldRetry(404, null)); assertFalse(nbuShouldRetry(200, null))
        assertFalse(nbuShouldRetry(null, IllegalStateException("parse")))
    }

    @Test fun backoffIsTwoFiveTenSeconds() {
        assertEquals(listOf(2_000L, 5_000L, 10_000L), NBU_RETRY_PAUSES_MS.toList())
        assertEquals(60_000, NBU_READ_TIMEOUT_MS); assertEquals(20_000, NBU_CONNECT_TIMEOUT_MS)
    }

    // ---------- імпорт ----------
    private val header = mapOf("A" to "Дата", "B" to "Категорія", "C" to "Картка", "D" to "Опис операції", "E" to "Сума в валюті картки",
        "F" to "Валюта картки", "G" to "Сума в валюті транзакції", "H" to "Валюта транзакції", "I" to "Залишок на кінець періоду", "J" to "Валюта залишку")
    private fun row(date: String, cat: String, desc: String, amt: String) = mapOf("A" to date, "B" to cat, "C" to card, "D" to desc,
        "E" to amt, "F" to "UAH", "G" to amt.removePrefix("-"), "H" to "UAH", "I" to "1000.00", "J" to "UAH")
    private fun parse(vararg rows: Map<String, String>) = Importer.readRows(listOf(header) + rows.toList(), DEFAULT_RULES, emptyMap())!!

    @Test fun latinPersonTransfersAreIncome() {
        val r = parse(row("06.11.2026 10:00:00", "Зарахування", "VIKTOR CHICHIHIN, KYIV", "700.00"),
            row("06.11.2026 10:01:00", "Зарахування", "від SEMCHII ANDRII", "900.00"))
        assertEquals(listOf("Перекази від людей", "Перекази від людей"), r.txs.map { it.cat })
        assertTrue(r.txs.all { it.type == "income" })
    }

    @Test fun interestGoesToItsOwnCategory() {
        val r = parse(row("14.10.2026 12:00:00", "Заощадження", "% за депозитом", "150.00"),
            row("14.10.2026 12:01:00", "Заощадження", "Купон ОВДП", "16350.00"))
        assertEquals(listOf("Відсотки та купони", "Відсотки та купони"), r.txs.map { it.cat })
    }

    @Test fun pipeInDescriptionDoesNotBreakBankKey() {
        val r = parse(row("01.11.2026 10:00:00", "Супермаркети та продукти", "МАГАЗИН | ФІЛІЯ 3", "-500.00"))
        val t = r.txs.single()
        assertTrue(isBankImported(t)); assertEquals("1983", cardOfKey(t.key))
        assertEquals(4, t.key.split("|").size)
        assertFalse(t.key.contains("ФІЛІЯ 3|") && t.key.split("|").size > 4)
    }

    @Test fun savingsTransferFoundByDigitsInDescription() {
        val card7174 = Account("Депозит 1", "UAH", 0.0, sav = true, id = "d1", card = "1697")
        val sav2 = Account("Скарбничка", "UAH", 0.0, sav = true, id = "d2")
        val t = Tx("Поповнення депозиту 2620**1697", "Заощадження", "transfer", 500.0, "2026-10-01", "2026-10-01|x|-500|Поповнення депозиту 2620**1697", "c", 500.0)
        val r = linkSavingsTransfers(listOf(t), listOf(card7174, sav2)).single()
        assertEquals("d1", r.toAcc)
    }

    @Test fun savingsFallbackFromUserChoice() {
        val s1 = Account("Скарбничка", "UAH", 0.0, sav = true, id = "d1")
        val s2 = Account("Депозит", "UAH", 0.0, sav = true, id = "d2")
        val t = Tx("Переказ", "Заощадження", "transfer", 500.0, "2026-10-01", "2026-10-01|x|-500|Переказ", "c", 500.0)
        assertEquals("", linkSavingsTransfers(listOf(t), listOf(s1, s2)).single().toAcc)           // без вибору — без прив'язки
        assertEquals("d2", linkSavingsTransfers(listOf(t), listOf(s1, s2), fallbackId = "d2").single().toAcc)
    }

    @Test fun reclassLinksOnlyWithoutTouchingBalances() {
        val acc = Account("Картка", "UAH", 900.0, id = "c", card = "1983")
        val sav = Account("Скарбничка", "UAH", 100.0, sav = true, id = "s")
        val old = Tx("Скарбничка", "Заощадження", "transfer", 300.0, "2026-10-01", "2026-10-01|$card|-300|Скарбничка")
        val d = AppData(txs = listOf(old), accs = listOf(acc, sav), debts = emptyList(), pos = emptyList(), goals = emptyList(), demo = false)
        val ch = reclassifyImported(d.txs, d.accs)
        assertEquals(1, ch.size)
        val after = applyReclass(d, ch)
        assertEquals("c", after.txs.single().acc); assertEquals("s", after.txs.single().toAcc)
        assertEquals(900.0, after.accs[0].bal, 1e-9); assertEquals(100.0, after.accs[1].bal, 1e-9)
    }
}
