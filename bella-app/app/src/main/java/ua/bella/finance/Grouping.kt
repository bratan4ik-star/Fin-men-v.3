package ua.bella.finance

/** Групи рахунків: картки (не готівка, не заощадження), готівка, заощадження. Порожні групи не показуються. */
enum class AccGroup(val title: String) { CARDS("Картки"), CASH("Готівка"), SAVINGS("Заощадження") }

fun accGroup(a: Account): AccGroup = when { a.sav -> AccGroup.SAVINGS; a.cash -> AccGroup.CASH; else -> AccGroup.CARDS }

/** Рахунки за групами (з індексами в списку даних), лише непорожні, у фіксованому порядку. */
fun groupAccounts(accs: List<Account>): List<Pair<AccGroup, List<IndexedValue<Account>>>> =
    AccGroup.values().map { g -> g to accs.withIndex().filter { accGroup(it.value) == g } }.filter { it.second.isNotEmpty() }

/** Борги за напрямом: «Мені винні» і «Я винен»; порожні не показуються. Сума — лише невиплачені. */
data class DebtGroup(val title: String, val owedToMe: Boolean, val items: List<IndexedValue<Debt>>, val openTotal: Double)

fun groupDebts(debts: List<Debt>): List<DebtGroup> = listOf(true to "Мені винні", false to "Я винен").mapNotNull { (dir, title) ->
    val items = debts.withIndex().filter { it.value.owedToMe == dir }
    if (items.isEmpty()) null else DebtGroup(title, dir, items, items.filter { !it.value.done }.sumOf { it.value.amt })
}
