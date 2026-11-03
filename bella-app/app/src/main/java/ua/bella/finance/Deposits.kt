package ua.bella.finance

import java.time.LocalDate

/** Податок на відсотки депозиту в Україні: 18% ПДФО + 5% військовий збір. */
const val DEPOSIT_TAX = 0.23

/** Відсотки депозиту за місяць після податку, у гривнях. */
fun depositMonthlyNet(p: Pos): Double = p.qty * p.now * p.rate / 100 / 12 * (1 - DEPOSIT_TAX)

/**
 * Щомісячні виплати депозитів: для кожної позиції з monthly = true і датою next <= today
 * створює операцію доходу «Відсотки: …» (на рахунок payAcc, якщо він є) і переносить next на місяць уперед.
 * Пропущені місяці дораховуються. Виплати після дати повернення (mat) не створюються.
 * Повтори не дублюються: операція має ключ dep|назва|дата.
 */
fun processDepositPayouts(d: AppData, today: String, rates: Map<String, Double>): AppData {
    if (!isIsoDate(today)) return d
    val now = LocalDate.parse(today)
    var data = d
    val keys = d.txs.map { it.key }.toMutableSet()
    val pos = d.pos.map { p ->
        if (!p.monthly || p.group != "Депозити" || !isIsoDate(p.next)) return@map p
        val end = if (isIsoDate(p.mat)) LocalDate.parse(p.mat) else null
        val amtUah = depositMonthlyNet(p)
        var next = LocalDate.parse(p.next); var guard = 0
        while (!next.isAfter(now) && (end == null || !next.isAfter(end)) && guard < 600) {
            val date = next.toString(); val key = "dep|${p.name}|$date"
            if (amtUah > 0 && key !in keys) {
                val acc = data.accs.firstOrNull { it.id == p.payAcc }
                val accAmt = if (acc != null) amtUah / (rates[acc.cur] ?: 1.0) else 0.0
                data = addTx(data, Tx("Відсотки: ${p.name}", "Надходження", "income", amtUah, date, key, acc?.id ?: "", accAmt))
                keys += key
            }
            next = next.plusMonths(1); guard++
        }
        val nextStr = if (end != null && next.isAfter(end)) "" else next.toString()
        if (nextStr == p.next) p else p.copy(next = nextStr)
    }
    return if (pos == d.pos && data === d) d else data.copy(pos = pos)
}
