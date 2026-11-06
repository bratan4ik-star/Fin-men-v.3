package ua.bella.finance

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/*
 * Графіки рахунку IBKR. Історія вартості рахунку (у базовій валюті, зазвичай USD) береться з розділу
 * «Net Asset Value (NAV) in Base» Flex-звіту, а якщо його немає — з власного знімка при кожній синхронізації.
 * Поповнення й виведення — з Cash Transactions (Deposits/Withdrawals). Ціни ETF за 5 років — з Yahoo Finance.
 */

/** Поповнення (+) чи виведення (−) у базовій валюті рахунку. id — transactionID IBKR, щоб не дублювати. */
@Serializable data class IbFlow(val id: String, val d: String, val amt: Double)

/** Періоди перемикача: код → підпис. */
val IB_PERIODS = listOf("7D" to "7Д", "1M" to "1М", "3M" to "3М", "6M" to "6М", "1Y" to "1Р", "3Y" to "3Р", "5Y" to "5Р")

/** Перший день вікна: стільки назад від останнього дня з даними. */
fun periodStart(last: String, period: String): String {
    val d = LocalDate.parse(last)
    return when (period) {
        "7D" -> d.minusDays(7); "1M" -> d.minusMonths(1); "3M" -> d.minusMonths(3); "6M" -> d.minusMonths(6)
        "1Y" -> d.minusYears(1); "3Y" -> d.minusYears(3); else -> d.minusYears(5)
    }.toString()
}

/** Чи для періоду брати тижневі ціни (довгі періоди) замість денних. */
fun weeklyPeriod(period: String) = period == "3Y" || period == "5Y"

/**
 * Об'єднує історію рахунку з новим Flex-звітом. Дні з NAV-розділу звіту замінюють наявні; за відсутності
 * NAV за сьогодні додається знімок snap (вартість, яку порахував застосунок). Поповнення в межах періоду звіту
 * беруться зі звіту (старі за ці дні замінюються), поза ним лишаються. Зберігається не більше ~5 років.
 */
fun mergeIbHist(nav: List<HistPoint>, flows: List<IbFlow>, f: FlexData, snap: Double?, today: String): Pair<List<HistPoint>, List<IbFlow>> {
    val m = java.util.TreeMap<String, Double>(); nav.forEach { m[it.d] = it.v }
    f.nav.forEach { if (isIsoDate(it.d) && it.v.isFinite()) m[it.d] = it.v }
    if (snap != null && snap > 0 && f.nav.none { it.d == today } && isIsoDate(today)) m[today] = snap
    val from = f.fromDate.takeIf { isIsoDate(it) }
    val keep = if (from == null) flows.filter { o -> f.flows.none { it.id == o.id } } else flows.filter { it.d < from && f.flows.none { n -> n.id == it.id } }
    val fl = (keep + f.flows.filter { isIsoDate(it.date) }.map { IbFlow(it.id, it.date, it.amount) }).sortedBy { it.d }
    val cut = if (m.isEmpty()) "" else LocalDate.parse(m.lastKey()).minusYears(5).minusDays(7).toString()
    return m.filterKeys { it >= cut }.map { HistPoint(it.key, it.value) } to fl.filter { it.d >= cut }
}

/** Точка вікна: вартість, скільки внесено від початку вікна (з вартістю на початку) і накопичена дохідність TWR. */
data class IbPt(val d: String, val nav: Double, val invested: Double, val twr: Double, val flow: Double)

/** Вікно періоду: точки й підсумки. capped — історія коротша за період, показано всю. */
data class IbWin(val pts: List<IbPt>, val capped: Boolean) {
    val start get() = pts.firstOrNull()?.nav ?: 0.0
    val value get() = pts.lastOrNull()?.nav ?: 0.0
    val deposits get() = pts.drop(1).sumOf { it.flow }
    val change get() = value - start
    val gain get() = change - deposits
    val twr get() = pts.lastOrNull()?.twr ?: 0.0
}

/**
 * Будує вікно: від останнього дня ≤ початку періоду (або від першого дня, якщо історія коротша) до кінця.
 * Поповнення між двома днями відносяться до пізнішого. Денна дохідність: (вартість − поповнення) / вартість учора − 1.
 */
fun ibWindow(nav: List<HistPoint>, flows: List<IbFlow>, period: String): IbWin {
    val h = nav.sortedBy { it.d }; if (h.isEmpty()) return IbWin(emptyList(), false)
    val from = periodStart(h.last().d, period)
    val bi = h.indexOfLast { it.d <= from }; val capped = bi < 0
    val w = h.subList(if (capped) 0 else bi, h.size)
    val out = ArrayList<IbPt>(w.size); var inv = w[0].v; var g = 1.0
    out += IbPt(w[0].d, w[0].v, inv, 0.0, 0.0)
    for (k in 1 until w.size) {
        val prev = w[k - 1]; val cur = w[k]
        val fl = flows.filter { it.d > prev.d && it.d <= cur.d }.sumOf { it.amt }
        inv += fl
        if (prev.v > 0) g *= (cur.v - fl) / prev.v
        out += IbPt(cur.d, cur.v, inv, g - 1, fl)
    }
    return IbWin(out, capped)
}

