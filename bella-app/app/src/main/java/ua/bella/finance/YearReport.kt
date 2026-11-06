package ua.bella.finance

import java.time.LocalDate
import java.time.YearMonth

/** Період звіту [from, to] (дати включно) і такий самий період роком раніше для порівняння. */
data class Period(val label: String, val from: LocalDate, val to: LocalDate, val prevFrom: LocalDate, val prevTo: LocalDate)

/** Календарний рік; для поточного — з 1 січня до сьогодні, порівняння — з тими самими днями торік. */
fun yearPeriod(year: Int, today: LocalDate): Period {
    val from = LocalDate.of(year, 1, 1)
    val to = if (year == today.year) today else LocalDate.of(year, 12, 31)
    return Period(if (year == today.year) "$year (з початку року)" else "$year", from, to, from.minusYears(1), to.minusYears(1))
}

/** Останні 12 місяців до сьогодні включно; порівняння — з попередніми 12 місяцями. */
fun last12Period(today: LocalDate): Period {
    val from = today.minusYears(1).plusDays(1)
    return Period("${ddmmyyyy(from.toString())} – ${ddmmyyyy(today.toString())}", from, today, from.minusYears(1), today.minusYears(1))
}

/** Вид інвестиційного доходу за описом і категорією доходу: ОВДП, Депозити, Дивіденди; null — не інвестиційний. */
fun investKind(t: Tx): String? {
    if (t.type != "income") return null
    val s = (t.desc + " " + t.cat).lowercase()
    return when {
        s.contains("купон") || s.contains("овдп") -> "ОВДП"
        s.contains("дивіденд") -> "Дивіденди"
        s.contains("відсот") || s.contains("депозит") || (t.cat == "Заощадження" && s.contains("%")) -> "Депозити"
        else -> null
    }
}

data class YearReport(
    val period: Period, val months: Int,
    val income: Double, val expense: Double, val prevIncome: Double, val prevExpense: Double,
    val flows: List<MonthFlow>,
    val cats: List<Pair<String, Double>>,
    /** Місяці з найбільшими й найменшими витратами (серед тих, де витрати були). */
    val maxMonth: MonthFlow?, val minMonth: MonthFlow?,
    /** Капітал зі щоденних знімків: найближчий до початку і до кінця періоду всередині нього. */
    val netStart: HistPoint?, val netEnd: HistPoint?,
    /** Інвестиційний дохід за видами (суми, що надійшли). Відсотки депозитів у застосунку — уже після податку. */
    val invest: Map<String, Double>,
) {
    val net get() = income - expense
    val savingsPct get() = if (income > 0) Math.round(net / income * 100).toInt() else null
    val avgIncome get() = income / months
    val avgExpense get() = expense / months
    val investTotal get() = invest.values.sum()
    /** Утриманий податок з відсотків депозитів (23%), оцінка за сумою після податку. */
    val depositTax get() = (invest["Депозити"] ?: 0.0) / (1 - DEPOSIT_TAX) * DEPOSIT_TAX
    val netChange get() = if (netStart != null && netEnd != null && netStart.d != netEnd.d) netEnd.v - netStart.v else null
}

fun yearReport(txs: List<Tx>, hist: List<HistPoint>, p: Period): YearReport {
    val f = p.from.toString(); val t = p.to.toString(); val pf = p.prevFrom.toString(); val pt = p.prevTo.toString()
    val cur = txs.filter { it.date in f..t }; val prev = txs.filter { it.date in pf..pt }
    fun inc(l: List<Tx>) = l.filter { it.type == "income" }.sumOf { it.amt }
    fun exp(l: List<Tx>) = l.filter { it.type == "expense" }.sumOf { it.amt }
    val first = YearMonth.from(p.from); val last = YearMonth.from(p.to)
    val flows = generateSequence(first) { it.plusMonths(1) }.takeWhile { !it.isAfter(last) }.map { ym ->
        val l = cur.filter { it.date.startsWith(ym.toString()) }; MonthFlow(ym.toString(), inc(l), exp(l))
    }.toList()
    val withExp = flows.filter { it.exp > 0 }
    val cats = cur.filter { it.type == "expense" }.groupBy { it.cat }.map { (c, l) -> c to l.sumOf { it.amt } }.sortedByDescending { it.second }
    val h = hist.filter { it.d in f..t }.sortedBy { it.d }
    val invest = cur.mapNotNull { x -> investKind(x)?.let { it to x.amt } }.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
    return YearReport(p, flows.size, inc(cur), exp(cur), inc(prev), exp(prev), flows, cats,
        withExp.maxByOrNull { it.exp }, if (withExp.size > 1) withExp.minByOrNull { it.exp } else null,
        h.firstOrNull(), h.lastOrNull(), invest)
}

/** Роки, за які є операції, плюс поточний (від новішого). */
fun reportYears(txs: List<Tx>, today: LocalDate): List<Int> =
    (txs.mapNotNull { it.date.take(4).toIntOrNull() } + today.year).distinct().sortedDescending()
