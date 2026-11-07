package ua.bella.finance

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalDate
import javax.crypto.KeyGenerator

/**
 * Довідник ОВДП НБУ. Фікстури — справжні відповіді API, збережені 07.11.2026:
 * depo_securities_sample.json — 8 записів з https://bank.gov.ua/depo_securities?json (зокрема погашений UA4000190870
 * і не-ОВДП XS2895055981), ovdp_*.json — https://bank.gov.ua/NBU_ovdp?json&date=… за 27.10 і 03.11.2026.
 */
class OvdpRegistryTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource("nbu/$name")!!.readText()
    private fun d(s: String) = LocalDate.parse(s)
    private val today = d("2026-11-07")
    private val depo by lazy { res("depo_securities_sample.json") }
    private val auc1103 by lazy { res("ovdp_2026-11-03.json") }
    private val auc1027 by lazy { res("ovdp_2026-10-27.json") }

    /** «Мережа» з фікстур: довідник, останній день аукціонів (03.11) і окремі дні; решта днів — порожньо. */
    private fun fakeNet(calls: MutableList<String> = mutableListOf()): (String) -> String = { url ->
        calls += url
        when {
            url == NBU_DEPO_URL -> depo
            url == NBU_AUCTION_URL -> auc1103
            url.endsWith("&date=03.11.2026") -> auc1103
            url.endsWith("&date=27.10.2026") -> auc1027
            url.startsWith(NBU_AUCTION_URL) -> "[]"
            else -> throw IOException("unexpected $url")
        }
    }

    // ---------- розбір ----------
    @Test fun parsesSavedDirectoryResponse() {
        val bonds = parseDepoSecurities(depo, today)
        assertEquals(listOf("UA4000236541", "UA4000237077", "UA4000239149", "UA4000239131", "UA4000197685", "UA4000197693"), bonds.map { it.isin })
        val b = bonds.first { it.isin == "UA4000239149" }
        assertEquals("UAH", b.cur); assertEquals(1000.0, b.nom, 0.0); assertEquals(15.1, b.rate, 1e-9); assertEquals("2028-01-19", b.mat)
        assertEquals("2026-11-03", b.issue); assertEquals(182, b.periodDays); assertEquals("Середньострокові", b.kind); assertEquals(6, bondFreqMonths(b))
        assertEquals(listOf(NbuPay("2027-01-20", 1, 75.5), NbuPay("2027-07-21", 1, 75.5), NbuPay("2028-01-19", 1, 75.5), NbuPay("2028-01-19", 2, 1000.0)), b.pays)
        val usd = bonds.first { it.isin == "UA4000236541" }
        assertEquals("USD", usd.cur); assertEquals(4.17, usd.rate, 1e-9); assertEquals(listOf(NbuPay("2027-02-04", 1, 20.85), NbuPay("2027-02-04", 2, 1000.0)), usd.pays)
        assertEquals("EUR", bonds.first { it.isin == "UA4000237077" }.cur)
        // річний купон, інфляційний випуск: суми з графіка НБУ, а не з номінальної ставки
        val infl = bonds.first { it.isin == "UA4000197685" }
        assertEquals(12, bondFreqMonths(infl)); assertEquals(NbuPay("2027-05-10", 1, 103.0), infl.pays.first())
        assertEquals("2026-11-10", nextCoupon(bonds.first { it.isin == "UA4000197693" }, today))
    }

    @Test fun maturedAndNonOvdpAreDroppedAndPastPaymentsTrimmed() {
        val isins = parseDepoSecurities(depo, today).map { it.isin }
        assertFalse("погашений 24.06.2026", "UA4000190870" in isins); assertFalse("не ОВДП", "XS2895055981" in isins)
        // 10.02.2027: доларовий (погашення 04.02.2027) уже погашено, євро ще ні; минулі виплати відкинуто
        val later = parseDepoSecurities(depo, d("2027-02-10"))
        assertFalse("UA4000236541" in later.map { it.isin }); assertTrue("UA4000237077" in later.map { it.isin })
        assertTrue(later.all { b -> b.pays.all { it.d >= "2027-02-10" } })
        assertEquals(listOf("2027-07-21", "2028-01-19", "2028-01-19"), later.first { it.isin == "UA4000239149" }.pays.map { it.d })
        // у день погашення випуск уже не пропонується
        assertFalse("UA4000236541" in parseDepoSecurities(depo, d("2027-02-04")).map { it.isin })
    }

    @Test fun brokenRecordsAreSkippedNotFatal() {
        val json = """[
            {"cpcode":"UA4000000001","nominal":"1000,00","auk_proc":"15.5","pgs_date":"2028-01-01","val_code":"uah","pay_period":"182",
             "payments":[{"pay_date":"2027-01-01","pay_type":"1","pay_val":"77,5"},{"pay_date":"bad","pay_type":"1","pay_val":1},{"pay_date":"2028-01-01","pay_type":"9","pay_val":1}]},
            {"cpcode":"UA4000000002","pgs_date":"не дата"},
            {"cpcode":"UA40000","pgs_date":"2030-01-01"},
            "рядок",
            {"cpcode":"UA4000000003","pgs_date":"2029-01-01","payments":"ні"}
        ]"""
        val b = parseDepoSecurities(json, today)
        assertEquals(listOf("UA4000000001", "UA4000000003"), b.map { it.isin })
        assertEquals(1000.0, b[0].nom, 0.0); assertEquals(15.5, b[0].rate, 0.0); assertEquals("UAH", b[0].cur)
        assertEquals(listOf(NbuPay("2027-01-01", 1, 77.5)), b[0].pays)
        assertTrue(b[1].pays.isEmpty())
        assertTrue(try { parseDepoSecurities("""{"error":1}""", today); false } catch (_: IllegalArgumentException) { true })
    }

    @Test fun parsesAuctionResults() {
        val a = parseAuctions(auc1103)
        assertEquals(listOf(NbuAuction("UA4000239131", "2026-11-03", 16.3, 16.3, "UAH"), NbuAuction("UA4000239149", "2026-11-03", 15.1, 15.1, "UAH")), a.sortedBy { it.isin })
        assertEquals(setOf("UA4000239073", "UA4000239081", "UA4000239115"), parseAuctions(auc1027).map { it.isin }.toSet())
        assertEquals(11.95, parseAuctions(auc1027).first { it.isin == "UA4000239073" }.avg, 1e-9)
        assertTrue(parseAuctions("[]").isEmpty())
        assertEquals("2026-11-03", dmyToIso("03.11.2026")); assertEquals("", dmyToIso("2026-11-03")); assertEquals("", dmyToIso("31.02.2026"))
    }

    // ---------- аукціони ----------
    @Test fun latestAuctionPerIsinWins() {
        val old = NbuAuction("UA4000239149", "2026-10-27", 14.9); val new = NbuAuction("UA4000239149", "2026-11-03", 15.1)
        assertEquals(listOf(new), latestAuctions(listOf(new, old))); assertEquals(listOf(new), latestAuctions(listOf(old, new)))
    }

    @Test fun auctionDaysAreTuesdaysOnly() {
        val first = auctionDatesToFetch("", today)
        assertTrue(first.all { it.dayOfWeek == DayOfWeek.TUESDAY }); assertEquals(52, first.size)
        assertEquals(d("2026-11-03"), first.last()); assertTrue(first.first() >= today.minusWeeks(52))
        // далі — лише нові дні після переглянутого
        assertEquals(listOf(d("2026-11-10")), auctionDatesToFetch("2026-11-03", d("2026-11-12")))
        assertTrue(auctionDatesToFetch("2026-11-03", d("2026-11-09")).isEmpty())
        // давно не оновлювали — не більше року назад
        assertEquals(52, auctionDatesToFetch("2020-01-07", today).size)
    }

    @Test fun refreshMatchesDirectoryWithAuctions() {
        val calls = mutableListOf<String>()
        val (r, msg) = refreshRegistry(OvdpRegistry(), today, 1000L, fakeNet(calls))
        assertEquals("довідник — одним запитом", 1, calls.count { it == NBU_DEPO_URL })
        assertEquals(6, r.bonds.size); assertEquals(1000L, r.updatedAt); assertEquals("2026-11-03", r.aucScanned)
        assertTrue(msg, msg.startsWith("Довідник НБУ оновлено: випусків 6"))
        // аукціони лише для випусків з довідника (UA4000239073 та ін. — не з цієї вибірки)
        assertEquals(listOf("UA4000239131", "UA4000239149"), r.auctions.map { it.isin })
        assertEquals(NbuAuction("UA4000239149", "2026-11-03", 15.1, 15.1, "UAH"), r.auctionOf("UA4000239149"))
        assertNull(r.auctionOf("UA4000236541"))
        // наступного разу — лише нові вівторки, старі результати лишаються
        val calls2 = mutableListOf<String>()
        val (r2, _) = refreshRegistry(r, d("2026-11-12"), 2000L, fakeNet(calls2))
        assertEquals(listOf(NBU_DEPO_URL, NBU_AUCTION_URL, "$NBU_AUCTION_URL&date=10.11.2026"), calls2)
        assertEquals(r.auctions, r2.auctions); assertEquals("2026-11-10", r2.aucScanned)
    }

    @Test fun failedAuctionDayIsRetriedLater() {
        var failOn = "&date=27.10.2026"
        val net: (String) -> String = { url -> if (url.endsWith(failOn)) throw IOException("timeout") else fakeNet()(url) }
        val (r, msg) = refreshRegistry(OvdpRegistry(), today, 1L, net)
        assertEquals(6, r.bonds.size); assertTrue(msg.contains("Частину аукціонів не завантажено"))
        assertEquals("2026-10-20", r.aucScanned)  // зупинились перед днем, що не завантажився
        failOn = "never"
        assertEquals("2026-11-03", refreshRegistry(r, today, 2L, net).first.aucScanned)
    }

    // ---------- кеш без мережі ----------
    @Test fun offlineKeepsCacheAndShowsIt() {
        val (cached, _) = refreshRegistry(OvdpRegistry(), today, 5_000L, fakeNet())
        val offline: (String) -> String = { throw IOException("Unable to resolve host bank.gov.ua") }
        val (r, msg) = refreshRegistry(cached, today, 9_000L, offline)
        assertSame("кеш не змінено", cached, r); assertTrue(msg.contains("Немає зв'язку з НБУ")); assertTrue(msg.contains("Показано збережений довідник."))
        assertTrue(refreshRegistry(OvdpRegistry(), today, 1L, offline).second.contains("він порожній"))
        // відповідь не JSON / порожній довідник — теж лишається кеш
        assertSame(cached, refreshRegistry(cached, today, 9_000L) { "<html>" }.first)
        assertSame(cached, refreshRegistry(cached, today, 9_000L) { "[]" }.first)
        // раз на 7 днів
        assertFalse(registryDue(cached, 5_000L + REGISTRY_TTL_MS - 1)); assertTrue(registryDue(cached, 5_000L + REGISTRY_TTL_MS)); assertTrue(registryDue(OvdpRegistry(), 0L))
    }

    @Test fun cacheLivesInEncryptedStateAndWorksWithoutNetwork() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val (reg, _) = refreshRegistry(OvdpRegistry(), today, 7_000L, fakeNet())
        val pos = Pos("ОВДП", "ОВДП UA4000239149", 10.0, 1000.0, 1000.0, "2028-01-19", 15.1, "2027-01-20")
        val st = Persisted(data = AppData(pos = listOf(pos), demo = false), nbu = reg)
        val k = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey(); val cipher = AesGcmCipher({ k })
        val kv = MemKV()
        assertTrue(StateStore.save(kv, cipher, json.encodeToString(st)))
        assertFalse("довідник у сховищі не відкритим текстом", kv.get(StateStore.ENC)!!.contains("UA4000239149"))
        val back = json.decodeFromString<Persisted>((StateStore.load(kv, cipher, { true }) as StateLoad.Ok).json)
        assertEquals(reg, back.nbu)
        // мережі немає: графік і нагадування — з кешу
        val (still, _) = refreshRegistry(back.nbu, today, 8_000L) { throw IOException("offline") }
        val ev = ovdpEvents(back.data.pos, today, today.plusYears(3), still.byIsin())
        assertEquals(listOf("2027-01-20", "2027-07-21", "2028-01-19"), ev.map { it.date.toString() })
        // стан зі старих версій (без «nbu») читається з порожнім довідником
        assertEquals(OvdpRegistry(), json.decodeFromString<Persisted>("""{"cur":"UAH"}""").nbu)
    }

    // ---------- графік виплат ----------
    @Test fun officialScheduleDrivesEventsWithFallback() {
        val reg = parseDepoSecurities(depo, today).associateBy { it.isin }
        val p = Pos("ОВДП", "ОВДП UA4000239149", 10.0, 1000.0, 1000.0, "2028-01-20", 15.0, "2027-01-15")
        val ev = ovdpEvents(listOf(p), today, today.plusYears(3), reg)
        assertEquals(listOf("2027-01-20", "2027-07-21", "2028-01-19"), ev.map { it.date.toString() })
        assertEquals(755.0, ev[0].coupon, 1e-9); assertEquals(0.0, ev[0].principal, 0.0)
        assertTrue(ev[2].maturity); assertEquals(755.0, ev[2].coupon, 1e-9); assertEquals(10_000.0, ev[2].principal, 0.0)
        // без довідника — як раніше (від «наступної виплати» кожні 6 міс.)
        assertEquals(listOf("2027-01-15", "2027-07-15", "2028-01-15", "2028-01-20"), ovdpEvents(listOf(p), today, today.plusYears(3)).map { it.date.toString() })
        // ISIN не знайдено — теж як раніше
        val other = p.copy(name = "ОВДП UA4000999999")
        assertEquals(ovdpEvents(listOf(other), today, today.plusYears(3)), ovdpEvents(listOf(other), today, today.plusYears(3), reg))
        // валютна: суми в USD, купон і погашення одного дня — однією подією
        val usd = Pos("ОВДП", "Валютна", 3.0, 1000.0, 1000.0, "2027-02-04", 4.17, "", CalcExtra("UA4000236541", 1000.0, 6, "", "", "2026-08-01", "USD"), cur = "USD")
        val e = ovdpEvents(listOf(usd), today, today.plusYears(1), reg).single()
        assertEquals("USD", e.cur); assertEquals(62.55, e.coupon, 1e-9); assertEquals(3000.0, e.principal, 0.0)
        // календар портфеля бере той самий графік
        val cf = buildOvdpPortfolioCashFlow(listOf(p), mapOf("UAH" to 1.0), today, reg)
        assertEquals(10_000.0 + 3 * 755.0, cf.sumOf { it.totalUah }, 1e-6)
    }

    @Test fun isinFromPosition() {
        assertEquals("UA4000239149", posIsin(Pos("ОВДП", "ОВДП ua4000239149 (Моно)", 1.0, 1.0, 1.0)))
        assertEquals("UA4000236541", posIsin(Pos("ОВДП", "Валютна", 1.0, 1.0, 1.0, calc = CalcExtra("UA4000236541", 1000.0, 6, "", "", ""))))
        assertEquals("", posIsin(Pos("ОВДП", "Облігація", 1.0, 1.0, 1.0, calc = CalcExtra("UA1", 1000.0, 6, "", "", ""))))
    }

    // ---------- вибір і звірка ----------
    @Test fun searchAndFilters() {
        val b = parseDepoSecurities(depo, today)
        assertEquals(setOf("UA4000239149", "UA4000239131"), filterBonds(b, " 2391 ", null, null, today).map { it.isin }.toSet())
        assertEquals(listOf("UA4000236541"), filterBonds(b, "", "USD", null, today).map { it.isin })
        assertEquals(listOf("UA4000236541", "UA4000237077"), filterBonds(b, "", null, 0, today).map { it.isin })
        assertEquals(listOf("UA4000239149"), filterBonds(b, "", "UAH", 2, today).map { it.isin })
        assertEquals(3, filterBonds(b, "ua4000", "UAH", 3, today).size)
        assertTrue(filterBonds(b, "", "EUR", 3, today).isEmpty())
        // межі строків
        assertEquals(0, termBucket("2027-05-07", today)); assertEquals(1, termBucket("2027-05-08", today)); assertEquals(1, termBucket("2027-11-07", today))
        assertEquals(2, termBucket("2027-11-08", today)); assertEquals(2, termBucket("2028-11-07", today)); assertEquals(3, termBucket("2028-11-08", today))
    }

    @Test fun reconcileShowsDiffsAndApplyFixesThem() {
        val b = parseDepoSecurities(depo, today).first { it.isin == "UA4000239149" }
        val p = Pos("ОВДП", "ОВДП UA4000239149", 10.0, 980.0, 990.0, "2028-01-20", 15.0, "2027-01-15")
        val diffs = reconcile(p, b, today)
        assertEquals(listOf(OvdpDiff("Погашення", "2028-01-20", "2028-01-19"), OvdpDiff("Купон, %", fmt2(15.0), fmt2(15.1)), OvdpDiff("Наступна виплата", "2027-01-15", "2027-01-20")), diffs)
        val fixed = applyNbu(p, b, today)
        assertTrue(reconcile(fixed, b, today).isEmpty())
        assertEquals(10.0, fixed.qty, 0.0); assertEquals(980.0, fixed.buy, 0.0); assertEquals(990.0, fixed.now, 0.0)  // кількість і ціни не змінюються
        assertEquals("UA4000239149", fixed.calc!!.isin); assertEquals(6, fixed.calc!!.freq)
        // валюта: лише показується, не змінюється
        val usd = parseDepoSecurities(depo, today).first { it.isin == "UA4000236541" }
        val wrongCur = Pos("ОВДП", "ОВДП UA4000236541", 1.0, 40_000.0, 40_000.0, "2027-02-04", 4.17, "2027-02-04")
        assertEquals(listOf(OvdpDiff("Валюта", "UAH", "USD")), reconcile(wrongCur, usd, today))
        assertEquals("UAH", applyNbu(wrongCur, usd, today).cur)
    }

    @Test fun calculatorQuickPicksComeFromRecentAuctions() {
        val (r, _) = refreshRegistry(OvdpRegistry(), today, 1L, fakeNet())
        val pr = registryPresets(r, today)
        assertEquals(setOf("UA4000239131", "UA4000239149"), pr.map { it.isin }.toSet())
        val p = pr.first { it.isin == "UA4000239149" }
        assertEquals("1000", p.nom); assertEquals("15.1", p.coupon); assertEquals(6, p.freq); assertEquals("2028-01-19", p.mat); assertTrue(p.desc.startsWith("аукціон"))
        assertTrue(registryPresets(OvdpRegistry(), today).isEmpty())
        assertNotNull(r.byIsin()["UA4000239131"])
    }
}
