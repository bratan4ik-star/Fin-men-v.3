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

/** Рядок ребалансування: частки у %, diffUah > 0 — докупити, < 0 — зменшити; ok — відхилення менше 1% портфеля. */
data class RebRow(val group: String, val valueUah: Double, val nowPct: Double, val targetPct: Double, val diffUah: Double, val ok: Boolean)

/**
 * Порівнює поточний розподіл інвестицій з цільовим (як у PWA). База — уся вартість інвестицій без готівки.
 * Групи беруться з цілей і з наявних позицій (порядок: цілі, потім інші).
 */
fun rebalance(values: Map<String, Double>, targets: Map<String, Double>): List<RebRow> {
    val total = values.values.sum()
    val groups = (targets.keys + values.filterValues { it > 0 }.keys).distinct()
    return groups.map { g ->
        val v = values[g] ?: 0.0; val tg = targets[g] ?: 0.0
        val diff = tg / 100 * total - v
        RebRow(g, v, if (total > 0) v / total * 100 else 0.0, tg, diff, Math.abs(diff) < total * 0.01)
    }
}
