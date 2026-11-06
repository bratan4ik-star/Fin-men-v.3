package ua.bella.finance

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory

/*
 * Interactive Brokers через Flex Web Service (v3): застосунок надсилає токен і номер Flex-запиту,
 * отримує код звіту й забирає XML з позиціями (OpenPositions), грошима (CashReport) і дивідендами (CashTransactions).
 * Тут — лише розбір XML і об'єднання з даними застосунку; мережа — у IbkrNet.kt.
 */

const val FLEX_SEND = "https://ndcdyn.interactivebrokers.com/AccountManagement/FlexWebService/SendRequest"

data class IbPos(val symbol: String, val desc: String, val exchange: String, val cur: String, val qty: Double, val mark: Double, val cost: Double, val category: String)
data class IbDiv(val id: String, val date: String, val symbol: String, val cur: String, val amount: Double, val tax: Double)
data class FlexData(val account: String, val asOf: String, val positions: List<IbPos>, val cash: Map<String, Double>, val dividends: List<IbDiv>)

/** Відповідь SendRequest / GetStatement зі статусом: ref і url — коли звіт замовлено; code/message — помилка чи «ще готується». */
data class FlexStatus(val ok: Boolean, val ref: String, val url: String, val code: String, val message: String) {
    /** Звіт ще формується або сервер зайнятий — варто повторити за кілька секунд. */
    val retry get() = code in setOf("1001", "1004", "1005", "1006", "1007", "1008", "1009", "1018", "1019", "1021")
}

private fun sax(xml: String, h: DefaultHandler) {
    val f = SAXParserFactory.newInstance()
    try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) {}
    f.newSAXParser().parse(InputSource(StringReader(xml)), h)
}

/** Розбирає FlexStatementResponse; null — це не відповідь зі статусом (тобто, ймовірно, готовий звіт). */
fun parseFlexStatus(xml: String): FlexStatus? {
    if (!xml.contains("<FlexStatementResponse")) return null
    val v = HashMap<String, String>(); var cur = ""; val sb = StringBuilder()
    return try {
        sax(xml, object : DefaultHandler() {
            override fun startElement(u: String?, l: String?, q: String, a: Attributes?) { cur = q; sb.setLength(0) }
            override fun characters(ch: CharArray, s: Int, n: Int) { sb.append(ch, s, n) }
            override fun endElement(u: String?, l: String?, q: String) { if (q == cur) v[q] = sb.toString().trim() }
        })
        FlexStatus(v["Status"] == "Success", v["ReferenceCode"] ?: "", v["Url"] ?: "", v["ErrorCode"] ?: "", v["ErrorMessage"] ?: "")
    } catch (_: Exception) { FlexStatus(false, "", "", "", "Незрозуміла відповідь IBKR") }
}

/** Повідомлення українською для типових кодів помилок Flex. */
fun flexError(s: FlexStatus): String = when (s.code) {
    "1012" -> "Токен IBKR прострочений. Створіть новий у Flex Web Service Configuration."
    "1015" -> "Токен IBKR недійсний. Перевірте, що скопіювали його повністю."
    "1014" -> "Flex-запит з таким номером не знайдено. Перевірте Query ID."
    "1013" -> "IBKR не приймає запити з цієї IP-адреси: у налаштуваннях Flex Web Service вимкніть обмеження IP."
    "1011" -> "Flex Web Service вимкнено для рахунку. Увімкніть його в Account Management."
    "1018" -> "Забагато запитів до IBKR. Спробуйте за хвилину."
    "1020" -> "IBKR не прийняв запит (1020): токен і Query ID не підходять одне до одного. Перевірте, що Query ID — це номер саме Activity Flex Query (число в списку Flex Queries, не назва), токен створено в тому самому логіні IBKR і скопійовано повністю, без пробілів."
    "1019" -> "IBKR ще формує звіт. Спробуйте за хвилину."
    else -> "IBKR: " + s.message.ifEmpty { "помилка ${s.code}" }
}

private fun d(a: Attributes, k: String): Double = a.getValue(k)?.replace(",", "")?.toDoubleOrNull() ?: 0.0
private fun str(a: Attributes, k: String): String = a.getValue(k)?.trim() ?: ""

/** Дата IBKR: 20261105, 2026-11-05, 20261105;101500 або 11/05/2026 → 2026-11-05. */
fun flexDate(raw: String): String {
    val s = raw.trim().substringBefore(';').substringBefore(' ').substringBefore(',')
    Regex("^(\\d{4})(\\d{2})(\\d{2})$").find(s)?.let { val (y, m, dd) = it.destructured; return "$y-$m-$dd" }
    Regex("^(\\d{4})-(\\d{2})-(\\d{2})").find(s)?.let { val (y, m, dd) = it.destructured; return "$y-$m-$dd" }
    Regex("^(\\d{2})/(\\d{2})/(\\d{4})$").find(s)?.let { val (m, dd, y) = it.destructured; return "$y-$m-$dd" }
    return ""
}

