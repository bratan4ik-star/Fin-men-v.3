package ua.bella.finance

/*
 * Калькулятор складного відсотка й прогноз фінансової незалежності.
 * Формули — як у вебверсії (index.html): ефективна річна ставка → місячна, внески наприкінці місяця
 * з щорічною індексацією, комісії зменшують дохідність, податок береться з прибутку при виході,
 * «сьогоднішні гроші» — з поправкою на інфляцію.
 */

/** Ефективна річна ставка → місячна (без завищення, яке дає r/12). */
fun monthlyRateFromAnnual(annual: Double): Double = if (annual <= -1) -1.0 else Math.pow(1 + annual, 1.0 / 12) - 1

data class Growth(val total: Double, val deposits: Double)

/** Внески наприкінці місяця, щорічно збільшуються на contribGrowth. */
fun simulateGrowth(start: Double, monthly: Double, annualRate: Double, years: Int, contribGrowth: Double = 0.0): Growth {
    val m = monthlyRateFromAnnual(annualRate)
    var total = start; var deposits = start; var pmt = monthly
    for (i in 1..years * 12) {
        total = total * (1 + m) + pmt; deposits += pmt
        if (i % 12 == 0) pmt *= (1 + contribGrowth)
    }
    return Growth(total, deposits)
}

/** Результат калькулятора: усе в тій самій валюті, що й вхідні суми. */
data class CompoundResult(val deposits: Double, val profit: Double, val total: Double, val tax: Double, val net: Double, val real: Double, val realRate: Double, val netRate: Double)

/** Ставки у частках: 0.12 = 12%. */
fun compound(start: Double, monthly: Double, rate: Double, years: Int, inflation: Double, fees: Double, taxRate: Double, contribGrowth: Double): CompoundResult {
    val y = years.coerceIn(1, 50)
    val netRate = (1 + rate) * (1 - fees) - 1
    val g = simulateGrowth(Math.max(0.0, start), Math.max(0.0, monthly), netRate, y, Math.max(0.0, contribGrowth))
    val profit = g.total - g.deposits
    val tax = Math.max(0.0, profit) * taxRate
    val net = g.total - tax
    val real = net / Math.pow(1 + inflation, y.toDouble())
    return CompoundResult(g.deposits, profit, g.total, tax, net, real, (1 + netRate) / (1 + inflation) - 1, netRate)
}

enum class FireStatus { NO_TARGET, NO_SWR, DONE, UNREACHABLE, REACHED, TOO_LONG }

/** Прогноз фінансової незалежності в сьогоднішніх грошах. */
data class FireResult(
    val status: FireStatus, val realRate: Double, val incomeNow: Double, val capitalNeeded: Double,
    val months: Int = 0, val targetNominal: Double = 0.0,
)

/**
 * capital — стартовий капітал, monthly — внесок (індексується на інфляцію), yieldRate — номінальна дохідність,
 * swr — безпечна ставка знімання на рік, target — бажаний дохід на місяць (у сьогоднішніх грошах).
 */
fun fireProjection(capital: Double, monthly: Double, yieldRate: Double, inflation: Double, swr: Double, target: Double): FireResult {
    val realRate = (1 + yieldRate) / (1 + inflation) - 1
    val mReal = monthlyRateFromAnnual(realRate)
    val incomeNow = capital * swr / 12
    val need = if (target > 0 && swr > 0) target * 12 / swr else 0.0
    if (target <= 0) return FireResult(FireStatus.NO_TARGET, realRate, incomeNow, need)
    if (swr <= 0) return FireResult(FireStatus.NO_SWR, realRate, incomeNow, need)
    if (incomeNow >= target) return FireResult(FireStatus.DONE, realRate, incomeNow, need, 0, target)
    if (realRate <= 0 && monthly <= 0) return FireResult(FireStatus.UNREACHABLE, realRate, incomeNow, need)
    var c = capital; var months = 0
    while (months < 600) {
        months++; c = c * (1 + mReal) + monthly
        if (c * swr / 12 >= target) return FireResult(FireStatus.REACHED, realRate, incomeNow, need, months, target * Math.pow(1 + inflation, months / 12.0))
    }
    return FireResult(FireStatus.TOO_LONG, realRate, incomeNow, need, months)
}

/** «3 р. 4 міс.» */
fun fmtYearsMonths(m: Int): String { val y = m / 12; val r = m % 12; return if (y > 0) "$y р. $r міс." else "$r міс." }