/** Вартість рахунку IBKR за даними застосунку (позиції IBKR + рахунки «IBKR …») у валюті base. */
fun ibkrSnapshot(d: AppData, rates: Map<String, Double>, base: String): Double? {
    val r = rates[base] ?: return null
    val pos = d.pos.filter { it.src == "ibkr" }; val accs = d.accs.filter { it.id.startsWith("ibkr-") }
    if (pos.isEmpty() && accs.isEmpty()) return null
    return (pos.sumOf { posValueUah(it, rates) } + accs.sumOf { it.bal * (rates[it.cur] ?: 0.0) }) / r
}

/** Частка позиції чи готівки в рахунку IBKR, у базовій валюті. */
data class IbSlice(val name: String, val value: Double, val cash: Boolean)

fun ibkrAlloc(d: AppData, rates: Map<String, Double>, base: String): List<IbSlice> {
    val r = rates[base] ?: return emptyList()
    val pos = d.pos.filter { it.src == "ibkr" }.map { IbSlice(it.name, posValueUah(it, rates) / r, false) }
    val cash = d.accs.filter { it.id.startsWith("ibkr-") }.sumOf { it.bal * (rates[it.cur] ?: 0.0) } / r
    return (pos + if (cash > 0.005) listOf(IbSlice("Готівка", cash, true)) else emptyList()).filter { it.value > 0 }.sortedByDescending { it.value }
}

/** Нереалізований прибуток позиції у валюті фонду: вартість зараз мінус купівля за середньою ціною. */
data class IbPnl(val name: String, val cur: String, val value: Double, val cost: Double) {
    val pnl get() = value - cost
    val pct get() = if (cost > 0) pnl / cost else 0.0
}

fun ibkrPnl(d: AppData): List<IbPnl> = d.pos.filter { it.src == "ibkr" && isFxPos(it) }
    .map { IbPnl(it.name, it.cur, it.qty * it.pxNow, it.qty * it.pxBuy) }.sortedBy { it.pnl }

/** Розбирає історію Yahoo Finance chart API: дата закриття → ціна (дні без ціни пропускаються). */
fun parseYahooHistory(body: String): List<HistPoint> = try {
    val r = Json.parseToJsonElement(body).jsonObject["chart"]!!.jsonObject["result"]!!.jsonArray[0].jsonObject
    val ts = r["timestamp"]!!.jsonArray.map { it.jsonPrimitive.longOrNull }
    val close = r["indicators"]!!.jsonObject["quote"]!!.jsonArray[0].jsonObject["close"]!!.jsonArray.map { it.jsonPrimitive.doubleOrNull }
    val m = java.util.TreeMap<String, Double>()
    ts.forEachIndexed { i, t -> val c = close.getOrNull(i); if (t != null && c != null && c > 0) m[Instant.ofEpochSecond(t).atOffset(ZoneOffset.UTC).toLocalDate().toString()] = c }
    m.map { HistPoint(it.key, it.value) }
} catch (_: Exception) { emptyList() }

/** Історія цін з Yahoo: weekly — тижнева за 5 років, інакше денна за рік. Лише поза головним потоком. */
fun fetchHistory(ticker: String, weekly: Boolean): List<HistPoint> {
    val t = ticker.trim().uppercase(); if (t.isEmpty()) return emptyList()
    val q = if (weekly) "range=5y&interval=1wk" else "range=1y&interval=1d"
    val c = URL("https://query1.finance.yahoo.com/v8/finance/chart/" + URLEncoder.encode(t, "UTF-8") + "?" + q).openConnection() as HttpURLConnection
    return try {
        c.connectTimeout = 8000; c.readTimeout = 12000
        c.setRequestProperty("Accept", "application/json"); c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) BellaFinance")
        if (c.responseCode != HttpURLConnection.HTTP_OK) emptyList() else parseYahooHistory(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
    } finally { c.disconnect() }
}

/** Зміна ціни від початку періоду (частка): від останньої ціни ≤ початку періоду або від першої. */
fun priceWindow(h: List<HistPoint>, period: String): List<HistPoint> {
    if (h.isEmpty()) return emptyList()
    val from = periodStart(h.last().d, period)
    val i = h.indexOfLast { it.d <= from }.coerceAtLeast(0)
    val base = h[i].v
    return h.subList(i, h.size).map { HistPoint(it.d, it.v / base - 1) }
}
