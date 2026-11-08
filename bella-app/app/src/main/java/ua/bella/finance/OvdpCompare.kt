package ua.bella.finance

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/*
 * «Порівняти ОВДП»: 2–3 випуски за офіційним графіком виплат НБУ на одну суму.
 * - Ціна — повна (скільки списують за 1 шт разом з накопиченим купоном), у валюті випуску.
 * - Кількість — ціла (залишок лишається грошима, без відсотків); валютні випуски купуються за поточним курсом НБУ.
 * - YTM — XIRR потоків (−вкладено в день купівлі, далі купони й номінал) за днями, рік = 365 днів.
 * - Порівняння строків: усі потоки реінвестуються під припущену ставку r до дати погашення найдовшого (горизонт).
 *   Ставка беззбитковості — r, за якої підсумки коротшого й довшого варіантів на цю дату однакові.
 * - Драбина — сума порівну між вибраними випусками.
 * Валютні суми в гривнях — за поточним курсом НБУ (курс на дати виплат невідомий: валютний ризик).
 */

/** Число для поля вводу: до 2 знаків, без «.0» і пробілів (1012.4, 1000). */
fun fmtPlain(v: Double): String = (Math.round(v * 100) / 100.0).let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }

/** Грошовий потік: дата і сума (додатна — надходження). */
data class Flow(val date: LocalDate, val amount: Double)

/** Частка року між датами (факт./365). */
fun yearFrac(a: LocalDate, b: LocalDate): Double = ChronoUnit.DAYS.between(a, b) / 365.0

/** Чиста приведена вартість потоків на дату першого потоку під ставку r (річна ефективна). */
fun xnpv(r: Double, flows: List<Flow>): Double {
    val t0 = flows.first().date
    return flows.sumOf { it.amount / (1 + r).pow(yearFrac(t0, it.date)) }
}

/**
 * XIRR: ставка, за якої NPV потоків = 0 (як XIRR в Excel). null — розв'язку немає (усі потоки одного знаку).
 * Ньютон, а якщо не зійшовся — поділ навпіл на [−0,99; 100].
 */
fun xirr(flows: List<Flow>, guess: Double = 0.1): Double? {
    if (flows.size < 2 || flows.none { it.amount > 0 } || flows.none { it.amount < 0 }) return null
    val fs = flows.sortedBy { it.date }
    val t0 = fs.first().date
    fun f(r: Double) = fs.sumOf { it.amount / (1 + r).pow(yearFrac(t0, it.date)) }
    fun df(r: Double) = fs.sumOf { val t = yearFrac(t0, it.date); -t * it.amount / (1 + r).pow(t + 1) }
    var r = guess
    repeat(100) {
        val v = f(r); val d = df(r)
        if (abs(v) < 1e-9) return r
        if (d == 0.0 || d.isNaN()) return@repeat
        val n = r - v / d
        if (n <= -0.99 || n.isNaN() || n.isInfinite()) return@repeat
        if (abs(n - r) < 1e-12) return n
        r = n
    }
    var lo = -0.99; var hi = 100.0; var flo = f(lo); val fhi = f(hi)
    if (flo.isNaN() || fhi.isNaN() || flo * fhi > 0) return null
    repeat(300) {
        val m = (lo + hi) / 2; val fm = f(m)
        if (abs(fm) < 1e-10 || hi - lo < 1e-13) return m
        if (flo * fm < 0) hi = m else { lo = m; flo = fm }
    }
    return (lo + hi) / 2
}

/** Повна ціна 1 облігації, за якої дохідність до погашення дорівнює [y] (виплати після дати купівлі). */
fun priceAtYield(b: NbuBond, settle: LocalDate, y: Double): Double =
    b.pays.filter { LocalDate.parse(it.d).isAfter(settle) }.sumOf { it.v / (1 + y).pow(yearFrac(settle, LocalDate.parse(it.d))) }

/** Курс валюти до гривні (UAH = 1). */
private fun fxOf(cur: String, rates: Map<String, Double>) = if (cur == "UAH") 1.0 else rates[cur] ?: 1.0

