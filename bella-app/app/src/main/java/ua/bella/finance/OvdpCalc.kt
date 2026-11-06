package ua.bella.finance

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

data class SchedRow(
    val date: LocalDate,
    val kind: String,
    val amount: Double,
    val cum: Double,
    val paid: Boolean
)

data class CalcResult(
    val qty: Int,
    val inv: Double,
    val cpn: Double,
    val disc: Double,
    val total: Double,
    val sched: List<SchedRow>,
    val sProfit: Double,
    val sYield: Double,
    val sTotal: Double,
    val hasSell: Boolean,
    val prog: Int,
    val days: Long,
    val ytm: Double,
    val depositEqYield: Double = if (ytm > 0) ytm / 0.77 else 0.0,
    val accruedInterest: Double = 0.0,
    val cleanPrice: Double = 0.0,
    val cur: String = "UAH",
)

data class CalcInput(
    val isin: String = "UA4000236228",
    val nom: String = "1000",
    val buy: String = "2026-11-02",
    val mat: String = "2027-03-10",
    val price: String = "1029.54",
    val qty: String = "98",
    val coupon: String = "17.1",
    val freq: Int = 6,
    val sell: String = "1050",
    val sellD: String = "2027-01-10",
    /** Дата початку купона (випуску): купон «в кінці» нараховується від неї; порожньо — від дати купівлі. */
    val issue: String = "",
    val cur: String = "UAH",
)

data class OvdpPreset(
    val name: String,
    val isin: String,
    val cur: String,
    val nom: String,
    val coupon: String,
    val freq: Int,
    val mat: String,
    val issue: String = "",
    val desc: String = ""
)

val POPULAR_OVDP_PRESETS = listOf(
    OvdpPreset("UA4000236228 (17.1%)", "UA4000236228", "UAH", "1000", "17.1", 6, "2027-03-10", "", "Гривня · піврічний купон"),
    OvdpPreset("Ялта (18.0%)", "UA4000227235", "UAH", "1000", "18.0", 6, "2026-06-24", "2023-06-21", "Військова · піврічний купон"),
    OvdpPreset("Нова Каховка (16.5%)", "UA4000227227", "UAH", "1000", "16.5", 6, "2026-11-04", "2023-11-08", "Військова · піврічний купон"),
    OvdpPreset("Сімферополь (16.0%)", "UA4000228373", "UAH", "1000", "16.0", 12, "2027-01-13", "2024-01-17", "Військова · річний купон"),
    OvdpPreset("Бердянськ USD (4.6%)", "UA4000228381", "USD", "1000", "4.6", 6, "2026-10-22", "2024-04-25", "Військова USD · піврічний купон"),
    OvdpPreset("USD 12 міс (4.5%)", "UA4000229124", "USD", "1000", "4.5", 0, "2026-12-17", "2025-12-18", "Валютна USD · виплата в кінці"),
    OvdpPreset("EUR 12 міс (3.25%)", "UA4000229132", "EUR", "1000", "3.25", 6, "2026-11-19", "2025-11-20", "Валютна EUR · піврічний купон"),
)

fun pd(s: String): LocalDate? = try { LocalDate.parse(s.trim()) } catch (e: Exception) { null }

/** Розрахунок параметрів ОВДП з урахуванням НКД, валюти та еквівалентної ставки депозиту. */
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
    if (fr > 0) for (k in 1 until 400) {
        val d = mat.minusMonths((k * fr).toLong())
        if (!d.isAfter(buy)) break
        dates.add(d)
    }
    dates.reverse()

    val per = if (fr > 0) qty * nom * cp / 100 * fr / 12 else 0.0
    val sched = mutableListOf<SchedRow>()
    var cum = 0.0
    dates.forEach { d ->
        cum += per
        sched.add(SchedRow(d, "Купон", per, cum, !d.isAfter(today)))
    }

    // Купон «в кінці»: від дати випуску (або купівлі), actual/360 — як у довідці брокера
    val cStart = pd(s.issue)?.takeIf { it.isBefore(mat) } ?: buy
    val last = if (fr > 0) per else qty * nom * cp / 100 * Math.max(0.0, days(cStart, mat)) / 360.0
    if (mat.isAfter(buy)) {
        cum += qty * nom + last
        sched.add(SchedRow(mat, if (fr > 0) "Купон + погашення" else "Погашення з купоном", qty * nom + last, cum, !mat.isAfter(today)))
    }

    val cpn = dates.size * per + if (mat.isAfter(buy)) last else 0.0
    val inv = qty * pr
    val disc = qty * nom - inv
    val tot = qty * nom + cpn
    val sp = parseNum(s.sell)
    val sd = pd(s.sellD) ?: mat

    // Розрахунок приблизного НКД на 1 папір
    val lastCpnDate = if (dates.isNotEmpty()) {
        dates.lastOrNull { !it.isAfter(buy) } ?: pd(s.issue) ?: buy
    } else pd(s.issue) ?: buy
    val daysAccrued = Math.max(0.0, days(lastCpnDate, buy))
    val accrued1 = if (cp > 0 && daysAccrued > 0) nom * (cp / 100.0) * (daysAccrued / 365.25) else 0.0
    val clean1 = Math.max(0.0, pr - accrued1)

    // Продаж у день погашення або пізніше — це погашення: номінал + усі купони
    val atMat = mat.isAfter(buy) && !sd.isBefore(mat)
    val sy = Math.max(days(buy, if (atMat) mat else sd) / 365.25, 1 / 365.0)
    val got = if (atMat) cpn else sched.filter { it.kind == "Купон" && !it.date.isAfter(sd) }.sumOf { it.amount }
    val proceeds = if (atMat) qty * nom else qty * sp
    val sProfit = if (sp > 0) proceeds - inv + got else 0.0
    val ytm = if (inv > 0) (cpn + disc) / inv / yrs * 100 else 0.0

    return CalcResult(
        qty = qty,
        inv = inv,
        cpn = cpn,
        disc = disc,
        total = tot,
        sched = sched,
        sProfit = sProfit,
        sYield = if (sp > 0 && inv > 0) sProfit / inv / sy * 100 else 0.0,
        sTotal = proceeds,
        hasSell = sp > 0,
        prog = Math.max(0.0, Math.min(100.0, Math.round(days(buy, today) / Math.max(1.0, days(buy, mat)) * 100).toDouble())).toInt(),
        days = ChronoUnit.DAYS.between(today, mat),
        ytm = ytm,
        depositEqYield = if (ytm > 0) ytm / 0.77 else 0.0,
        accruedInterest = accrued1,
        cleanPrice = clean1,
        cur = s.cur
    )
}

