package ua.bella.finance

/*
 * Редагування з підказкою «як було»: форма запам'ятовує значення на момент відкриття.
 * Порожнє поле показує старе значення сірою підказкою, а під час збереження порожнє поле означає «без змін».
 * Сума зарахування переказу (tToAmt) не підставляється: порожня означає «за курсом».
 */
fun fillBlanks(cur: Form, orig: Form): Form {
    fun k(v: String, o: String) = if (v.isBlank()) o else v
    return cur.copy(
        tAmt = k(cur.tAmt, orig.tAmt), tDesc = k(cur.tDesc, orig.tDesc), tDate = k(cur.tDate, orig.tDate),
        name = k(cur.name, orig.name), bal = k(cur.bal, orig.bal),
        pName = k(cur.pName, orig.pName), pQty = k(cur.pQty, orig.pQty), pBuy = k(cur.pBuy, orig.pBuy), pNow = k(cur.pNow, orig.pNow),
        pRate = k(cur.pRate, orig.pRate), pMat = k(cur.pMat, orig.pMat), pNext = k(cur.pNext, orig.pNext),
        rDay = k(cur.rDay, orig.rDay), who = k(cur.who, orig.who), amt = k(cur.amt, orig.amt), due = cur.due, gName = k(cur.gName, orig.gName), gTarget = k(cur.gTarget, orig.gTarget), gSaved = k(cur.gSaved, orig.gSaved), gStep = k(cur.gStep, orig.gStep), // дедлайн не підставляється: порожній — без дедлайну
    )
}

/** Перевіряє дати позиції: обидві необов'язкові, але у форматі РРРР-ММ-ДД, і виплата не пізніше повернення. "" — усе гаразд. */
fun posDatesError(next: String, mat: String): String {
    val n = next.trim(); val m = mat.trim()
    if (n.isNotEmpty() && !isIsoDate(n)) return "Дата виплати у форматі РРРР-ММ-ДД"
    if (m.isNotEmpty() && !isIsoDate(m)) return "Дата повернення у форматі РРРР-ММ-ДД"
    if (n.isNotEmpty() && m.isNotEmpty() && n > m) return "Дата виплати не може бути пізніше дати повернення"
    return ""
}

/**
 * Поля форми позиції, які ще треба заповнити (підсвічуються бурштиновим, поки порожні): назва, кількість (крім депозиту),
 * ціна покупки / сума депозиту, а для щомісячного депозиту — дата виплати й ставка. Ті самі умови, що в posFormError.
 * У редагуванні порожнє поле означає «як було» ([orig]), тож воно не підсвічується, якщо старе значення є.
 */
fun posMissing(f: Form, orig: Form?): Set<String> {
    fun eff(v: String, o: String?) = if (v.isBlank()) o.orEmpty() else v
    val dep = f.group == "Депозити"
    val out = linkedSetOf<String>()
    if (eff(f.pName, orig?.pName).isBlank()) out += "name"
    if (!dep && parseNum(eff(f.pQty, orig?.pQty)) <= 0) out += "qty"
    if (parseNum(eff(f.pBuy, orig?.pBuy)) <= 0) out += "buy"
    if (dep && f.pMonthly) {
        if (parseNum(eff(f.pRate, orig?.pRate)) <= 0) out += "rate"
        if (eff(f.pNext, orig?.pNext).isBlank()) out += "next"
    }
    return out
}
val MISSING_LABELS = mapOf("name" to "назва", "qty" to "кількість", "buy" to "ціна покупки", "rate" to "ставка", "next" to "дата виплати")

/** Перевірка форми позиції. "" — можна зберігати. */
fun posFormError(group: String, name: String, qty: Double, buy: Double, monthly: Boolean, rate: Double, next: String, mat: String): String {
    val dep = group == "Депозити"
    if (name.isBlank()) return if (dep) "Вкажіть назву депозиту" else "Вкажіть назву"
    if (qty <= 0) return "Вкажіть кількість більше нуля"
    if (buy <= 0) return if (dep) "Вкажіть суму депозиту" else "Вкажіть ціну покупки"
    posDatesError(next, mat).let { if (it.isNotEmpty()) return it }
    if (dep && monthly) {
        if (next.isBlank()) return "Для щомісячних виплат вкажіть дату виплати"
        if (rate <= 0) return "Для щомісячних виплат вкажіть ставку"
    }
    return ""
}

/** «03.11.2026 о 14:05» для часу збереження; порожньо, якщо ще не зберігалось. */
fun fmtSavedAt(ms: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
    if (ms <= 0) return ""
    val t = java.time.Instant.ofEpochMilli(ms).atZone(zone)
    return t.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy")) + " о " + t.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
}
