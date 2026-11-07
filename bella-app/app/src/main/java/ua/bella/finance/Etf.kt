package ua.bella.finance


/*
 * ETF у валюті фонду. Pos.cur — валюта ціни (UAH для старих позицій), pxBuy/pxNow — ціна купівлі й поточна
 * в цій валюті, buyFx — курс гривні до цієї валюти на дату купівлі. Вартість у гривнях іде за поточним курсом НБУ,
 * тож прибуток ділиться на дві частини: від зміни ціни фонду і від зміни курсу.
 */

/** Валютна позиція не-ETF (напр. ОВДП у USD з калькулятора): buy/now — у валюті cur, без pxBuy/pxNow. */
fun isBondCurPos(p: Pos): Boolean = !isFxPos(p) && p.cur != "UAH" && p.group != "ETF"

/** Позиція рахується у валюті фонду (а не в гривнях). */
fun isFxPos(p: Pos): Boolean = p.cur != "UAH" && p.pxNow > 0

/** Поточна вартість у гривнях. */
fun posValueUah(p: Pos, rates: Map<String, Double>): Double =
    if (isFxPos(p)) p.qty * p.pxNow * (rates[p.cur] ?: 1.0)
    else if (p.cur != "UAH") p.qty * p.now * (rates[p.cur] ?: 1.0)
    else p.qty * p.now

/** Вартість купівлі в гривнях. */
fun posCostUah(p: Pos): Double =
    if (isFxPos(p) && p.pxBuy > 0 && p.buyFx > 0) p.qty * p.pxBuy * p.buyFx
    else if (p.cur != "UAH") p.qty * p.buy * (RATES[p.cur] ?: 1.0)
    else p.qty * p.buy

/** Прибуток у гривнях: усього, від ціни фонду, від курсу. Для гривневих позицій курсова частина 0. */
data class PlSplit(val total: Double, val price: Double, val fx: Double)

fun plSplit(p: Pos, rates: Map<String, Double>): PlSplit {
    val value = posValueUah(p, rates); val cost = posCostUah(p)
    if (!isFxPos(p) || p.pxBuy <= 0 || p.buyFx <= 0) return PlSplit(value - cost, value - cost, 0.0)
    val rate = rates[p.cur] ?: p.buyFx
    val price = p.qty * (p.pxNow - p.pxBuy) * p.buyFx
    val fx = p.qty * p.pxNow * (rate - p.buyFx)
    return PlSplit(price + fx, price, fx)
}

/** Знак валюти: € $ zł ₴. */
fun curSign(cur: String): String = SYM[cur]?.trim() ?: cur

/** Сума у валюті фонду: «3 356,00 €». */
fun fxMoney(v: Double, cur: String): String = fmt2(v) + " " + curSign(cur)

/** Вартість і купівля у валюті фонду (для гривневих позицій — 0). */
fun posValueCur(p: Pos): Double = if (isFxPos(p)) p.qty * p.pxNow else 0.0
fun posCostCur(p: Pos): Double = if (isFxPos(p)) p.qty * p.pxBuy else 0.0

/** Зміна ціни фонду у відсотках у його валюті (без впливу курсу). */
fun plCurPct(p: Pos): Double = if (isFxPos(p) && p.pxBuy > 0) (p.pxNow - p.pxBuy) / p.pxBuy * 100 else 0.0

/** Курс купівлі правдоподібний: не більш ніж утричі відрізняється від поточного курсу НБУ (захист від введення ціни чи суми замість курсу). */
fun fxPlausible(buyFx: Double, rateNow: Double): Boolean = rateNow <= 0 || (buyFx >= rateNow / 3 && buyFx <= rateNow * 3)

/** Помилка форми позиції у валюті: неправдоподібний курс купівлі. */
fun fxFormError(p: Pos, rates: Map<String, Double>): String {
    if (!isFxPos(p)) return ""
    val now = rates[p.cur] ?: return ""
    return if (fxPlausible(p.buyFx, now)) "" else
        "Курс купівлі ${fmt2(p.buyFx)} ₴ за 1 ${p.cur} не схожий на справжній (НБУ зараз ${fmt2(now)}). Це курс гривні на день купівлі, а не ціна фонду. Якщо не знаєте — залиште поле порожнім."
}
