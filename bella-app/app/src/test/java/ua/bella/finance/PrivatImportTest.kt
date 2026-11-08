package ua.bella.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Виписка ПриватБанку (колонки: Дата; Категорія; Картка; Опис операції; Сума в валюті картки; Валюта картки;
 * Сума в валюті транзакції; Валюта транзакції; Залишок на кінець періоду; Валюта залишку).
 * Рядки вигадані і знеособлені: без імен, номерів карток і реальних сум.
 */
class PrivatImportTest {
    private val header = mapOf("A" to "Дата", "B" to "Категорія", "C" to "Картка", "D" to "Опис операції", "E" to "Сума в валюті картки",
        "F" to "Валюта картки", "G" to "Сума в валюті транзакції", "H" to "Валюта транзакції", "I" to "Залишок на кінець періоду", "J" to "Валюта залишку")

    private fun row(date: String, cat: String, card: String, desc: String, amt: String, cur: String = "UAH", bal: String = "1000.00") = mapOf(
        "A" to date, "B" to cat, "C" to card, "D" to desc, "E" to amt, "F" to cur, "G" to amt.removePrefix("-"), "H" to cur, "I" to bal, "J" to cur)

    private fun parse(vararg data: Map<String, String>, archive: Map<String, Map<String, Double>> = emptyMap()): ImportResult =
        Importer.readRows(listOf(header) + data.toList(), DEFAULT_RULES, archive)!!

    private val card = "5168 **** **** 1983"

    @Test fun investmentsAreNotExpenses() {
        val r = parse(
            row("22.09.2026 10:00:00", "Інше", card, "Купівля державних облігацій", "-500000.00"),
            row("16.07.2026 09:00:00", "Платежі за реквізитами", card, "ТОВ «ІНВЕСТИЦІЙНИЙ КАПІТАЛ УКРАЇНА». Коментар: Oplata za FI", "-90000.00"),
            row("10.07.2026 09:00:00", "Платежі за реквізитами", card, "Interactive Brokers LLC", "-1000.00", "USD"),
        )
        r.txs.forEach { assertEquals("transfer", it.type); assertEquals("Інвестиції", it.cat) }
        assertEquals(590_000.0 + 1000.0 * (RATES["USD"] ?: 1.0), importTotals(r.txs).invest, 1e-6); assertEquals(0.0, importTotals(r.txs).expense, 0.0)
    }

    @Test fun ovdpRedemptionIsTransferBackCouponIsIncome() {
        val r = parse(
            row("14.10.2026 12:00:00", "Заощадження", card, "Номінал ОВДП", "200000.00"),
            row("14.10.2026 12:01:00", "Заощадження", card, "Купон ОВДП", "16350.00"),
        )
        val (nominal, coupon) = r.txs
        assertEquals("transfer", nominal.type); assertEquals("Інвестиції", nominal.cat)
        assertEquals("income", coupon.type)
    }

    @Test fun incomeCommentsGetSpecialCategories() {
        val r = parse(
            row("30.10.2026 12:00:00", "Зарахування", card, "Зарплата, ВІЙСЬКОВА ЧАСТИНА X1. Коментар: Hroshove zabezpechennia-vynahoroda za znyshch. tekhniky", "172000.00"),
            row("30.10.2026 12:01:00", "Зарахування", card, "Зарплата, ВІЙСЬКОВА ЧАСТИНА X1. Коментар: Hroshove zabezpechennia-za veresen'", "21470.79"),
            row("28.08.2026 12:00:00", "Зарахування", card, "Зарплата, ВІЙСЬКОВА ЧАСТИНА X1. Коментар: Bojovi -DV za serpen'", "5000.00"),
            row("20.08.2026 12:00:00", "Зарахування", card, "Виплата ДВ додатков", "3000.00"),
            row("20.08.2026 12:02:00", "Зарахування", card, "Соціальні виплати", "1553.32"),
        )
        assertEquals(listOf("За знищену техніку", "Грошове забезпечення", "Бойові (ДВ)", "Бойові (ДВ)", "Соціальні виплати"), r.txs.map { it.cat })
        assertTrue(r.txs.all { it.type == "income" })
    }