/** Один випуск у варіанті: скільки куплено, за скільки, які виплати (у валюті випуску, після податку). */
data class CmpLeg(
    val isin: String, val cur: String, val price: Double, val qty: Int,
    /** Вкладено й залишок — у валюті випуску. */
    val invested: Double, val leftover: Double,
    val coupons: Double, val principal: Double, val tax: Double,
    val flows: List<Flow>, val mat: LocalDate,
)

/** Варіант порівняння: один випуск або драбина. Суми в гривнях — за поточним курсом НБУ. */
data class CmpResult(
    val title: String, val legs: List<CmpLeg>,
    /** Валюта варіанту: валюта випуску або «UAH» для драбини з різних валют. */
    val cur: String,
    val amountUah: Double, val investedUah: Double, val leftoverUah: Double,
    /** Усі виплати в гривнях (за поточним курсом), по датах. */
    val flowsUah: List<Flow>,
    /** YTM (XIRR) у валюті варіанту; null — немає виплат після дати купівлі. */
    val ytm: Double?,
    /** Простий річний дохід: дохід / вкладено / роки до повернення. */
    val simple: Double,
    val incomeUah: Double, val per100k: Double, val returnDate: LocalDate, val days: Long,
) {
    val single get() = legs.size == 1
    val fxRisk get() = legs.any { it.cur != "UAH" }
}

/**
 * Купівля [b] на [amountUah] гривень за повною ціною [price] (у валюті випуску) у день [settle]:
 * ціла кількість, виплати після дати купівлі за графіком НБУ. [taxPct] — податок на дохід ОВДП, % (зараз 0 для
 * фізосіб): з купонів і з різниці «номінал − ціна», якщо вона додатна.
 */
fun buyLeg(b: NbuBond, price: Double, amountUah: Double, settle: LocalDate, rates: Map<String, Double>, taxPct: Double = 0.0): CmpLeg {
    val fx = fxOf(b.cur, rates)
    val budget = amountUah / fx
    val qty = if (price > 0) floor(budget / price + 1e-9).toInt().coerceAtLeast(0) else 0
    val invested = qty * price
    val pays = b.pays.filter { LocalDate.parse(it.d).isAfter(settle) }
    val t = taxPct.coerceIn(0.0, 100.0) / 100
    var coupons = 0.0; var principal = 0.0; var tax = 0.0
    val byDate = pays.groupBy { it.d }.toSortedMap().map { (d, l) ->
        val c = l.filter { it.type == 1 }.sumOf { it.v } * qty
        val p = l.filter { it.type != 1 }.sumOf { it.v } * qty
        // дисконт оподатковується при погашенні
        val gain = if (p > 0) Math.max(0.0, p - qty * price) else 0.0
        val tx = (c + gain) * t
        coupons += c; principal += p; tax += tx
        Flow(LocalDate.parse(d), c + p - tx)
    }
    return CmpLeg(b.isin, b.cur, price, qty, invested, budget - invested, coupons, principal, tax, byDate,
        pays.maxOfOrNull { LocalDate.parse(it.d) } ?: LocalDate.parse(b.mat))
}

