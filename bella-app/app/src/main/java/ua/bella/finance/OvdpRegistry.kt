package ua.bella.finance

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/*
 * Довідник ОВДП з відкритих даних НБУ.
 * - Довідник цінних паперів Депозитарію: https://bank.gov.ua/depo_securities?json — масив об'єктів
 *   {cpcode, nominal, auk_proc, pgs_date (РРРР-ММ-ДД), razm_date, pay_period (днів), val_code, cptype, cpdescr,
 *   emit_okpo, emit_name, cptype_nkcpfr, cpcode_cfi, total_bonds, payments:[{pay_date, pay_type ("1" купон,
 *   "2" погашення, "3" дострокове), pay_val (на 1 папір), array}]}. Береться одним запитом.
 * - Результати розміщення ОВДП: https://bank.gov.ua/NBU_ovdp?json[&date=дд.мм.рррр] — аукціони лише за одну дату
 *   (без date — останній день аукціонів); поля AuctionDate, StockCode, ValCode, AvgLevel (середньозважена
 *   дохідність, %), IncomeLevel (граничний рівень). Історії одним запитом немає, тож беруться вівторки
 *   (дні аукціонів) за останній рік, далі — лише нові.
 * Кеш (OvdpRegistry) зберігається в зашифрованому стані застосунку й оновлюється раз на 7 днів або кнопкою.
 */

const val NBU_DEPO_URL = "https://bank.gov.ua/depo_securities?json"
const val NBU_AUCTION_URL = "https://bank.gov.ua/NBU_ovdp?json"
const val REGISTRY_TTL_MS = 7L * 24 * 3600_000
const val AUCTION_WEEKS = 52

/** Виплата за 1 папір: type 1 — купон, 2 — погашення, 3 — дострокове погашення. */
@Serializable data class NbuPay(val d: String, val type: Int, val v: Double)

/** Випуск ОВДП з довідника НБУ. pays — лише майбутні виплати (з дати оновлення). */
@Serializable data class NbuBond(
    val isin: String, val cur: String, val nom: Double, val rate: Double, val mat: String,
    val issue: String = "", val periodDays: Int = 0, val kind: String = "", val pays: List<NbuPay> = emptyList(),
)

/** Останній аукціон випуску: середньозважена дохідність (%) і дата. Це дохідність розміщення, не ринкова ціна. */
@Serializable data class NbuAuction(val isin: String, val date: String, val avg: Double, val cutoff: Double = 0.0, val cur: String = "")

@Serializable data class OvdpRegistry(
    val bonds: List<NbuBond> = emptyList(),
    /** Останній аукціон для кожного ISIN. */
    val auctions: List<NbuAuction> = emptyList(),
    /** Коли довідник успішно оновлено (мс). */
    val updatedAt: Long = 0L,
    /** До якої дати (включно, РРРР-ММ-ДД) аукціони вже переглянуто. */
    val aucScanned: String = "",
)

// ---------- розбір ----------
private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

private fun JsonElement?.str(): String = (this as? JsonPrimitive)?.content?.trim() ?: ""
private fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.content?.trim()?.replace(',', '.')?.toDoubleOrNull()
private fun isoOrEmpty(s: String): String = if (isIsoDate(s)) s else ""
/** дд.мм.рррр → РРРР-ММ-ДД. */
fun dmyToIso(s: String): String = Regex("^(\\d{2})\\.(\\d{2})\\.(\\d{4})$").find(s.trim())?.destructured?.let { (d, m, y) -> isoOrEmpty("$y-$m-$d") } ?: ""

/**
 * Розбирає довідник цінних паперів. Лишаються тільки ОВДП (ISIN UA4000…), не погашені на [today];
 * у графіку — лише виплати з [today] включно. Пошкоджені записи пропускаються, а не валять увесь довідник.
 */
