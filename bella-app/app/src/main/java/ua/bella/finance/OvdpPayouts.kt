package ua.bella.finance

import java.time.LocalDate

/*
 * Автоматичні виплати ОВДП для позицій з autoCpn (вмикається у формі позиції, рахунок — payAcc):
 * - у дату купона — операція доходу «Купон: назва» (категорія «Надходження») на рахунок;
 * - у дату погашення — купон і переказ номіналу з інвестицій на рахунок «Погашення: назва» (переказ, не дохід),
 *   кількість позиції стає 0 («погашено»), щоб номінал не рахувався в капіталі двічі.
 * Дати й суми — з офіційного графіка НБУ, якщо ISIN позиції є в довіднику, інакше — як у нагадуваннях (ovdpEvents).
 * Обробляються виплати від autoFrom (день увімкнення) до сьогодні включно; після обробки autoFrom переходить на
 * наступний день. Тож купони, внесені раніше вручну, не дублюються, а видалена операція не з'являється знову.
 * Суми у валюті облігації переводяться в гривні за курсом НБУ, на рахунок — у його валюті.
 */

/** Ключі операцій: купон і погашення (захист від повторів). */
fun ovdpCouponKey(name: String, date: String) = "ovdp|$name|$date"
fun ovdpMaturityKey(name: String, date: String) = "ovdpm|$name|$date"

fun processOvdpPayouts(d: AppData, today: String, rates: Map<String, Double>, reg: Map<String, NbuBond> = emptyMap()): AppData {
    if (!isIsoDate(today)) return d
    val now = LocalDate.parse(today)
    fun r(c: String) = if (c == "UAH") 1.0 else rates[c] ?: 1.0
    var data = d
    val keys = d.txs.map { it.key }.toMutableSet()
    var changed = false
    val pos = d.pos.map { p ->
        if (p.group != "ОВДП" || !p.autoCpn || p.qty <= 0 || !isIsoDate(p.autoFrom)) return@map p
        val from = LocalDate.parse(p.autoFrom)
        if (from.isAfter(now)) return@map p
        // без рахунку нічого не створюємо: гроші мають кудись надійти
        val acc = data.accs.firstOrNull { it.id == p.payAcc && it.id.isNotEmpty() } ?: return@map p
        val events = ovdpEvents(listOf(p), from, now, reg)
        if (events.isEmpty()) return@map p
        var matured = false
        events.forEach { e ->
            val date = e.date.toString(); val fx = r(e.cur)
            if (e.coupon > 0) {
                val key = ovdpCouponKey(p.name, date)
                if (key !in keys) {
                    val uah = e.coupon * fx
                    data = addTx(data, Tx("Купон: ${p.name}", "Надходження", "income", uah, date, key, acc.id, uah / r(acc.cur))); keys += key
                }
            }
            if (e.principal > 0) {
                val key = ovdpMaturityKey(p.name, date)
                if (key !in keys) {
                    val uah = e.principal * fx
                    data = addTx(data, Tx("Погашення: ${p.name}", "Перекази", "transfer", uah, date, key, "", 0.0, acc.id, uah / r(acc.cur))); keys += key
                }
                matured = true
            }
        }
        changed = true
        p.copy(autoFrom = now.plusDays(1).toString(), qty = if (matured) 0.0 else p.qty)
    }
    return if (!changed) d else data.copy(pos = pos)
}

/** Позиція ОВДП погашена автоматично (кількість 0 після дати погашення). */
fun ovdpRedeemed(p: Pos, today: String): Boolean = p.group == "ОВДП" && p.qty == 0.0 && p.autoCpn && isIsoDate(p.mat) && p.mat <= today
