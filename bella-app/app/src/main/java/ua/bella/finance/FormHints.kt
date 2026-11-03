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

/** Перевірка форми позиції. "" — можна зберігати. */
fun posFormError(group: String, name: String, qty: Double, buy: Double, monthly: Boolean, rate: Double, next: String, mat: String): String {
    val dep = group == "Депозити"
    if (name.isBlank()) return if (dep) "Вкажіть назву депозиту" else "Вкажіть назву або тікер"
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