fun parseDepoSecurities(json: String, today: LocalDate): List<NbuBond> {
    val arr = lenient.parseToJsonElement(json) as? JsonArray ?: throw IllegalArgumentException("Довідник НБУ: очікувався масив")
    val t = today.toString()
    return arr.mapNotNull { el ->
        try {
            val o = el.jsonObject
            val isin = o["cpcode"].str().uppercase()
            val mat = isoOrEmpty(o["pgs_date"].str())
            if (!Regex("^UA4000\\d{6}$").matches(isin) || mat.isEmpty() || mat <= t) return@mapNotNull null
            val pays = (o["payments"] as? JsonArray).orEmpty().mapNotNull { p ->
                val po = p as? JsonObject ?: return@mapNotNull null
                val d = isoOrEmpty(po["pay_date"].str()); val ty = po["pay_type"].str().toIntOrNull() ?: 0; val v = po["pay_val"].num() ?: return@mapNotNull null
                if (d.isEmpty() || ty !in 1..3 || d < t) null else NbuPay(d, ty, v)
            }.sortedWith(compareBy({ it.d }, { it.type }))
            NbuBond(isin, o["val_code"].str().uppercase().ifEmpty { "UAH" }, o["nominal"].num() ?: 1000.0, o["auk_proc"].num() ?: 0.0, mat,
                isoOrEmpty(o["razm_date"].str()), o["pay_period"].num()?.toInt() ?: 0, o["cpdescr"].str(), pays)
        } catch (_: Exception) { null }
    }.distinctBy { it.isin }.sortedBy { it.mat }
}

/** Розбирає результати розміщення (масив; порожній, якщо аукціону того дня не було). */
fun parseAuctions(json: String): List<NbuAuction> {
    val arr = lenient.parseToJsonElement(json) as? JsonArray ?: throw IllegalArgumentException("Аукціони НБУ: очікувався масив")
    return arr.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val isin = o["StockCode"].str().uppercase(); val d = dmyToIso(o["AuctionDate"].str())
        val avg = o["AvgLevel"].num()
        if (isin.isEmpty() || d.isEmpty() || avg == null) null else NbuAuction(isin, d, avg, o["IncomeLevel"].num() ?: 0.0, o["ValCode"].str().uppercase())
    }
}

/** Лишає для кожного ISIN найновіший аукціон. */
fun latestAuctions(all: List<NbuAuction>): List<NbuAuction> = all.groupBy { it.isin }.map { (_, l) -> l.maxByOrNull { it.date }!! }.sortedBy { it.isin }

// ---------- оновлення ----------
fun registryDue(r: OvdpRegistry, now: Long): Boolean = r.bonds.isEmpty() || now - r.updatedAt >= REGISTRY_TTL_MS

/** Дні аукціонів (вівторки) для перегляду: після [scanned], а якщо нічого ще не переглянуто — за [weeks] тижнів; до [today]. */
fun auctionDatesToFetch(scanned: String, today: LocalDate, weeks: Int = AUCTION_WEEKS): List<LocalDate> {
    val start = (if (isIsoDate(scanned)) LocalDate.parse(scanned).plusDays(1) else today.minusWeeks(weeks.toLong())).let { if (it.isBefore(today.minusWeeks(weeks.toLong()))) today.minusWeeks(weeks.toLong()) else it }
    var d = start; while (d.dayOfWeek != DayOfWeek.TUESDAY) d = d.plusDays(1)
    val out = mutableListOf<LocalDate>(); while (!d.isAfter(today)) { out += d; d = d.plusWeeks(1) }
    return out
}

private val DMY = DateTimeFormatter.ofPattern("dd.MM.yyyy")

/** Результат першого етапу оновлення: довідник (null — не вдалося, тоді [msg] пояснює) і аукціони останнього дня. */
data class DirectoryStep(val reg: OvdpRegistry?, val msg: String)

/**
 * Етап 1: увесь довідник одним запитом + результати останнього дня аукціонів (ще один запит).
 * Без мережі / з порожньою чи зламаною відповіддю повертає reg = null: кеш лишається як був.
 */
fun refreshDirectory(old: OvdpRegistry, today: LocalDate, now: Long, get: (String) -> String): DirectoryStep {
    val bonds = try { parseDepoSecurities(get(NBU_DEPO_URL), today) } catch (e: Exception) {
        return DirectoryStep(null, "Немає зв'язку з НБУ (${e.message ?: e.javaClass.simpleName}). Показано збережений довідник" + (if (old.updatedAt > 0) "." else ": він порожній."))
    }
    if (bonds.isEmpty()) return DirectoryStep(null, "НБУ повернув порожній довідник. Показано збережений.")
    val latest = try { parseAuctions(get(NBU_AUCTION_URL)) } catch (_: Exception) { emptyList() }
    val live = bonds.map { it.isin }.toHashSet()
    return DirectoryStep(OvdpRegistry(bonds, latestAuctions(old.auctions + latest).filter { it.isin in live }, now, old.aucScanned), "Довідник НБУ оновлено: випусків ${bonds.size}.")
}