/** Розбирає FlexQueryResponse. Позиції — рівень SUMMARY (або всі, якщо рівня немає); дивіденди об'єднуються з утриманим податком. */
fun parseFlexStatement(xml: String): FlexData {
    val pos = mutableListOf<IbPos>(); val cash = LinkedHashMap<String, Double>()
    data class Ct(val id: String, val date: String, val symbol: String, val cur: String, val amount: Double, val type: String)
    val cts = mutableListOf<Ct>(); var account = ""; var asOf = ""
    sax(xml, object : DefaultHandler() {
        override fun resolveEntity(publicId: String?, systemId: String?) = InputSource(StringReader(""))
        override fun startElement(u: String?, l: String?, q: String, a: Attributes) {
            when (q) {
                "FlexStatement" -> { account = str(a, "accountId"); asOf = flexDate(str(a, "toDate")) }
                "OpenPosition" -> {
                    val lvl = str(a, "levelOfDetail"); if (lvl.isNotEmpty() && !lvl.equals("SUMMARY", true)) return
                    val cost = d(a, "costBasisPrice").takeIf { it != 0.0 } ?: d(a, "openPrice")
                    pos += IbPos(str(a, "symbol"), str(a, "description"), str(a, "listingExchange"), str(a, "currency").uppercase(),
                        d(a, "position"), d(a, "markPrice"), cost, str(a, "assetCategory").uppercase())
                }
                "CashReportCurrency" -> {
                    val c = str(a, "currency").uppercase()
                    if (c.isNotEmpty() && c != "BASE_SUMMARY") cash[c] = d(a, "endingCash")
                }
                "CashTransaction" -> {
                    val lvl = str(a, "levelOfDetail"); if (lvl.equals("SUMMARY", true)) return
                    val date = flexDate(str(a, "dateTime").ifEmpty { str(a, "reportDate") }.ifEmpty { str(a, "settleDate") })
                    cts += Ct(str(a, "transactionID"), date, str(a, "symbol"), str(a, "currency").uppercase(), d(a, "amount"), str(a, "type"))
                }
            }
        }
    })
    val divs = cts.filter { it.type.contains("Dividend", true) }.distinctBy { it.id.ifEmpty { "${it.symbol}|${it.date}|${it.amount}" } }
    val taxes = cts.filter { it.type.contains("Withholding", true) }
    val out = divs.groupBy { Triple(it.symbol, it.date, it.cur) }.map { (k, l) ->
        val tax = taxes.filter { it.symbol == k.first && it.date == k.second && it.cur == k.third }.sumOf { it.amount }
        IbDiv(l.map { it.id }.sorted().joinToString("+").ifEmpty { "${k.first}|${k.second}" }, k.second, k.first, k.third, l.sumOf { it.amount }, tax)
    }.filter { it.amount > 0 }.sortedBy { it.date }
    return FlexData(account, asOf, pos.filter { it.qty != 0.0 }, cash, out)
}

/** Тікер Yahoo для оновлення цін з біржі: суфікс за біржею IBKR (IBIS → .DE, LSEETF → .L тощо). */
fun yahooTicker(symbol: String, exchange: String): String {
    val e = exchange.uppercase()
    val suf = when {
        e.startsWith("IBIS") || e == "FWB" || e == "XETRA" -> ".DE"
        e.startsWith("LSE") -> ".L"
        e == "AEB" -> ".AS"
        e == "SBF" -> ".PA"
        e == "EBS" -> ".SW"
        e.startsWith("BVME") -> ".MI"
        e == "BM" -> ".MC"
        else -> ""
    }
    return symbol.uppercase().replace(' ', '-') + suf
}

/** Підсумок синхронізації; gone — назви ETF з IBKR, яких більше немає в рахунку (питаємо, чи прибрати). */
data class IbkrSync(val updated: Int, val added: Int, val cash: Int, val dividends: Int, val gone: List<String>, val skipped: List<String>) {
    val text: String get() = buildList {
        add("Оновлено позицій: $updated" + if (added > 0) ", нових: $added" else "")
        if (cash > 0) add("рахунків з грошима: $cash")
        if (dividends > 0) add("нових дивідендів: $dividends")
        if (skipped.isNotEmpty()) add("пропущено: " + skipped.joinToString(", "))
    }.joinToString(" · ")
}

