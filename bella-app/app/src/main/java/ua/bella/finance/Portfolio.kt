package ua.bella.finance

/** Готівкові рахунки (позначка «Готівка») показуються в портфелі окремою групою; сума — за курсами до гривні. */
fun cashAccounts(accs: List<Account>): List<Account> = accs.filter { it.cash }

fun cashTotalUah(accs: List<Account>, rates: Map<String, Double>): Double =
    cashAccounts(accs).sumOf { it.bal * (rates[it.cur] ?: 1.0) }

/** Частка у відсотках від загальної суми портфеля (0, якщо портфель порожній). */
fun sharePct(part: Double, total: Double): Long = if (total > 0) Math.round(part / total * 100) else 0

/** Знімок чистого капіталу за день (у гривнях). */
@kotlinx.serialization.Serializable
data class HistPoint(val d: String, val v: Double)

/** Додає або замінює знімок за дату; зберігаються останні max днів, від старших до новіших. */
fun upsertHist(h: List<HistPoint>, date: String, v: Double, max: Int = 400): List<HistPoint> {
    val out = h.filter { it.d != date } + HistPoint(date, v)
    return out.sortedBy { it.d }.takeLast(max)
}

/** Нормалізує значення в 0..1 для графіка (усі однакові — посередині). */
fun normalize(vs: List<Double>): List<Double> {
    if (vs.isEmpty()) return vs
    val mn = vs.min(); val mx = vs.max()
    return if (mx - mn < 1e-9) vs.map { 0.5 } else vs.map { (it - mn) / (mx - mn) }
}