/** Підсумки варіанту з одного чи кількох випусків (суми в гривнях за поточним курсом). */
fun cmpResult(title: String, legs: List<CmpLeg>, amountUah: Double, settle: LocalDate, rates: Map<String, Double>): CmpResult {
    val curs = legs.map { it.cur }.distinct()
    val cur = curs.singleOrNull() ?: "UAH"
    val k = fxOf(cur, rates)  // у валюті варіанту; для мішаних — у гривнях
    val investedUah = legs.sumOf { it.invested * fxOf(it.cur, rates) }
    val leftoverUah = legs.sumOf { it.leftover * fxOf(it.cur, rates) }
    val flowsUah = legs.flatMap { l -> l.flows.map { Flow(it.date, it.amount * fxOf(l.cur, rates)) } }
        .groupBy { it.date }.toSortedMap().map { (d, l) -> Flow(d, l.sumOf { it.amount }) }
    val ret = flowsUah.lastOrNull()?.date ?: settle
    val inv = investedUah / k
    val ytm = if (inv > 0 && flowsUah.isNotEmpty()) xirr(listOf(Flow(settle, -inv)) + flowsUah.map { Flow(it.date, it.amount / k) }) else null
    val incomeUah = flowsUah.sumOf { it.amount } - investedUah
    val days = ChronoUnit.DAYS.between(settle, ret).coerceAtLeast(0)
    val simple = if (investedUah > 0 && days > 0) incomeUah / investedUah / (days / 365.0) else 0.0
    return CmpResult(title, legs, cur, amountUah, investedUah, leftoverUah, flowsUah, ytm, simple, incomeUah,
        if (investedUah > 0) incomeUah / investedUah * 100_000 else 0.0, ret, days)
}

/** Що порівнюється: випуск і повна ціна 1 шт у валюті випуску. */
data class CmpPick(val bond: NbuBond, val price: Double)

/** Варіанти: кожен випуск окремо (у порядку погашення) і, якщо випусків кілька, драбина (сума порівну). */
fun compareOvdp(picks: List<CmpPick>, amountUah: Double, settle: LocalDate, rates: Map<String, Double>, taxPct: Double = 0.0): List<CmpResult> {
    val ok = picks.filter { it.price > 0 }.sortedBy { it.bond.mat }
    val singles = ok.map { p -> cmpResult(p.bond.isin, listOf(buyLeg(p.bond, p.price, amountUah, settle, rates, taxPct)), amountUah, settle, rates) }
    if (ok.size < 2) return singles
    val part = amountUah / ok.size
    val ladder = cmpResult("Драбина", ok.map { buyLeg(it.bond, it.price, part, settle, rates, taxPct) }, amountUah, settle, rates)
    return singles + ladder
}

/**
 * Підсумок варіанту на дату [horizon] у гривнях: виплати реінвестуються під [r] (річна ефективна) з дати надходження,
 * залишок від округлення кількості лежить без відсотків.
 */
fun valueAt(v: CmpResult, horizon: LocalDate, r: Double): Double =
    v.leftoverUah + v.flowsUah.filter { !it.date.isAfter(horizon) }.sumOf { it.amount * (1 + r).pow(yearFrac(it.date, horizon)) } +
        // виплати після горизонту (якщо горизонт раніший) — за їхньою поточною вартістю під r
        v.flowsUah.filter { it.date.isAfter(horizon) }.sumOf { it.amount / (1 + r).pow(yearFrac(horizon, it.date)) }

/**
 * Ставка беззбитковості: під яку ставку треба реінвестувати гроші коротшого варіанту [short] (і купони довшого)
 * до погашення довшого [long], щоб підсумки зрівнялися. null — за будь-якої ставки від −50% до 200% один із варіантів кращий.
 */
fun breakEvenRate(short: CmpResult, long: CmpResult): Double? {
    val h = long.returnDate
    fun g(r: Double) = valueAt(short, h, r) - valueAt(long, h, r)
    var lo = -0.5; var hi = 2.0; var glo = g(lo); val ghi = g(hi)
    if (glo * ghi > 0) {
        // шукаємо зміну знаку дрібнішим кроком (функція не обов'язково монотонна)
        var prev = lo; var gp = glo
        var x = lo + 0.01
        while (x <= hi) { val gx = g(x); if (gp * gx <= 0) { lo = prev; hi = x; glo = gp; break }; prev = x; gp = gx; x += 0.01 }
        if (glo * g(hi) > 0) return null
    }
    repeat(200) {
        val m = (lo + hi) / 2; val gm = g(m)
        if (abs(gm) < 1e-7 || hi - lo < 1e-12) return m
        if (glo * gm < 0) hi = m else { lo = m; glo = gm }
    }
    return (lo + hi) / 2
}

