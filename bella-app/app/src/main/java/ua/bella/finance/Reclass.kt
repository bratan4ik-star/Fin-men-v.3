package ua.bella.finance

/*
 * Перекласифікація вже імпортованих операцій ПриватБанку за поточними правилами (classifyPrivat).
 * Імпортовані з виписки операції мають ключ «дата|картка|сума|опис» (4 частини); ручні — порожній ключ.
 * У Tx зберігається вже зіставлена категорія, тож банківську відновлюємо з типу й опису.
 * Суми й баланси не змінюються: зміна йде через replaceTx, тож ефект на рахунки коректний.
 */

/** Імпортована з виписки ПриватБанку операція: ключ «дата|картка|сума|опис», картка містить «*». */
fun isBankImported(t: Tx): Boolean = t.key.split("|").let { it.size == 4 && it[1].contains("*") }

/** Картка операції з ключа (останні 4 цифри), порожньо для ручних. */
fun cardOfKey(key: String): String = key.split("|").getOrNull(1)?.takeLast(4).orEmpty()

private val PERSON_DESC = Regex("^\\p{Lu}[\\p{L}'’-]+(\\s+\\p{L}+)?\\s+\\p{Lu}\\.$")

/** Банківська категорія, з якої міг з'явитися запис. */
fun bankCatOf(t: Tx): String = when {
    t.type == "transfer" && t.cat == "Заощадження" -> "Заощадження"
    t.type == "expense" && t.cat == "Перекази" -> "Перекази"
    t.type == "income" && t.cat == "Надходження" && PERSON_DESC.matches(t.desc.trim()) -> "Зарахування переказу"
    t.type == "income" && t.cat == "Надходження" -> "Зарахування"
    else -> t.cat
}

data class ReclassChange(val index: Int, val old: Tx, val new: Tx)

/**
 * Зміни імпортованих операцій: нові тип і категорія за правилами, а також прив'язка рахунків
 * (картка → acc; переказ на Скарбничку/депозит → toAcc). Ручні й неімпортовані не чіпаються.
 */
fun reclassifyImported(txs: List<Tx>, accs: List<Account> = emptyList()): List<ReclassChange> = txs.mapIndexedNotNull { i, t ->
    if (!isBankImported(t)) return@mapIndexedNotNull null
    val sign = if (t.type == "expense") -1.0 else 1.0
    val rule = Importer.classifyPrivat(bankCatOf(t), t.desc, sign * t.amt)
    val typed = if (rule != null) t.copy(type = rule.first, cat = rule.second) else t
    val linked = linkSavingsTransfers(listOf(withCardAccount(typed, accs)), accs).single()
    if (typed.type == t.type && typed.cat == t.cat && linked.acc == t.acc && linked.toAcc == t.toAcc) null
    else ReclassChange(i, t, linked)
}

/** Рахунок за карткою з ключа, якщо операція ще не прив'язана. */
fun withCardAccount(t: Tx, accs: List<Account>): Tx {
    if (t.acc.isNotEmpty()) return t
    val card = cardOfKey(t.key)
    val id = accs.firstOrNull { it.id.isNotEmpty() && card.isNotEmpty() && it.card.takeLast(4) == card }?.id.orEmpty()
    return if (id.isEmpty()) t else t.copy(acc = id)
}

/**
 * Застосовує зміни без коригування балансів: баланси імпортованих операцій уже відповідають виписці,
 * тож перекласифікація лише міняє тип, категорію й прив'язку рахунків.
 */
fun applyReclass(d: AppData, changes: List<ReclassChange>): AppData =
    if (changes.isEmpty()) d else d.copy(txs = d.txs.mapIndexed { i, t -> changes.firstOrNull { it.index == i }?.new ?: t })

/**
 * Переказ на депозит/Скарбничку: операції «Заощадження» з виписки отримують другий рахунок (toAcc).
 * Напрямок — за знаком суми з ключа: від'ємна — з картки на накопичувальний рахунок, додатна — навпаки.
 * Накопичувальний рахунок — sav = true; якщо їх кілька, беремо той, чия назва збігається з описом (Скарбничка/Депозит).
 * Вже заповнені toAcc не змінюються. Баланси тут не змінюються (імпорт їх не перераховує).
 */
fun linkSavingsTransfers(txs: List<Tx>, accs: List<Account>, fallbackId: String = ""): List<Tx> {
    val savings = accs.filter { it.sav && it.id.isNotEmpty() }
    if (savings.isEmpty() && fallbackId.isEmpty()) return txs
    return txs.map { t ->
        if (t.type != "transfer" || t.cat != "Заощадження" || t.toAcc.isNotEmpty() || t.acc.isEmpty()) return@map t
        // 1) за 4 цифрами рахунку в описі (напр. «Поповнення депозиту 2620**1697» → картка 1697)
        val frag = Regex("\\d{4}").findAll(t.desc).lastOrNull()?.value
        val byDigits = frag?.let { f -> accs.firstOrNull { it.id.isNotEmpty() && it.card.takeLast(4) == f && it.id != t.acc } }
        // 2) єдиний накопичувальний рахунок, або за назвою (Скарбничка/Депозит)
        val byName = savings.singleOrNull() ?: savings.firstOrNull { a ->
            val d = t.desc.lowercase(); val n = a.name.lowercase()
            (d.contains("скарб") && n.contains("скарб")) || (d.contains("депозит") && n.contains("депоз"))
        }
        // 3) вибір користувача перед імпортом (коли накопичувальних кілька і збігу немає)
        val target = byDigits ?: byName ?: accs.firstOrNull { it.id == fallbackId } ?: return@map t
        val amount = t.key.split("|").getOrNull(2)?.toDoubleOrNull() ?: return@map t
        if (amount < 0) t.copy(toAcc = target.id) else t.copy(toAcc = t.acc, acc = target.id)
    }
}
