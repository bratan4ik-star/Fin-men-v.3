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

/** Індекс розділу довідки (HELP у Sheets.kt) для екрана: 0 Головна, 2 Капітал, 4 Бюджет, 7 Інвестиції, 8 ОВДП, 9 Цілі й пасивний дохід. */
fun helpSectionFor(s: Screen): Int = when (s) {
    // індекси розділів HELP (Sheets.kt); відповідність назвам перевіряє GroupingTest
    Screen.Home -> 0; Screen.Acc -> 2; Screen.Bud -> 4; Screen.Inv -> 7; Screen.Calc -> 9; Screen.Goals, Screen.Pass -> 11
}

/** Борг повернено: прибирає його зі списку (за індексом); інші не змінюються. */
fun settleDebt(debts: List<Debt>, i: Int): List<Debt> = debts.filterIndexed { j, _ -> j != i }
