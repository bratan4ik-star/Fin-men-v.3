package ua.bella.finance

/** Службові категорії: їх використовують перекази, відсотки депозитів, доходи й «усе інше», тож видаляти не можна. */
val SYSTEM_CATS = setOf("Перекази", "Надходження", "Зарплата", "Інше")

/** Категорії, для яких має сенс ліміт витрат. */
fun limitCats(cats: List<String>): List<String> = cats.filter { it !in setOf("Перекази", "Надходження", "Зарплата") }

/** Додає категорію; повертає новий список або текст помилки. */
fun addCategory(cats: List<String>, name: String): Pair<List<String>?, String> {
    val n = name.trim()
    if (n.isEmpty()) return null to "Вкажіть назву категорії"
    if (n.length > 40) return null to "Назва задовга (до 40 символів)"
    if (cats.any { it.equals(n, ignoreCase = true) }) return null to "Категорія «$n» вже є"
    return (cats + n) to ""
}

/** Видаляє категорію (службові не видаляються). Операції з нею зберігають свою категорію. */
fun removeCategory(cats: List<String>, name: String): List<String> = if (name in SYSTEM_CATS) cats else cats.filter { it != name }

/**
 * Ліміти з полів форми: значення у валюті перегляду → гривні; порожньо або 0 — без ліміту.
 * Порядок — як у списку категорій. Повертає ліміти або текст помилки.
 */
fun buildLimits(cats: List<String>, edits: Map<String, String>, viewRate: Double): Pair<LinkedHashMap<String, Double>?, String> {
    val out = LinkedHashMap<String, Double>()
    for (c in limitCats(cats)) {
        val raw = edits[c]?.trim().orEmpty(); if (raw.isEmpty()) continue
        val v = parseNum(raw)
        if (v < 0 || (v == 0.0 && raw.replace("0", "").replace(",", "").replace(".", "").isNotBlank())) return null to "Ліміт «$c»: вкажіть число"
        if (v > 0) out[c] = v * viewRate
    }
    return out to ""
}
