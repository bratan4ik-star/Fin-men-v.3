package ua.bella.finance

import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class SchedRow(val date: LocalDate, val kind: String, val amount: Double, val cum: Double, val paid: Boolean)
data class CalcResult(
    val qty: Int, val inv: Double, val cpn: Double, val disc: Double, val total: Double, val sched: List<SchedRow>,
    val sProfit: Double, val sYield: Double, val sTotal: Double, val hasSell: Boolean, val prog: Int, val days: Long, val ytm: Double,
)

data class CalcInput(
    val isin: String = "UA4000236228", val nom: String = "1000", val buy: String = "2026-11-02", val mat: String = "2027-03-10",
    val price: String = "1029.54", val qty: String = "98", val coupon: String = "17.1", val freq: Int = 6,
    val sell: String = "1050", val sellD: String = "2027-01-10",
    /** Дата початку купона (випуску): купон «в кінці» нараховується від неї; порожньо — від дати купівлі. */
    val issue: String = "",
)

private fun pd(s: String): LocalDate? = try { LocalDate.parse(s.trim()) } catch (e: Exception) { null }

/** Порт calc() з прототипу. Дохідність проста, у річних; "сьогодні" зафіксоване як 2026-11-02, як у макеті. */
fun calcOvdp(s: CalcInput): CalcResult {
    val today = LocalDate.parse(TODAY)
    val nom = parseNum(s.nom).takeIf { it != 0.0 } ?: 1000.0
    val cp = parseNum(s.coupon)
    val pr = parseNum(s.price).takeIf { it != 0.0 } ?: nom
    val qty = Math.max(0, Math.floor(parseNum(s.qty)).toInt())
    val fr = s.freq
    val buy = pd(s.buy) ?: today
    val mat = pd(s.mat) ?: buy
    fun days(a: LocalDate, b: LocalDate) = ChronoUnit.DAYS.between(a, b).toDouble()
    val yrs = Math.max(days(buy, mat) * 86400000 / 31557600000.0, 1 / 365.0)
    val dates = mutableListOf<LocalDate>()
    if (fr > 0) for (k in 1 until 400) { val d = mat.minusMonths((k * fr).toLong()); if (!d.isAfter(buy)) break; dates.add(d) }
    dates.reverse()
    val per = if (fr > 0) qty * nom * cp / 100 * fr / 12 else 0.0
    val sched = mutableListOf<SchedRow>()
    var cum = 0.0
    dates.forEach { d -> cum += per; sched.add(SchedRow(d, "Купон", per, cum, !d.isAfter(today))) }
    // Купон «в кінці»: від дати випуску (або купівлі), actual/360 — як у довідці брокера
    val cStart = pd(s.issue)?.takeIf { it.isBefore(mat) } ?: buy
    val last = if (fr > 0) per else qty * nom * cp / 100 * Math.max(0.0, days(cStart, mat)) / 360.0
    if (mat.isAfter(buy)) { cum += qty * nom + last; sched.add(SchedRow(mat, if (fr > 0) "Купон + погашення" else "Погашення з купоном", qty * nom + last, cum, !mat.isAfter(today))) }
    val cpn = dates.size * per + if (mat.isAfter(buy)) last else 0.0
    val inv = qty * pr
    val disc = qty * nom - inv
    val tot = qty * nom + cpn
    val sp = parseNum(s.sell)
    val sd = pd(s.sellD) ?: mat
    // Продаж у день погашення або пізніше — це погашення: номінал + усі купони (включно з виплатою «в кінці»)
    val atMat = mat.isAfter(buy) && !sd.isBefore(mat)
    val sy = Math.max(days(buy, if (atMat) mat else sd) / 365.25, 1 / 365.0)
    val got = if (atMat) cpn else sched.filter { it.kind == "Купон" && !it.date.isAfter(sd) }.sumOf { it.amount }
    val proceeds = if (atMat) qty * nom else qty * sp
    val sProfit = if (sp > 0) proceeds - inv + got else 0.0
    return CalcResult(
        qty, inv, cpn, disc, tot, sched, sProfit, if (sp > 0 && inv > 0) sProfit / inv / sy * 100 else 0.0, proceeds, sp > 0,
        Math.max(0.0, Math.min(100.0, Math.round(days(buy, today) / Math.max(1.0, days(buy, mat)) * 100).toDouble())).toInt(),
        ChronoUnit.DAYS.between(today, mat), if (inv > 0) (cpn + disc) / inv / yrs * 100 else 0.0,
    )
}