/** Точки графіка накопиченої вартості (раз на місяць і на дати виплат) до [horizon]: отримане реінвестується під [r],
 *  ще не отримане оцінюється за дохідністю варіанту (на дату купівлі = вкладено + залишок). */
fun valueCurve(v: CmpResult, settle: LocalDate, horizon: LocalDate, r: Double): List<Flow> {
    // ще не отримані виплати — за дохідністю варіанту в гривнях (на дату купівлі крива = вкладено + залишок)
    val y = (if (v.investedUah > 0) xirr(listOf(Flow(settle, -v.investedUah)) + v.flowsUah) else null) ?: r
    val dates = (generateSequence(settle) { it.plusMonths(1) }.takeWhile { !it.isAfter(horizon) }.toList() + v.flowsUah.map { it.date }.filter { !it.isAfter(horizon) } + horizon)
        .distinct().sorted()
    return dates.map { t ->
        val got = v.flowsUah.filter { !it.date.isAfter(t) }.sumOf { it.amount * (1 + r).pow(yearFrac(it.date, t)) }
        val ahead = v.flowsUah.filter { it.date.isAfter(t) }.sumOf { it.amount / (1 + y).pow(yearFrac(t, it.date)) }
        Flow(t, v.leftoverUah + got + ahead)
    }
}

/**
 * Висновок простими словами (без порад) для найкоротшого й найдовшого одиночних варіантів:
 * за якої ставки реінвестування після погашення коротшого вигідніший кожен з них.
 */
fun compareConclusion(results: List<CmpResult>, r: Double): String {
    val singles = results.filter { it.single }
    if (singles.size < 2) return ""
    val s = singles.minByOrNull { it.returnDate }!!; val l = singles.maxByOrNull { it.returnDate }!!
    if (s.returnDate == l.returnDate) {
        val best = singles.maxByOrNull { valueAt(it, l.returnDate, r) }!!
        return "Погашення в один день, тож ставка реінвестування майже не впливає: більший підсумок дає ${best.title}."
    }
    val be = breakEvenRate(s, l)
    val sb = StringBuilder()
    if (be == null) {
        val longWins = valueAt(l, l.returnDate, 0.15) > valueAt(s, l.returnDate, 0.15)
        sb.append(if (longWins) "${l.title} дає більший підсумок на ${ddmmyyyy(l.returnDate.toString())} за будь-якої реальної ставки реінвестування."
            else "${s.title} з реінвестуванням дає більший підсумок на ${ddmmyyyy(l.returnDate.toString())} за будь-якої реальної ставки.")
    } else {
        val longWinsBelow = valueAt(l, l.returnDate, be - 0.01) > valueAt(s, l.returnDate, be - 0.01)
        val pct = fmt2(be * 100)
        if (longWinsBelow) sb.append("Довший ${l.title} вигідніший, якщо після ${ddmmyyyy(s.returnDate.toString())} гроші вдасться реінвестувати лише під ставку, нижчу за $pct% річних; якщо вищу — вигідніший коротший ${s.title}.")
        else sb.append("Коротший ${s.title} вигідніший, якщо після ${ddmmyyyy(s.returnDate.toString())} гроші вдасться реінвестувати під ставку, нижчу за $pct% річних; якщо вищу — вигідніший довший ${l.title}.")
    }
    val ladder = results.firstOrNull { !it.single }
    if (ladder != null) {
        val h = l.returnDate; val vals = singles.map { valueAt(it, h, r) }
        sb.append(" Драбина за ставки ${fmt2(r * 100)}% дає ${fmtInt(valueAt(ladder, h, r))} ₴ — між ${fmtInt(vals.min())} і ${fmtInt(vals.max())} ₴ окремих випусків: менше залежить від того, якими будуть ставки.")
    }
    if (results.any { it.fxRisk }) sb.append(" Валютні суми в гривнях пораховано за поточним курсом НБУ; курс на дати виплат невідомий — це валютний ризик.")
    return sb.toString()
}
