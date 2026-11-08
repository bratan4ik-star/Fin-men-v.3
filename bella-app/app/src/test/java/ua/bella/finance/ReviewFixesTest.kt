package ua.bella.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewFixesTest {
    // ---- резервна копія ----
    @Test fun backupRoundTripWithoutPin() {
        val p = Persisted(data = AppData(), cur = "USD", light = true, sec = Security(hash = "abc", bio = true), hist = listOf(HistPoint("2026-11-01", 100.0)))
        val text = makeBackup(p, "2026-11-03T14:05:00")
        assertTrue(!text.contains("\"abc\""))
        val b = parseBackup(text)
        assertNotNull(b)
        assertEquals("2026-11-03T14:05:00", b!!.createdAt)
        assertEquals(p.data, b.state.data); assertEquals("USD", b.state.cur); assertEquals(true, b.state.light)
        assertEquals(p.hist, b.state.hist)
        assertEquals("", b.state.sec.hash)
    }

    @Test fun foreignOrBrokenFilesAreRejected() {
        assertNull(parseBackup("""{"app":"other","state":{}}"""))
        assertNull(parseBackup("не json"))
        assertNull(parseBackup(""))
        assertEquals("bella-backup-2026-11-03.json", backupFileName("2026-11-03"))
    }

    @Test fun olderBackupWithoutNewFieldsStillLoads() {
        val b = parseBackup("""{"app":"bella-finance","state":{"data":{"txs":[],"accs":[],"debts":[],"pos":[],"goals":[],"demo":false},"cur":"UAH"}}""")
        assertNotNull(b); assertTrue(b!!.state.data.cats.contains("Інше"))
    }

    // ---- імпорт не стирає рахунки ----
    @Test fun importKeepsManualAccounts() {
        val old = listOf(Account("Моя картка", "UAH", 100.0, id = "c", card = "1234"), Account("Готівка USD", "USD", 500.0, cash = true, id = "u"), Account("Скарбничка", "UAH", 9000.0, sav = true, id = "s"))
        val imported = listOf(Account("Картка •1234", "UAH", 2500.0, card = "1234"), Account("Картка •9999", "UAH", 70.0, card = "9999"))
        // збіг за номером картки; назва й баланс наявного рахунку не змінюються без згоди
        val r = mergeImportedAccs(old, imported)
        assertEquals(listOf("Моя картка", "Готівка USD", "Скарбничка", "Картка •9999"), r.map { it.name })
        assertEquals(100.0, r[0].bal, 1e-9); assertEquals("c", r[0].id)
        assertEquals(2500.0, mergeImportedAccs(old, imported, updateBal = true)[0].bal, 1e-9)
        assertEquals(500.0, r[1].bal, 1e-9); assertTrue(r[1].cash)
        assertTrue(r[2].sav)
    }

    // ---- історія капіталу ----
    @Test fun historyKeepsOnePointPerDay() {
        var h = emptyList<HistPoint>()
        h = upsertHist(h, "2026-11-02", 100.0); h = upsertHist(h, "2026-11-03", 110.0); h = upsertHist(h, "2026-11-03", 120.0)
        assertEquals(listOf(HistPoint("2026-11-02", 100.0), HistPoint("2026-11-03", 120.0)), h)
        assertEquals(3, (1..5).fold(emptyList<HistPoint>()) { a, i -> upsertHist(a, "2026-11-0$i", i.toDouble(), max = 3) }.size)
        assertEquals(listOf(0.0, 0.5, 1.0), normalize(listOf(10.0, 15.0, 20.0)))
        assertEquals(listOf(0.5, 0.5), normalize(listOf(7.0, 7.0)))
    }

    @Test fun currentMonthIsDerivedFromToday() {
        assertEquals(java.time.LocalDate.now().toString(), TODAY)
        assertEquals(TODAY.take(7), CUR_MONTH)
    }

    // ---- ліміти й категорії ----
    @Test fun categoriesAddAndRemove() {
        val (a, e) = addCategory(CATEGORIES, " Діти ")
        assertEquals("", e); assertEquals("Діти", a!!.last())
        assertEquals("Категорія «харчування» вже є", addCategory(CATEGORIES, "харчування").second)
        assertEquals("Вкажіть назву категорії", addCategory(CATEGORIES, "  ").second)
        assertTrue("Інше" in removeCategory(CATEGORIES, "Інше"))
        assertTrue("Розваги" !in removeCategory(CATEGORIES, "Розваги"))
    }

    @Test fun limitsAreParsedInViewCurrency() {
        val (l, e) = buildLimits(listOf("Харчування", "Житло", "Перекази"), mapOf("Харчування" to "300", "Житло" to "", "Перекази" to "5"), 40.0)
        assertEquals("", e)
        assertEquals(linkedMapOf("Харчування" to 12000.0), l)
        assertEquals("Ліміт «Житло»: вкажіть число", buildLimits(listOf("Житло"), mapOf("Житло" to "abc"), 1.0).second)
    }

    @Test fun defaultLimitsSkipEmpty() {
        assertTrue(AppData().limits.values.all { it > 0 })
        assertTrue("Інше" !in AppData().limits)
    }
}
