package ua.bella.finance

/*
 * Перекласифікація вже імпортованих операцій ПриватБанку за поточними правилами (classifyPrivat).
 * Імпортовані з виписки операції мають ключ «дата|картка|сума|опис» (4 частини); ручні — порожній ключ.
 * У Tx зберігається вже зіставлена категорія, тож банківську відновлюємо з типу й опису.
 * Суми й баланси не змінюються: зміна йде через replaceTx, тож ефект на рахунки коректний.
 */

/** Імпортована з виписки операція (ключ із чотирьох частин). */
fun isBankImported(t: Tx): Boolean = t.key.split("|").size == 4

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

/** Зміни тих імпортованих операцій, яким нові правила дають інший тип або категорію. Інші не чіпаються. */
fun reclassifyImported(txs: List<Tx>): List<ReclassChange> = txs.mapIndexedNotNull { i, t ->
    if (!isBankImported(t)) return@mapIndexedNotNull null
    val sign = if (t.type == "expense") -1.0 else 1.0
    val (type, cat) = Importer.classifyPrivat(bankCatOf(t), t.desc, sign * t.amt) ?: return@mapIndexedNotNull null
    if (type == t.type && cat == t.cat) null else ReclassChange(i, t, t.copy(type = type, cat = cat))
}

/** Застосовує зміни через replaceTx (баланси коригуються, якщо операція прив'язана до рахунку). */
fun applyReclass(d: AppData, changes: List<ReclassChange>): AppData =
    changes.fold(d) { acc, c -> replaceTx(acc, c.index, c.new) }

/**
 * Переказ на депозит/Скарбничку: операції «Заощадження» з виписки отримують другий рахунок (toAcc).
 * Напрямок — за знаком суми з ключа: від'ємна — з картки на накопичувальний рахунок, додатна — навпаки.
 * Накопичувальний рахунок — sav = true; якщо їх кілька, беремо той, чия назва збігається з описом (Скарбничка/Депозит).
 * Вже заповнені toAcc не змінюються. Баланси тут не змінюються (імпорт їх не перераховує).
 */
fun linkSavingsTransfers(txs: List<Tx>, accs: List<Account>): List<Tx> {
    val savings = accs.filter { it.sav && it.id.isNotEmpty() }
    if (savings.isEmpty()) return txs
    return txs.map { t ->
        if (t.type != "transfer" || t.cat != "Заощадження" || t.toAcc.isNotEmpty() || t.acc.isEmpty()) return@map t
        val target = savings.singleOrNull() ?: savings.firstOrNull { a ->
            val d = t.desc.lowercase(); val n = a.name.lowercase()
            (d.contains("скарб") && n.contains("скарб")) || (d.contains("депозит") && n.contains("депоз"))
        } ?: return@map t
        val amount = t.key.split("|").getOrNull(2)?.toDoubleOrNull() ?: return@map t
        if (amount < 0) t.copy(toAcc = target.id) else t.copy(toAcc = t.acc, acc = target.id)
    }
}
