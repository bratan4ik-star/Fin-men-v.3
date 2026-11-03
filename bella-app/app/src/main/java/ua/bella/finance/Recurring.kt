package ua.bella.finance

import java.time.LocalDate

/**
 * Повторювана операція: щомісяця в день `day` створює операцію-шаблон (сума фіксована).
 * amt — у гривнях, acc/accAmt — рахунок і сума в його валюті («» = без рахунку).
 * next — дата наступного створення; active = false — призупинено.
 */
@kotlinx.serialization.Serializable
data class Recurring(
    val id: String, val desc: String, val cat: String, val type: String,
    val amt: Double, val acc: String = "", val accAmt: Double = 0.0,
    val day: Int, val next: String, val active: Boolean = true,
)

fun newRecId(): String = "rec-" + java.util.UUID.randomUUID().toString().take(8)

/** Дата `day`-го числа місяця ym (для коротких місяців — останній день). */
fun clampDay(ym: java.time.YearMonth, day: Int): LocalDate = ym.atDay(Math.min(day.coerceIn(1, 31), ym.lengthOfMonth()))

/** Наступна дата після `from` (того ж числа наступного місяця). */
fun nextMonthly(from: LocalDate, day: Int): LocalDate = clampDay(java.time.YearMonth.from(from).plusMonths(1), day)

/**
 * Створює всі операції, що настали (next <= today), зокрема пропущені, поки застосунок не відкривали.
 * Повтори не дублюються (ключ rec|id|дата). Рахунок, якого вже немає, ігнорується (операція без рахунку).
 */
fun processRecurring(d: AppData, today: String, rates: Map<String, Double>): AppData {
    if (!isIsoDate(today) || d.recurring.isEmpty()) return d
    val now = LocalDate.parse(today)
    var data = d
    val keys = d.txs.map { it.key }.toMutableSet()
    val recs = d.recurring.map { r ->
        if (!r.active || !isIsoDate(r.next)) return@map r
        var next = LocalDate.parse(r.next); var guard = 0
        while (!next.isAfter(now) && guard < 600) {
            val key = "rec|${r.id}|$next"
            if (key !in keys) {
                val accOk = data.accs.any { it.id == r.acc }
                data = addTx(data, Tx(r.desc, r.cat, r.type, r.amt, next.toString(), key, if (accOk) r.acc else "", if (accOk) r.accAmt else 0.0))
                keys += key
            }
            next = nextMonthly(next, r.day); guard++
        }
        if (next.toString() == r.next) r else r.copy(next = next.toString())
    }
    return if (recs == d.recurring && data === d) d else data.copy(recurring = recs)
}

/** Шаблон з уже створеної операції: перша копія з'явиться наступного місяця. */
fun recurringFrom(t: Tx, day: Int): Recurring = Recurring(newRecId(), t.desc, t.cat, t.type, t.amt, t.acc, t.accAmt, day, nextMonthly(LocalDate.parse(t.date), day).toString())
