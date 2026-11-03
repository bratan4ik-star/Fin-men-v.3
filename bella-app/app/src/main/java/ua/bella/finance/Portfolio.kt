package ua.bella.finance

/** Готівкові рахунки (позначка «Готівка») показуються в портфелі окремою групою; сума — за курсами до гривні. */
fun cashAccounts(accs: List<Account>): List<Account> = accs.filter { it.cash }

fun cashTotalUah(accs: List<Account>, rates: Map<String, Double>): Double =
    cashAccounts(accs).sumOf { it.bal * (rates[it.cur] ?: 1.0) }

/** Частка у відсотках від загальної суми портфеля (0, якщо портфель порожній). */
fun sharePct(part: Double, total: Double): Long = if (total > 0) Math.round(part / total * 100) else 0
