package ua.bella.finance

/** Фрагменти правила: через кому, без пробілів по краях, у нижньому регістрі. */
fun ruleParts(r: CatRule): List<String> = r.match.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

/** Категорія з першого правила, фрагмент якого є в описі; null — жодне не підійшло. */
fun ruleFor(desc: String, rules: List<CatRule>): String? {
    val d = desc.lowercase()
    return rules.firstOrNull { r -> r.cat.isNotBlank() && ruleParts(r).any { d.contains(it) } }?.cat
}

/** Застосовує правила до операцій (перекази не чіпає). Повертає нові операції і кількість змінених. */
fun applyRules(txs: List<Tx>, rules: List<CatRule>): Pair<List<Tx>, Int> {
    var n = 0
    val out = txs.map { t ->
        if (t.type == "transfer") return@map t
        val c = ruleFor(t.desc, rules)
        if (c != null && c != t.cat) { n++; t.copy(cat = c) } else t
    }
    return out to n
}

/**
 * Пропозиція правила після ручної зміни категорії операції: «Завжди ставити cat для desc?».
 * null — категорія не змінилась, це переказ, опис закороткий або правила вже дають цю категорію.
 */
fun suggestRule(old: Tx, new: Tx, rules: List<CatRule>): CatRule? {
    val d = new.desc.trim()
    if (old.cat == new.cat || new.type == "transfer" || d.length < 3) return null
    if (ruleFor(d, rules) == new.cat) return null
    return CatRule(d, new.cat)
}

/** Додає правило першим (воно важливіше за загальні); правило з тим самим текстом замінюється. */
fun addRule(rules: List<CatRule>, r: CatRule): List<CatRule> =
    listOf(r) + rules.filter { it.match.trim().lowercase() != r.match.trim().lowercase() }