private fun sameSym(p: Pos, sym: String): Boolean =
    p.group == "ETF" && (p.ibSym.equals(sym, true) || p.ticker.substringBefore('.').equals(sym, true) || p.name.equals(sym, true))

/**
 * Об'єднує дані IBKR із застосунком: ETF оновлюються за символом (кількість, ціна купівлі й поточна у валюті фонду),
 * нові додаються в групу ETF, гроші — рахунками «IBKR USD» тощо (баланс замінюється), дивіденди — доходами без рахунку
 * (гроші вже враховані в балансі IBKR), з ключем, тож не дублюються. Курс купівлі для нових позицій невідомий — береться поточний.
 */
fun applyFlex(d: AppData, f: FlexData, rates: Map<String, Double>, now: Long): Pair<AppData, IbkrSync> {
    var pos = d.pos; var updated = 0; var added = 0; val skipped = mutableListOf<String>()
    f.positions.forEach { ip ->
        if (ip.category !in setOf("STK", "FUND", "ETF", "")) { skipped += "${ip.symbol} (${ip.category})"; return@forEach }
        val rate = rates[ip.cur]; if (rate == null) { skipped += "${ip.symbol} (${ip.cur})"; return@forEach }
        val i = pos.indexOfFirst { sameSym(it, ip.symbol) }
        if (i >= 0) {
            val o = pos[i]; val fx = if (o.cur == ip.cur && o.buyFx > 0 && fxPlausible(o.buyFx, rate)) o.buyFx else rate
            pos = pos.mapIndexed { j, x -> if (j != i) x else o.copy(qty = ip.qty, cur = ip.cur, pxBuy = ip.cost, pxNow = ip.mark, buyFx = fx, buy = ip.cost * fx, now = ip.mark * rate,
                pxAt = now, src = "ibkr", ibSym = ip.symbol, ticker = o.ticker.ifBlank { yahooTicker(ip.symbol, ip.exchange) }) }
            updated++
        } else {
            pos = pos + Pos("ETF", ip.symbol, ip.qty, ip.cost * rate, ip.mark * rate, cur = ip.cur, pxBuy = ip.cost, pxNow = ip.mark, buyFx = rate,
                ticker = yahooTicker(ip.symbol, ip.exchange), pxAt = now, src = "ibkr", ibSym = ip.symbol)
            added++
        }
    }
    val gone = pos.filter { it.src == "ibkr" && f.positions.none { ip -> sameSym(it, ip.symbol) } }.map { it.name }

    var accs = d.accs; var cashN = 0
    f.cash.forEach { (c, v) ->
        if (rates[c] == null) { if (v != 0.0) skipped += "гроші $c"; return@forEach }
        val id = "ibkr-$c"; val i = accs.indexOfFirst { it.id == id }
        accs = if (i >= 0) accs.mapIndexed { j, a -> if (j == i) a.copy(bal = v) else a } else if (v != 0.0) accs + Account("IBKR $c", c, v, id = id) else accs
        if (i >= 0 || v != 0.0) cashN++
    }

    var txs = d.txs; var divN = 0
    val keys = txs.map { it.key }.toHashSet()
    f.dividends.forEach { dv ->
        val rate = rates[dv.cur] ?: run { skipped += "дивіденд ${dv.symbol} (${dv.cur})"; return@forEach }
        val key = "ibkr|div|${dv.id}"; if (key in keys || !isIsoDate(dv.date)) return@forEach
        val net = dv.amount + dv.tax // tax від'ємний
        val desc = "Дивіденди ${dv.symbol}" + if (dv.tax < 0) " (податок ${fmt2(-dv.tax)} ${curSign(dv.cur)})" else ""
        txs = listOf(Tx(desc, "Надходження", "income", net * rate, dv.date, key)) + txs; keys += key; divN++
    }
    return d.copy(pos = pos, accs = accs, txs = txs) to IbkrSync(updated, added, cashN, divN, gone, skipped)
}

/** Прибирає позиції IBKR, яких більше немає в рахунку. */
fun removeGone(d: AppData, names: List<String>): AppData = d.copy(pos = d.pos.filterNot { it.src == "ibkr" && it.name in names })

/** Перевірка Query ID: це число (номер Activity Flex Query), а не номер рахунку U1234567 чи назва запиту. */
fun flexQueryError(q: String): String = when {
    Regex("^[UuFf]\\d+$").matches(q.trim()) -> "Це номер рахунку IBKR, а потрібен номер Flex-запиту: Performance & Reports → Flex Queries, число біля назви вашого Activity Flex Query (кнопка «i» або колонка Query ID)."
    !Regex("^\\d{3,}$").matches(q.trim()) -> "Query ID — це лише цифри, напр. 987654 (номер запиту в списку Flex Queries, не назва)."
    else -> ""
}
