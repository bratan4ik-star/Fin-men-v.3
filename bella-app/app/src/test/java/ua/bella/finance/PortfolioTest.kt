package ua.bella.finance

import org.junit.Assert.assertEquals
import org.junit.Test

class PortfolioTest {
    private val accs = listOf(
        Account("Monobank", "UAH", 10000.0),
        Account("Готівка USD", "USD", 500.0, cash = true),
        Account("Готівка UAH", "UAH", 2000.0, cash = true),
        Account("Сейф EUR", "EUR", 100.0, cash = true, sav = true),
    )

    @Test fun onlyCashAccountsAreCounted() {
        assertEquals(listOf("Готівка USD", "Готівка UAH", "Сейф EUR"), cashAccounts(accs).map { it.name })
    }

    @Test fun cashFollowsRates() {
        val r1 = mapOf("UAH" to 1.0, "USD" to 40.0, "EUR" to 45.0, "PLN" to 10.0)
        val r2 = r1 + ("USD" to 42.0)
        assertEquals(500 * 40.0 + 2000 + 100 * 45.0, cashTotalUah(accs, r1), 1e-9)
        assertEquals(500 * 42.0 + 2000 + 100 * 45.0, cashTotalUah(accs, r2), 1e-9)
    }

    @Test fun shares() {
        assertEquals(25L, sharePct(25.0, 100.0)); assertEquals(0L, sharePct(10.0, 0.0))
    }
}
