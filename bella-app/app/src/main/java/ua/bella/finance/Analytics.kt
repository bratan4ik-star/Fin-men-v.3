package ua.bella.finance

import java.time.YearMonth
import java.util.Locale

/** Порада у блоці «Розумна аналітика». ok = true — показник у нормі. */
data class Insight(val ok: Boolean, val title: String, val text: String)

/**
 * Результат аналітики. Усі суми в гривнях (як Tx.amt); у валюту перегляду їх переводить екран.
 * runwayMonths = null, якщо немає даних про витрати.
 */
data class SmartResult(
    val runwayMonths: Double?,
    val projectedUah: Double,
    val burnPerDayUah: Double,
    val insights: List<Insight>,
    val top: List<Tx>,
)

fun fmtMonths(v: Double): String = if (v >= 99) "99+" else String.format(Locale.US, "%.1f", v).replace('.', ',')

/**
 * Запас міцності, прогноз витрат поточного місяця, поради й п'ять найбільших витрат.
 * @param allTxs усі операції (для середніх витрат і поточного місяця)
 * @param periodTxs операції вибраного періоду (для концентрації витрат і топ-5)
 * @param cashUah готівка на картках і в готівці в гривнях
 * @param today поточна дата РРРР-ММ-ДД
 */
fun smartAnalytics(allTxs: List<Tx>, periodTxs: List<Tx>, cashUah: Double, today: String): SmartResult {
    val cm = today.take(7)
    val day = (if (today.length >= 10) today.substring(8, 10).toIntOrNull() else null)?.coerceAtLeast(1) ?: 1
    val dim = try { YearMonth.parse(cm).lengthOfMonth() } catch (_: Exception) { 30 }

    val cmTxs = allTxs.filter { it.date.startsWith(cm) }
    val monthIncome = cmTxs.filter { it.type == "income" }.sumOf { it.amt }
    val monthExpense = cmTxs.filter { it.type == "expense" }.sumOf { it.amt }
    val burn = monthExpense / day
    val projected = burn * dim

    val perMonth = allTxs.filter { it.type == "expense" }.groupBy { it.date.take(7) }.mapValues { e -> e.value.sumOf { it.amt } }
    val avg = if (perMonth.isEmpty()) 0.0 else perMonth.values.average()
    val base = if (avg > 0) avg else projected
    val runway = if (base > 0) Math.max(0.0, cashUah) / base else null

    val expenses = periodTxs.filter { it.type == "expense" }
    val periodExpense = expenses.sumOf { it.amt }
    val topCat = expenses.groupBy { it.cat }.mapValues { e -> e.value.sumOf { it.amt } }.entries.maxByOrNull { it.value }
    val topPct = if (topCat != null && periodExpense > 0) Math.round(topCat.value / periodExpense * 100).toInt() else 0
    val savings = if (monthIncome > 0) Math.round((monthIncome - monthExpense) / monthIncome * 100).toInt() else null

    val insights = mutableListOf<Insight>()
    if (savings != null) {
        insights += when {
            savings >= 20 -> Insight(true, "Норма заощаджень $savings%", "Це не менше рекомендованих 20% доходу (правило 50/30/20).")
            savings >= 0 -> Insight(false, "Норма заощаджень $savings%", "Правило 50/30/20 радить відкладати щонайменше 20% доходу.")
            else -> Insight(false, "Витрати перевищують доходи", "Цього місяця витрачено більше, ніж зароблено. Перегляньте необов’язкові категорії.")
        }
    }
    if (topCat != null) {
        insights += if (topPct > 40) Insight(false, "Висока концентрація витрат", "«${topCat.key}» — $topPct% усіх витрат за вибраний період.")
        else Insight(true, "Збалансовані витрати", "Найбільша категорія «${topCat.key}» — $topPct% витрат.")
    }
    if (runway != null) {
        val rt = fmtMonths(runway)
        insights += if (runway >= 6) Insight(true, "Фінансова подушка", "Готівки вистачить приблизно на $rt міс. витрат. Рекомендовано від 6.")
        else Insight(false, "Фінансова подушка", "Готівки вистачить приблизно на $rt міс. витрат. Рекомендовано мати запас від 6 міс.")
    }

    return SmartResult(runway, projected, burn, insights, expenses.sortedByDescending { it.amt }.take(5))
}

/**
 * «Вільно до кінця місяця» (як у PWA). Усі суми в гривнях.
 * free = доходи місяця − витрати місяця − повторювані витрати, що ще будуть цього місяця − невикористаний залишок лімітів.
 */
data class FreeLeft(
    val freeUah: Double,
    val perDayUah: Double,
    val daysLeft: Int,
    val incomeUah: Double,
    val expenseUah: Double,
    val recurringUah: Double,
    val limitsUah: Double,
)

/**
 * @param recurring шаблони повторюваних операцій; враховуються активні витрати з наступною датою пізніше today у поточному місяці
 *   (ті, що вже настали, створені як операції і входять у витрати)
 * @param limits місячні ліміти за категоріями; невитрачений залишок ліміту резервується
 */
fun freeLeft(allTxs: List<Tx>, recurring: List<Recurring>, limits: Map<String, Double>, today: String): FreeLeft {
    val cm = today.take(7)
    val day = (if (today.length >= 10) today.substring(8, 10).toIntOrNull() else null)?.coerceAtLeast(1) ?: 1
    val dim = try { YearMonth.parse(cm).lengthOfMonth() } catch (_: Exception) { 30 }
    val daysLeft = Math.max(1, dim - day + 1)

    val cmTxs = allTxs.filter { it.date.startsWith(cm) }
    val income = cmTxs.filter { it.type == "income" }.sumOf { it.amt }
    val expense = cmTxs.filter { it.type == "expense" }.sumOf { it.amt }
    val pending = recurring.filter { it.active && it.type == "expense" && it.next.startsWith(cm) && it.next > today }
    val rec = pending.sumOf { it.amt }
    val lim = limits.entries.filter { it.value > 0 }.sumOf { (c, l) ->
        val spent = cmTxs.filter { it.type == "expense" && it.cat == c }.sumOf { it.amt }
        val planned = pending.filter { it.cat == c }.sumOf { it.amt }
        Math.max(0.0, l - spent - planned)
    }
    val free = income - expense - rec - lim
    return FreeLeft(free, Math.max(0.0, free) / daysLeft, daysLeft, income, expense, rec, lim)
}