/** Елемент грошового потоку для портфеля ОВДП. */
data class CashFlowItem(
    val date: LocalDate,
    val isinOrName: String,
    val kind: String,
    val amount: Double,
    val cur: String,
    val amountUah: Double,
    val isRedemption: Boolean
)

/** Зведений місячний підсумок виплат ОВДП для графіка. */
data class MonthCashFlow(
    val ym: YearMonth,
    val label: String,
    val couponUah: Double,
    val principalUah: Double,
    val totalUah: Double,
    val items: List<CashFlowItem>
)

/** Зводить усі виплати ОВДП у портфелі по місяцях. */
fun buildOvdpPortfolioCashFlow(
    positions: List<Pos>,
    rates: Map<String, Double> = RATES,
    today: LocalDate = LocalDate.parse(TODAY)
): List<MonthCashFlow> {
    val ovdpPositions = positions.filter { it.group == "ОВДП" && it.qty > 0 }
    if (ovdpPositions.isEmpty()) return emptyList()

    val allEvents = mutableListOf<CashFlowItem>()

    ovdpPositions.forEach { p ->
        val x = p.calc
        val isin = x?.isin ?: p.name
        val nom = x?.nom ?: 1000.0
        val freq = x?.freq ?: 6
        val cur = x?.cur ?: p.cur
        val fx = rates[cur] ?: 1.0
        val buy = pd(x?.buy ?: "") ?: today
        val mat = pd(p.mat) ?: buy

        val cInput = CalcInput(
            isin = isin,
            nom = nom.toString(),
            buy = buy.toString(),
            mat = mat.toString(),
            price = p.buy.toString(),
            qty = p.qty.toInt().toString(),
            coupon = p.rate.toString(),
            freq = freq,
            cur = cur
        )
        val res = calcOvdp(cInput)
        res.sched.forEach { s ->
            if (!s.paid) {
                if (s.kind.contains("погашення", ignoreCase = true)) {
                    val principalPart = res.qty * nom
                    val couponPart = (s.amount - principalPart).coerceAtLeast(0.0)
                    if (principalPart > 0) {
                        allEvents.add(
                            CashFlowItem(
                                date = s.date,
                                isinOrName = p.name,
                                kind = "Погашення номіналу",
                                amount = principalPart,
                                cur = cur,
                                amountUah = principalPart * fx,
                                isRedemption = true
                            )
                        )
                    }
                    if (couponPart > 0) {
                        allEvents.add(
                            CashFlowItem(
                                date = s.date,
                                isinOrName = p.name,
                                kind = "Купон",
                                amount = couponPart,
                                cur = cur,
                                amountUah = couponPart * fx,
                                isRedemption = false
                            )
                        )
                    }
                } else {
                    allEvents.add(
                        CashFlowItem(
                            date = s.date,
                            isinOrName = p.name,
                            kind = s.kind,
                            amount = s.amount,
                            cur = cur,
                            amountUah = s.amount * fx,
                            isRedemption = false
                        )
                    )
                }
            }
        }
    }

    if (allEvents.isEmpty()) return emptyList()

    val grouped = allEvents.groupBy { YearMonth.from(it.date) }.toSortedMap()
    val monthNamesUa = listOf(
        "Січ", "Лют", "Бер", "Кві", "Тра", "Чер",
        "Лип", "Сер", "Вер", "Жов", "Лис", "Гру"
    )

    return grouped.map { (ym, items) ->
        val couponSum = items.filter { !it.isRedemption }.sumOf { it.amountUah }
        val principalSum = items.filter { it.isRedemption }.sumOf { it.amountUah }
        val lbl = "${monthNamesUa[ym.monthValue - 1]} '${ym.year % 100}"
        MonthCashFlow(
            ym = ym,
            label = lbl,
            couponUah = couponSum,
            principalUah = principalSum,
            totalUah = couponSum + principalSum,
            items = items.sortedBy { it.date }
        )
    }
}
