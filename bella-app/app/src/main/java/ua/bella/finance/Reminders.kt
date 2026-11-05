package ua.bella.finance

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Виплата за ОВДП: купон і/або номінал (при погашенні), суми в гривнях. */
data class OvdpEvent(val date: LocalDate, val name: String, val coupon: Double, val principal: Double) {
    val total get() = coupon + principal
    val maturity get() = principal > 0
}

private fun pdate(s: String): LocalDate? = try { LocalDate.parse(s.trim()) } catch (_: Exception) { null }

/**
 * Майбутні виплати ОВДП у проміжку [from, until].
 * Позиція з калькулятора (є calc) — купони від дати погашення назад з кроком calc.freq місяців (0 — лише погашення).
 * Позиція без calc — купони від «наступної виплати» вперед кожні 6 місяців до погашення.
 * Купон = к-сть × номінал × ставка / 100 × крок / 12; номінал без calc — 1000 ₴.
 */
fun ovdpEvents(pos: List<Pos>, from: LocalDate, until: LocalDate): List<OvdpEvent> {
    val out = mutableListOf<OvdpEvent>()
    pos.filter { it.group == "ОВДП" && it.qty > 0 }.forEach { p ->
        val nom = p.calc?.nom?.takeIf { it > 0 } ?: 1000.0
        val freq = p.calc?.freq ?: 6
        val mat = pdate(p.mat)
        val per = if (freq > 0) p.qty * nom * p.rate / 100 * freq / 12 else 0.0
        val dates = sortedSetOf<LocalDate>()
        if (p.calc != null && mat != null) {
            if (freq > 0) for (k in 0 until 400) { val d = mat.minusMonths((k * freq).toLong()); if (d.isBefore(from)) break; dates.add(d) } else dates.add(mat)
        } else {
            var d = pdate(p.next); var guard = 0
            while (d != null && guard < 400 && !d.isAfter(until) && (mat == null || d.isBefore(mat))) { dates.add(d); d = d.plusMonths(6); guard++ }
            if (mat != null) dates.add(mat)
        }
        dates.filter { !it.isBefore(from) && !it.isAfter(until) }.forEach { d ->
            val atMat = mat != null && d == mat
            out.add(OvdpEvent(d, p.name, if (atMat && freq == 0) 0.0 else per, if (atMat) p.qty * nom else 0.0))
        }
    }
    return out.sortedWith(compareBy({ it.date }, { it.name }))
}

/** Нагадування: за [ahead] днів (і будь-коли ближче, якщо пропущено) — «скоро», у день виплати — «сьогодні». */
data class Reminder(val key: String, val event: OvdpEvent, val today: Boolean, val daysLeft: Long)

fun remindersDue(events: List<OvdpEvent>, today: LocalDate, notified: Set<String>, ahead: Int = 3): List<Reminder> = events.mapNotNull { e ->
    val left = ChronoUnit.DAYS.between(today, e.date)
    val kind = when { left == 0L -> "day"; left in 1..ahead -> "soon"; else -> return@mapNotNull null }
    val key = "$kind|${e.name}|${e.date}"
    if (key in notified) null else Reminder(key, e, left == 0L, left)
}

/** Прибирає з журналу нагадувань записи про виплати, старші за 30 днів. */
fun pruneNotified(keys: Set<String>, today: LocalDate): Set<String> =
    keys.filter { k -> pdate(k.substringAfterLast('|'))?.let { !it.isBefore(today.minusDays(30)) } ?: false }.toSet()

fun reminderTitle(r: Reminder): String {
    val what = if (r.event.maturity) "погашення ОВДП" else "купон ОВДП"
    return if (r.today) "Сьогодні $what" else "Через ${r.daysLeft} ${dayWord(r.daysLeft)} $what"
}

fun reminderText(r: Reminder): String =
    "${r.event.name}: ≈ ${fmtInt(r.event.total)} ₴ · ${ddmmyyyy(r.event.date.toString())}" +
        if (r.event.maturity && r.event.coupon == 0.0) " (номінал, купон окремо)" else ""

fun dayWord(n: Long): String { val a = n % 100; val b = n % 10
    return if (a in 11..14) "днів" else if (b == 1L) "день" else if (b in 2..4) "дні" else "днів" }

// ---------- автокопія ----------
const val AUTO_PREFIX = "bella-auto-"
const val AUTO_KEEP = 8
const val AUTO_PERIOD_MS = 7 * 24 * 3600_000L

fun autoBackupName(date: String) = "$AUTO_PREFIX$date.json"
fun autoBackupDue(lastAt: Long, now: Long) = lastAt <= 0L || now - lastAt >= AUTO_PERIOD_MS - 3600_000L
/** Які автокопії видалити: усі понад [keep] найновіших (за датою в назві). Інші файли в папці не чіпає. */
fun autoBackupsToDelete(names: List<String>, keep: Int = AUTO_KEEP): List<String> =
    names.filter { Regex("""^${AUTO_PREFIX}\d{4}-\d{2}-\d{2}\.json$""").matches(it) }.sortedDescending().drop(keep)

// ---------- дані для графіків ----------
/** Знімки капіталу за останні [days] днів (0 — усі). */
fun histPeriod(h: List<HistPoint>, days: Int, today: String): List<HistPoint> =
    if (days <= 0) h else LocalDate.parse(today).minusDays(days.toLong()).toString().let { from -> h.filter { it.d >= from } }

data class MonthFlow(val month: String, val inc: Double, val exp: Double)
/** Доходи й витрати за [n] місяців, що закінчуються [last] (РРРР-ММ), від старшого до новішого; перекази не враховуються. */
fun monthlyFlows(txs: List<Tx>, last: String, n: Int): List<MonthFlow> {
    val ym = java.time.YearMonth.parse(last)
    return (n - 1 downTo 0).map { k ->
        val m = ym.minusMonths(k.toLong()).toString(); val t = txs.filter { it.date.startsWith(m) }
        MonthFlow(m, t.filter { it.type == "income" }.sumOf { it.amt }, t.filter { it.type == "expense" }.sumOf { it.amt })
    }
}
