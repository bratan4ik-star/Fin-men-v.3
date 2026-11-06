package ua.bella.finance

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * Сповіщення про гроші (крім ОВДП): ліміти витрат і терміни боргів.
 * key закінчується датою (РРРР-ММ-ДД), щоб pruneNotified прибирав старі записи журналу.
 */
data class Alert(val key: String, val title: String, val text: String)

/**
 * Ліміти поточного місяця: 80% і 100%. Кожен поріг — одне сповіщення на категорію за місяць;
 * якщо одразу перейдено 100%, сповіщення про 80% не надсилається окремо (обидва ключі позначаються як надіслані).
 */
fun limitAlerts(txs: List<Tx>, limits: Map<String, Double>, today: LocalDate, notified: Set<String>): List<Alert> {
    val ym = YearMonth.from(today); val cm = ym.toString(); val end = ym.atEndOfMonth()
    val out = mutableListOf<Alert>()
    limits.filterValues { it > 0 }.forEach { (cat, lim) ->
        val spent = txs.filter { it.type == "expense" && it.cat == cat && it.date.startsWith(cm) }.sumOf { it.amt }
        val k80 = "lim|$cat|80|$end"; val k100 = "lim|$cat|100|$end"
        when {
            spent >= lim && k100 !in notified -> out += Alert(k100, "Ліміт «$cat» перевищено",
                "Витрачено ${fmtInt(spent)} ₴ з ${fmtInt(lim)} ₴" + if (spent > lim) " (+${fmtInt(spent - lim)} ₴)" else "")
            spent >= lim * 0.8 && spent < lim && k80 !in notified -> out += Alert(k80, "Ліміт «$cat»: ${Math.floor(spent / lim * 100).toInt()}%",
                "Витрачено ${fmtInt(spent)} ₴ з ${fmtInt(lim)} ₴, лишилось ${fmtInt(lim - spent)} ₴ до кінця місяця")
        }
    }
    return out
}

/** Ключі, які треба позначити надісланими разом зі сповіщенням (100% закриває й 80%). */
fun alertKeys(a: Alert): List<String> = if (a.key.startsWith("lim|") && a.key.contains("|100|")) listOf(a.key, a.key.replace("|100|", "|80|")) else listOf(a.key)

/** Борги з датою: за [ahead] днів («скоро») і в день терміну. Повернені й без дати пропускаються. */
fun debtAlerts(debts: List<Debt>, today: LocalDate, notified: Set<String>, ahead: Int = 3): List<Alert> = debts.mapNotNull { d ->
    if (d.done || !isIsoDate(d.due)) return@mapNotNull null
    val left = ChronoUnit.DAYS.between(today, LocalDate.parse(d.due))
    val kind = when { left == 0L -> "day"; left in 1..ahead -> "soon"; else -> return@mapNotNull null }
    val key = "debt|$kind|${d.who}|${d.due}"
    if (key in notified) return@mapNotNull null
    val whenT = if (left == 0L) "Сьогодні" else "Через $left ${dayWord(left)}"
    val what = if (d.owedToMe) "мають повернути вам борг" else "термін повернення боргу"
    Alert(key, "$whenT $what", "${d.who}: ${fmtInt(d.amt)} ₴ · ${ddmmyyyy(d.due)}")
}
