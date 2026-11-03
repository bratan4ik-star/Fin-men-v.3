package ua.bella.finance

/** Іконки цілей: ключ у даних → підпис у формі. */
val GOAL_ICONS = listOf("shield" to "Подушка", "plane" to "Подорож", "laptop" to "Техніка", "flag" to "Інше")

/** Перевірка форми цілі (суми у валюті перегляду). "" — можна зберігати. */
fun goalFormError(name: String, target: Double, saved: Double, step: Double, deadline: String): String = when {
    name.isBlank() -> "Вкажіть назву цілі"
    target <= 0 -> "Вкажіть суму цілі більше нуля"
    saved < 0 -> "Накопичено не може бути від'ємним"
    step <= 0 -> "Крок внеску має бути більше нуля"
    deadline.isNotBlank() && !isIsoDate(deadline.trim()) -> "Дедлайн у форматі РРРР-ММ-ДД"
    else -> ""
}

/**
 * Внесок у ціль (amountUah > 0) або зняття з неї (amountUah < 0), у гривнях.
 * Якщо вказано рахунок, гроші переходять між рахунком і ціллю (внесок списує з рахунку, зняття повертає),
 * тож чистий капітал не змінюється. Повертає нові дані або текст помилки.
 */
fun moveGoal(d: AppData, gi: Int, amountUah: Double, accId: String, rates: Map<String, Double>): Pair<AppData?, String> {
    val g = d.goals.getOrNull(gi) ?: return null to "Ціль не знайдено"
    if (amountUah == 0.0) return null to "Вкажіть суму більше нуля"
    if (amountUah < 0 && -amountUah > g.saved + 1e-6) return null to "У цілі лише ${fmtInt(g.saved)} ₴"
    val acc = d.accs.firstOrNull { it.id == accId }
    var accs = d.accs
    if (acc != null) {
        val inAcc = amountUah / (rates[acc.cur] ?: 1.0)
        if (amountUah > 0 && inAcc > acc.bal + 1e-6) return null to "Недостатньо коштів на рахунку «${acc.name}»"
        accs = d.accs.map { if (it.id == acc.id) it.copy(bal = it.bal - inAcc) else it }
    }
    val goals = d.goals.mapIndexed { i, x -> if (i == gi) x.copy(saved = Math.max(0.0, x.saved + amountUah)) else x }
    return d.copy(goals = goals, accs = accs) to ""
}

/** Стан прогнозу цілі. */
enum class GoalStatus { DONE, NO_PLAN, ON_TIME, LATE, NO_DEADLINE, TOO_LONG }

/**
 * Прогноз за запланованим щомісячним внеском (усе в гривнях).
 * reachMonth — РРРР-ММ, коли ціль буде досягнута; needMonthly — скільки треба відкладати, щоб устигнути до дедлайну
 * (0, якщо дедлайну немає або він уже минув); deadlinePassed — дедлайн у минулому.
 */
data class GoalForecast(val status: GoalStatus, val months: Int = 0, val reachMonth: String = "", val needMonthly: Double = 0.0, val deadlinePassed: Boolean = false)

fun goalForecast(g: Goal, today: String): GoalForecast {
    val rem = g.target - g.saved
    if (rem <= 1e-6) return GoalForecast(GoalStatus.DONE)
    val now = java.time.LocalDate.parse(today)
    val dl = if (isIsoDate(g.deadline)) java.time.LocalDate.parse(g.deadline) else null
    // повних місяців до дедлайну (мінімум 1, якщо дедлайн ще попереду)
    val toDl = dl?.let { java.time.temporal.ChronoUnit.MONTHS.between(java.time.YearMonth.from(now), java.time.YearMonth.from(it)).toInt() }
    val passed = dl != null && dl.isBefore(now)
    val need = if (toDl != null && !passed) rem / Math.max(1, toDl) else 0.0
    if (g.monthly <= 0) return GoalForecast(GoalStatus.NO_PLAN, needMonthly = need, deadlinePassed = passed)
    val months = Math.ceil(rem / g.monthly - 1e-9).toInt()
    if (months > 600) return GoalForecast(GoalStatus.TOO_LONG, months, needMonthly = need, deadlinePassed = passed)
    val reach = java.time.YearMonth.from(now).plusMonths(months.toLong())
    val st = when {
        dl == null -> GoalStatus.NO_DEADLINE
        !reach.isAfter(java.time.YearMonth.from(dl)) -> GoalStatus.ON_TIME
        else -> GoalStatus.LATE
    }
    return GoalForecast(st, months, reach.toString(), need, passed)
}

/** «березень 2028». */
fun monthNameLower(ym: String): String = if (ym.length >= 7) MONTH_NAMES[ym.substring(5, 7).toInt() - 1].lowercase() + " " + ym.substring(0, 4) else ""
