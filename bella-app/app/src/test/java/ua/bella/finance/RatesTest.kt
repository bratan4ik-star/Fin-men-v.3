package ua.bella.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RatesTest {
    private val body = """[
        {"r030":840,"txt":"Долар США","rate":50.0,"cc":"USD","exchangedate":"02.11.2026"},
        {"r030":978,"txt":"Євро","rate":60.0,"cc":"EUR","exchangedate":"02.11.2026"},
        {"r030":985,"txt":"Злотий","rate":12.0,"cc":"PLN","exchangedate":"02.11.2026"},
        {"r030":826,"txt":"Фунт","rate":70.0,"cc":"GBP","exchangedate":"02.11.2026"}
    ]"""

    @Test fun parsesThreeCurrenciesAndDate() {
        val r = parseNbuRates(body)
        assertNotNull(r)
        assertEquals(mapOf("USD" to 50.0, "EUR" to 60.0, "PLN" to 12.0), r!!.rates)
        assertEquals("02.11.2026", r.date)
    }

    @Test fun incompleteOrBrokenResponseIsRejected() {
        assertNull(parseNbuRates("""[{"cc":"USD","rate":50.0,"exchangedate":"02.11.2026"}]"""))
        assertNull(parseNbuRates("не json"))
        assertNull(parseNbuRates("""{"error":"x"}"""))
    }

    @Test fun convertsThroughHryvnia() {
        val m = mapOf("UAH" to 1.0, "USD" to 50.0, "EUR" to 60.0, "PLN" to 12.0)
        assertEquals(5000.0, convertAmount(100.0, "USD", "UAH", m), 1e-9)
        assertEquals(6000.0, convertAmount(100.0, "EUR", "UAH", m), 1e-9)
        assertEquals(120.0, convertAmount(100.0, "EUR", "USD", m), 1e-9)
        assertEquals(100.0, convertAmount(100.0, "PLN", "PLN", m), 1e-9)
    }
}
