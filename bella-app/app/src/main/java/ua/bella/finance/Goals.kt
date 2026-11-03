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
