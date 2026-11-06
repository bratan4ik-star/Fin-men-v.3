package ua.bella.finance

import java.time.LocalDate
import java.util.UUID

/*
 * Операції й рахунки. Операція може бути прив'язана до рахунку (Tx.acc = Account.id).
 * accAmt — сума у валюті рахунку, яка змінила його баланс; для переказу toAcc/toAmt — рахунок і сума зарахування.
 * Операції без рахунку (acc = "") на баланси не впливають.
 * Редагування = скасувати вплив старої версії + застосувати вплив нової.
 */

fun newAccId(): String = "acc-" + UUID.randomUUID().toString().take(8)

/** Дає кожному рахунку унікальний id (старі дані й імпорт приходять без нього). */
fun ensureAccIds(d: AppData): AppData {
    val ids = d.accs.map { it.id }
    if (ids.none { it.isEmpty() } && ids.distinct().size == ids.size) return d
    val used = mutableSetOf<String>()
    return d.copy(accs = d.accs.map { a ->
        val id = if (a.id.isEmpty() || a.id in used) newAccId() else a.id
        used += id; if (id == a.id) a else a.copy(id = id)
    })
}

/** Переносить id старих рахунків на нові (імпорт перебудовує список): збіг за назвою або за останніми 4 символами. */
fun carryAccIds(old: List<Account>, new: List<Account>): List<Account> {
    val taken = mutableSetOf<String>()
    return new.map { n ->
        if (n.id.isNotEmpty()) { taken += n.id; return@map n }
        val m = old.firstOrNull { it.id.isNotEmpty() && it.id !in taken && it.name == n.name }
            ?: old.firstOrNull { it.id.isNotEmpty() && it.id !in taken && it.name.takeLast(4) == n.name.takeLast(4) }
        if (m != null) { taken += m.id; n.copy(id = m.id) } else n
    }
}

private fun adjust(accs: List<Account>, id: String, delta: Double): List<Account> =
    if (id.isEmpty() || delta == 0.0) accs else accs.map { if (it.id == id) it.copy(bal = it.bal + delta) else it }

/** Вплив операції на баланси рахунків. sign = 1.0 застосувати, -1.0 скасувати. */
fun applyTxEffect(accs: List<Account>, t: Tx, sign: Double): List<Account> = when (t.type) {
    "income" -> adjust(accs, t.acc, sign * t.accAmt)
    "expense" -> adjust(accs, t.acc, -sign * t.accAmt)
    "transfer" -> adjust(adjust(accs, t.acc, -sign * t.accAmt), t.toAcc, sign * t.toAmt)
    else -> accs
}

fun addTx(d: AppData, t: Tx): AppData = d.copy(txs = listOf(t) + d.txs, accs = applyTxEffect(d.accs, t, 1.0))

fun replaceTx(d: AppData, index: Int, t: Tx): AppData {
    val old = d.txs.getOrNull(index) ?: return d
    val accs = applyTxEffect(applyTxEffect(d.accs, old, -1.0), t, 1.0)
    return d.copy(txs = d.txs.mapIndexed { i, x -> if (i == index) t else x }, accs = accs)
}

fun deleteTx(d: AppData, index: Int): AppData {
    val old = d.txs.getOrNull(index) ?: return d
    return d.copy(txs = d.txs.filterIndexed { i, _ -> i != index }, accs = applyTxEffect(d.accs, old, -1.0))
}

fun todayIso(): String = LocalDate.now().toString()

fun isIsoDate(s: String): Boolean = Regex("\\d{4}-\\d{2}-\\d{2}").matches(s) && try { LocalDate.parse(s); true } catch (_: Exception) { false }

/** Поля форми операції, з яких будується Tx. amount — у валюті рахунку (або у валюті перегляду, якщо рахунку немає). */
data class TxInput(
    val type: String, val amount: Double, val cat: String, val desc: String, val date: String,
    val acc: String = "", val toAcc: String = "", val toAmount: Double = 0.0, val key: String = "",
)

/** Будує операцію або повертає текст помилки. viewRate — курс валюти перегляду до гривні (для операцій без рахунку). */
fun buildTx(inp: TxInput, accs: List<Account>, rates: Map<String, Double>, viewRate: Double): Pair<Tx?, String> {
    if (inp.amount <= 0) return null to "Вкажіть суму більше нуля"
    if (!isIsoDate(inp.date)) return null to "Дата у форматі РРРР-ММ-ДД"
    val acc = accs.firstOrNull { it.id == inp.acc }
    if (inp.type == "transfer") {
        val to = accs.firstOrNull { it.id == inp.toAcc }
        if (acc == null || to == null) return null to "Оберіть обидва рахунки"
        if (acc.id == to.id) return null to "Оберіть різні рахунки"
        val toAmt = if (inp.toAmount > 0) inp.toAmount else convertAmount(inp.amount, acc.cur, to.cur, rates)
        val desc = inp.desc.ifBlank { "Переказ: ${acc.name} → ${to.name}" }
        return Tx(desc, "Перекази", "transfer", inp.amount * (rates[acc.cur] ?: 1.0), inp.date, inp.key, acc.id, inp.amount, to.id, toAmt) to ""
    }
    val uah = if (acc != null) inp.amount * (rates[acc.cur] ?: 1.0) else inp.amount * viewRate
    return Tx(inp.desc.ifBlank { inp.cat }, inp.cat, inp.type, uah, inp.date, inp.key, acc?.id ?: "", if (acc != null) inp.amount else 0.0) to ""
}

/**
 * Рахунки після імпорту виписки: картки з виписки оновлюють назву, валюту й залишок свого рахунку
 * (збіг за id, назвою або останніми 4 символами) або додаються як нові; решта рахунків
 * (готівка, створені вручну) лишаються разом із позначками «Готівка» і «Заощадження».
 */
fun mergeImportedAccs(old: List<Account>, imported: List<Account>): List<Account> {
    val withIds = carryAccIds(old, imported)
    val byId = withIds.filter { it.id.isNotEmpty() }.associateBy { it.id }
    val kept = old.map { o -> byId[o.id]?.let { n -> o.copy(name = n.name, cur = n.cur, bal = n.bal) } ?: o }
    val oldIds = old.map { it.id }.toSet()
    return kept + withIds.filter { it.id.isEmpty() || it.id !in oldIds }
}

/** Масова зміна категорії операцій за індексами в d.txs. Перекази не змінюються (у них своя категорія). Баланси рахунків не зачіпаються. */
fun bulkSetCat(d: AppData, idx: Set<Int>, cat: String): AppData {
    if (idx.isEmpty() || cat.isBlank()) return d
    return d.copy(txs = d.txs.mapIndexed { i, t -> if (i in idx && t.type != "transfer" && t.cat != cat) t.copy(cat = cat) else t })
}

/** Масове видалення операцій за індексами в d.txs; вплив кожної на баланси рахунків скасовується. */
fun bulkDelete(d: AppData, idx: Set<Int>): AppData {
    if (idx.isEmpty()) return d
    var accs = d.accs
    d.txs.forEachIndexed { i, t -> if (i in idx) accs = applyTxEffect(accs, t, -1.0) }
    return d.copy(txs = d.txs.filterIndexed { i, _ -> i !in idx }, accs = accs)
}
