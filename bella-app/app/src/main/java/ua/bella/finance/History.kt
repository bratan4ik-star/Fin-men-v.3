package ua.bella.finance

/** Короткий опис зміни даних для списку «Останні дії» (порівнює стан до й після). */
fun describeChange(a: AppData, b: AppData): String {
    fun cnt(n: Int, m: Int, add: String, del: String, edit: String): String? = when { m > n -> add; m < n -> del; else -> null }
    cnt(a.txs.size, b.txs.size, "Додано операцію", "Видалено операцію", "")?.let { r ->
        if (b.txs.size > a.txs.size) b.txs.firstOrNull { it !in a.txs }?.let { return "$r: ${it.desc}" }
        if (b.txs.size < a.txs.size) a.txs.firstOrNull { it !in b.txs }?.let { return "$r: ${it.desc}" }
        return r
    }
    if (a.txs != b.txs) return b.txs.firstOrNull { it !in a.txs }?.let { "Змінено операцію: ${it.desc}" } ?: "Змінено операції"
    if (a.accs.size != b.accs.size) return if (b.accs.size > a.accs.size) "Додано рахунок: ${b.accs.first { it !in a.accs }.name}" else "Видалено рахунок: ${a.accs.first { it !in b.accs }.name}"
    if (a.pos.size != b.pos.size) return if (b.pos.size > a.pos.size) "Додано позицію: ${b.pos.first { it !in a.pos }.name}" else "Видалено позицію: ${a.pos.first { it !in b.pos }.name}"
    if (a.goals.size != b.goals.size) return if (b.goals.size > a.goals.size) "Додано ціль: ${b.goals.first { it !in a.goals }.name}" else "Видалено ціль: ${a.goals.first { it !in b.goals }.name}"
    if (a.debts.size != b.debts.size) return if (b.debts.size > a.debts.size) "Додано борг: ${b.debts.first { it !in a.debts }.who}" else "Видалено борг: ${a.debts.first { it !in b.debts }.who}"
    if (a.goals != b.goals) return "Зміна цілі: ${b.goals.firstOrNull { it !in a.goals }?.name ?: ""}".trim()
    if (a.debts != b.debts) return "Зміна боргу: ${b.debts.firstOrNull { it !in a.debts }?.who ?: ""}".trim()
    if (a.pos != b.pos) return "Зміна позиції: ${b.pos.firstOrNull { it !in a.pos }?.name ?: ""}".trim()
    if (a.accs != b.accs) return "Зміна рахунку: ${b.accs.firstOrNull { it !in a.accs }?.name ?: ""}".trim()
    if (a.limits != b.limits || a.cats != b.cats) return "Зміна лімітів і категорій"
    return "Зміна даних"
}

/** Стан після відміни останніх k дій: snaps — історія станів (найстаріший → найновіший «до» зміни). */
fun <T> undoSteps(snaps: List<T>, k: Int): Pair<T, List<T>>? {
    if (k < 1 || k > snaps.size) return null
    return snaps[snaps.size - k] to snaps.subList(0, snaps.size - k).toList()
}