    @Test fun peerTransfers() {
        val r = parse(
            row("05.11.2026 10:00:00", "Зарахування переказу", card, "Прізвище І.", "2000.00"),
            row("05.11.2026 10:01:00", "Перекази", card, "Прізвище І.", "-404.02"),
            row("05.11.2026 10:02:00", "Перекази", card, "На картку", "-1000.00"),
        )
        assertEquals(listOf("Перекази від людей", "Перекази людям", "Перекази на картки (перевірити)"), r.txs.map { it.cat })
        assertEquals("income", r.txs[0].type); assertEquals("expense", r.txs[1].type)
        assertEquals(2000.0 + 404.02 + 1000.0, importTotals(r.txs).peerTransfers, 1e-6)
    }

    @Test fun incomingTransfersFromPeopleAreNotIncome() {
        val r = parse(row("07.11.2026 10:00:00", "Перекази", card, "Прізвище І.", "500.00"))
        assertEquals("Перекази від людей", r.txs.single().cat)
    }

    @Test fun ownTransfersStayTransfers() {
        val r = parse(row("02.11.2026 01:34:21", "Переказ на свою картку", "5169 **** **** 3171", "На свою картку *7174", "-820.00"))
        assertEquals("transfer", r.txs.single().type); assertEquals("Переказ на свою картку", r.txs.single().cat)
    }

    @Test fun accountsMatchedByCardNotNameAndBalanceFromLatestRow() {
        val r = parse(
            row("06.11.2026 19:58:22", "Зарахування", card, "Соціальні виплати", "1553.32", bal = "503024.11"),
            row("01.11.2026 10:00:00", "Супермаркети та продукти", card, "МАГАЗИН", "-500.00", bal = "501470.79"),
            row("01.11.2026 11:00:00", "Таксі", "4149 **** **** 6543", "Uber", "-200.00", bal = "6906.69"),
        )
        assertEquals(listOf("1983", "6543"), r.accs.map { it.card })
        assertEquals(503024.11, r.accs[0].bal, 1e-9)
        assertEquals("Картка •1983", r.accs[0].name)
    }

    @Test fun foreignCardAmountKeptInCardCurrency() {
        val arch = mapOf("2026-11-02" to mapOf("USD" to 41.5))
        val r = parse(row("02.11.2026 02:00:00", "Розваги", "5169 **** **** 7174", "Онлайн сервіс", "-10.00", cur = "USD"), archive = arch)
        val t = r.txs.single()
        assertEquals(10.0, t.accAmt, 1e-9); assertEquals(415.0, t.amt, 1e-9)           // гривневий еквівалент — курс НБУ на дату
        assertEquals(0, r.approxRates)
    }

    @Test fun noArchiveFallsBackToCurrentRateAndIsCounted() {
        val r = parse(row("02.11.2026 02:00:00", "Розваги", "5169 **** **** 7174", "Онлайн сервіс", "-10.00", cur = "USD"))
        assertEquals(1, r.approxRates)
    }

    @Test fun totalsSeparateKinds() {
        val r = parse(
            row("03.11.2026 10:00:00", "Супермаркети та продукти", card, "МАГАЗИН", "-500.00"),
            row("03.11.2026 10:01:00", "Зарахування", card, "Соціальні виплати", "1000.00"),
            row("03.11.2026 10:02:00", "Перекази", card, "На картку", "-300.00"),
        )
        val t = importTotals(r.txs)
        assertEquals(1000.0, t.income, 1e-9); assertEquals(500.0, t.expense, 1e-9); assertEquals(300.0, t.peerTransfers, 1e-9)
    }

    @Test fun historyParsing() {
        val body = """[{"cc":"USD","rate":41.5,"exchangedate":"02.11.2026"},{"cc":"EUR","rate":48.0,"exchangedate":"02.11.2026"},{"cc":"USD","rate":41.6,"exchangedate":"03.11.2026"}]"""
        assertEquals(mapOf("2026-11-02" to 41.5, "2026-11-03" to 41.6), parseNbuHistory(body, "USD"))
        assertTrue(parseNbuHistory("не json", "USD").isEmpty())
    }
}
