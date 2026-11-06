package ua.bella.finance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/*
 * ETF у валюті фонду. Pos.cur — валюта ціни (UAH для старих позицій), pxBuy/pxNow — ціна купівлі й поточна
 * в цій валюті, buyFx — курс гривні до цієї валюти на дату купівлі. Вартість у гривнях іде за поточним курсом НБУ,
 * тож прибуток ділиться на дві частини: від зміни ціни фонду і від зміни курсу.
 */

/** Позиція рахується у валюті фонду (а не в гривнях). */
fun isFxPos(p: Pos): Boolean = p.cur != "UAH" && p.pxNow > 0

/** Поточна вартість у гривнях. */
fun posValueUah(p: Pos, rates: Map<String, Double>): Double =
    if (isFxPos(p)) p.qty * p.pxNow * (rates[p.cur] ?: 1.0) else p.qty * p.now

/** Вартість купівлі в гривнях. */
fun posCostUah(p: Pos): Double =
    if (isFxPos(p) && p.pxBuy > 0 && p.buyFx > 0) p.qty * p.pxBuy * p.buyFx else p.qty * p.buy

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

/** Котирування: ціна і валюта (як їх повертає біржа). */
data class Quote(val price: Double, val currency: String)

/** Розбирає відповідь Yahoo Finance chart API (meta.regularMarketPrice, meta.currency). */
fun parseYahooQuote(body: String): Quote? = try {
    val meta = Json.parseToJsonElement(body).jsonObject["chart"]!!.jsonObject["result"]!!.jsonArray[0].jsonObject["meta"]!!.jsonObject
    val price = meta["regularMarketPrice"]?.jsonPrimitive?.doubleOrNull
    val cur = meta["currency"]?.jsonPrimitive?.content
    if (price != null && price > 0 && cur != null) Quote(price, cur) else null
} catch (_: Exception) { null }

/** Мережевий запит котирування за тікером у форматі Yahoo (VWCE.DE, SPY, EUNL.DE). Лише поза головним потоком. */
fun fetchQuote(ticker: String): Quote? {
    val t = ticker.trim().uppercase(); if (t.isEmpty()) return null
    val c = URL("https://query1.finance.yahoo.com/v8/finance/chart/" + URLEncoder.encode(t, "UTF-8") + "?range=1d&interval=1d").openConnection() as HttpURLConnection
    return try {
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/json"); c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) BellaFinance")
        if (c.responseCode != HttpURLConnection.HTTP_OK) null else parseYahooQuote(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
    } finally { c.disconnect() }
}

/** Застосовує котирування до позиції. Повертає нову позицію або текст помилки. */
fun applyQuote(p: Pos, q: Quote, rates: Map<String, Double>, now: Long): Pair<Pos?, String> {
    if (q.currency !in rates.keys) return null to "${p.name}: валюта котирування ${q.currency} не підтримується"
    if (isFxPos(p) && p.cur != q.currency) return null to "${p.name}: котирування в ${q.currency}, а позиція в ${p.cur}"
    val rate = rates[q.currency] ?: 1.0
    return if (isFxPos(p)) p.copy(pxNow = q.price, now = q.price * rate, pxAt = now) to ""
    // гривнева позиція з тікером переходить на валюту фонду: ціна купівлі перераховується за поточним курсом
    else p.copy(cur = q.currency, pxBuy = if (p.buy > 0) p.buy / rate else q.price, buyFx = rate, pxNow = q.price, now = q.price * rate, pxAt = now) to ""
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