/**
 * Етап 2: результати аукціонів за непереглянуті вівторки (історії одним запитом API не дає).
 * Збій на якомусь дні зупиняє перегляд: цей і наступні дні переглянуться при наступному оновленні.
 * Повертає довідник і true, якщо щось не завантажилось.
 */
fun scanAuctions(reg: OvdpRegistry, today: LocalDate, get: (String) -> String): Pair<OvdpRegistry, Boolean> {
    val found = mutableListOf<NbuAuction>(); var scanned = reg.aucScanned; var err = false
    for (d in auctionDatesToFetch(reg.aucScanned, today)) {
        try { found += parseAuctions(get("$NBU_AUCTION_URL&date=${d.format(DMY)}")) } catch (_: Exception) { err = true; break }
        // сьогоднішні результати можуть ще не бути опубліковані — сьогодні не позначаємо переглянутим
        if (d.isBefore(today)) scanned = d.toString()
    }
    val live = reg.bonds.map { it.isin }.toHashSet()
    return reg.copy(auctions = latestAuctions(reg.auctions + found).filter { it.isin in live }, aucScanned = scanned) to err
}

const val AUCTION_PARTIAL = " Частину аукціонів не завантажено — спробуйте пізніше."

/**
 * Повне оновлення (обидва етапи). [get] повертає тіло відповіді або кидає виняток.
 * Без мережі повертає той самий [old] і текст помилки — кеш не втрачається.
 */
fun refreshRegistry(old: OvdpRegistry, today: LocalDate, now: Long, get: (String) -> String): Pair<OvdpRegistry, String> {
    val step = refreshDirectory(old, today, now, get)
    val reg = step.reg ?: return old to step.msg
    val (full, err) = scanAuctions(reg, today, get)
    return full to step.msg + if (err) AUCTION_PARTIAL else ""
}

/** HTTP GET тексту (UTF-8). Лише поза головним потоком. */
fun httpGetText(url: String, timeoutMs: Int = NBU_READ_TIMEOUT_MS): String = nbuGet(url, timeoutMs)

// ---------- вибір і фільтри ----------
/** Строк до погашення: 0 — до 6 міс, 1 — 6–12 міс, 2 — 1–2 роки, 3 — понад 2 роки. */
fun termBucket(mat: String, today: LocalDate): Int {
    val m = LocalDate.parse(mat)
    return when { !m.isAfter(today.plusMonths(6)) -> 0; !m.isAfter(today.plusMonths(12)) -> 1; !m.isAfter(today.plusYears(2)) -> 2; else -> 3 }
}

/** Пошук за ISIN (частина, без регістру), фільтр валюти й строку (null — усі). */
fun filterBonds(bonds: List<NbuBond>, query: String, cur: String?, term: Int?, today: LocalDate): List<NbuBond> {
    val q = query.trim().uppercase().replace(" ", "")
    return bonds.filter { b -> (q.isEmpty() || b.isin.contains(q)) && (cur == null || b.cur == cur) && (term == null || termBucket(b.mat, today) == term) }
}

/** Періодичність купона в місяцях за довідником (0 — без купонів). */
fun bondFreqMonths(b: NbuBond): Int = when {
    b.pays.none { it.type == 1 } && b.periodDays == 0 -> 0
    b.periodDays in 80..100 -> 3
    b.periodDays in 170..200 -> 6
    b.periodDays in 350..380 -> 12
    else -> 6
}

/** Наступна виплата купона (РРРР-ММ-ДД) з графіка, «» — немає. */
fun nextCoupon(b: NbuBond, today: LocalDate): String = b.pays.firstOrNull { it.type == 1 && it.d >= today.toString() }?.d ?: ""

/** ISIN позиції: з даних калькулятора або з назви («ОВДП UA4000…»). */
fun posIsin(p: Pos): String = p.calc?.isin?.trim()?.uppercase()?.takeIf { Regex("^UA4000\\d{6}$").matches(it) }
    ?: Regex("UA4000\\d{6}").find(p.name.uppercase())?.value ?: ""

/** Розбіжність позиції з довідником: поле, у застосунку, у НБУ. */
data class OvdpDiff(val field: String, val mine: String, val nbu: String)

/** Порівнює позицію ОВДП з довідником: погашення, ставка купона, наступна виплата, номінал, валюта. */
fun reconcile(p: Pos, b: NbuBond, today: LocalDate): List<OvdpDiff> {
    val out = mutableListOf<OvdpDiff>()
    if (p.mat != b.mat) out += OvdpDiff("Погашення", p.mat.ifEmpty { "—" }, b.mat)
    if (Math.abs(p.rate - b.rate) > 0.005) out += OvdpDiff("Купон, %", fmt2(p.rate), fmt2(b.rate))
    val nx = nextCoupon(b, today)
    if (nx.isNotEmpty() && p.next.isNotEmpty() && p.next != nx) out += OvdpDiff("Наступна виплата", p.next, nx)
    if (nx.isNotEmpty() && p.next.isEmpty()) out += OvdpDiff("Наступна виплата", "—", nx)
    val nom = p.calc?.nom ?: 1000.0
    if (Math.abs(nom - b.nom) > 0.005) out += OvdpDiff("Номінал", fmt2(nom), fmt2(b.nom))
    if (p.cur != b.cur) out += OvdpDiff("Валюта", p.cur, b.cur)
    return out
}

/**
 * Оновлює позицію даними довідника: ISIN, погашення, ставку, наступну виплату, номінал і періодичність.
 * Валюту не змінює: ціни позиції записані в її валюті, і перерахунок міг би зіпсувати вартість (тоді — вручну).
 */
fun applyNbu(p: Pos, b: NbuBond, today: LocalDate): Pos {
    val c = p.calc ?: CalcExtra(b.isin, b.nom, bondFreqMonths(b), "", "", today.toString(), p.cur)
    return p.copy(mat = b.mat, rate = b.rate, next = nextCoupon(b, today).ifEmpty { p.next },
        calc = c.copy(isin = b.isin, nom = b.nom, freq = bondFreqMonths(b)))
}

/**
 * Виплати позиції за офіційним графіком НБУ у проміжку [from, until]: купон і погашення на дату — однією подією.
 * null — графіка для позиції немає (тоді виплати рахуються, як раніше).
 */
fun officialEvents(p: Pos, b: NbuBond?, from: LocalDate, until: LocalDate): List<OvdpEvent>? {
    if (b == null || b.pays.isEmpty()) return null
    val f = from.toString(); val u = until.toString()
    return b.pays.filter { it.d >= f && it.d <= u }.groupBy { it.d }.map { (d, l) ->
        OvdpEvent(LocalDate.parse(d), p.name, p.qty * l.filter { it.type == 1 }.sumOf { it.v }, p.qty * l.filter { it.type != 1 }.sumOf { it.v }, cur = b.cur)
    }.sortedBy { it.date }
}

/** Індекс довідника за ISIN. */
fun OvdpRegistry.byIsin(): Map<String, NbuBond> = bonds.associateBy { it.isin }
fun OvdpRegistry.auctionOf(isin: String): NbuAuction? = auctions.firstOrNull { it.isin == isin }

/** Випуски з останніх аукціонів (новіші — першими) як швидкий вибір для калькулятора. Порожньо — довідника немає. */
fun registryPresets(r: OvdpRegistry, today: LocalDate, n: Int = 10): List<OvdpPreset> {
    val ix = r.byIsin()
    return r.auctions.sortedByDescending { it.date }.mapNotNull { a -> ix[a.isin]?.let { a to it } }.take(n).map { (a, b) ->
        OvdpPreset("${b.isin} (${fmt2(b.rate)}%)", b.isin, b.cur, (if (b.nom % 1.0 == 0.0) b.nom.toLong().toString() else b.nom.toString()), b.rate.toString(), bondFreqMonths(b), b.mat, b.issue,
            "аукціон ${fmt2(a.avg)}% · ${ddmmyy(a.date)} · ${termLeft(b.mat, today)}")
    }
}
