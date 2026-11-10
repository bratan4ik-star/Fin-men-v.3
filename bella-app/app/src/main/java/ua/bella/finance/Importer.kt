package ua.bella.finance

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.time.LocalDate
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory

data class ImportItem(
    val tx: Tx,
    val isDuplicate: Boolean,
    val duplicateReason: String = "",
    val selected: Boolean = !isDuplicate
)

/** Підсумок виписки до імпорту: великі суми видно одразу (доходи / витрати / інвестиції / перекази). */
data class ImportTotals(val income: Double, val expense: Double, val invest: Double, val ownTransfers: Double, val peerTransfers: Double)

/** Категорії переказів між людьми: вони не доходи й не витрати, їх показуємо окремим рядком. */
val PEER_CATS = setOf("Перекази людям", "Перекази на картки (перевірити)", "Перекази від людей")

fun importTotals(txs: List<Tx>): ImportTotals {
    fun s(p: (Tx) -> Boolean) = txs.filter(p).sumOf { it.amt }
    return ImportTotals(
        income = s { it.type == "income" && it.cat !in PEER_CATS },
        expense = s { it.type == "expense" && it.cat !in PEER_CATS },
        invest = s { it.type == "transfer" && it.cat == "Інвестиції" },
        ownTransfers = s { it.type == "transfer" && it.cat != "Інвестиції" },
        peerTransfers = s { it.cat in PEER_CATS },
    )
}

data class ImportResult(val txs: List<Tx>, val accs: List<Account>, val bad: Int, val error: String = "", val approxRates: Int = 0) {
    val info: String get() {
        if (error.isNotEmpty()) return error
        if (txs.isEmpty()) return "Не вдалося розпізнати рядки"
        val ds = txs.map { it.date }.sorted()
        return "Операцій: ${txs.size} · ${ddmmyyyy(ds.first())} – ${ddmmyyyy(ds.last())}" +
            (if (accs.isNotEmpty()) " · карток: ${accs.size}" else "") + (if (bad > 0) " · пропущено: $bad" else "")
    }
    val totals: ImportTotals get() = importTotals(txs)
    val info2: String get() {
        val tr = txs.count { it.type == "transfer" }
        return if (tr > 0) "Між власними картками та заощадженнями: $tr. Вони не рахуються доходом чи витратою." else ""
    }
}

/** Нормалізація рядка опису для нечутливого до регістру та розділових знаків порівняння. */
fun normDesc(s: String): String =
    s.lowercase()
        .replace(Regex("[^a-zа-яіїєґ0-9]"), "")
        .trim()

/**
 * Розумний пошук дублікатів операцій при періодичному імпорті:
 * 1. Збіг за точним технічним ключем виписки (key).
 * 2. Збіг за комбінацією: однакова дата, однаковий тип (income/expense), сума (з точністю до копійки),
 *    і схожий/ідентичний нормалізований опис.
 * 3. Запобігає дублюванню однакових рядків всередині одного файлу.
 */
fun detectDuplicates(newTxs: List<Tx>, existingTxs: List<Tx>): List<ImportItem> {
    val existingKeys = existingTxs.mapNotNull { it.key.ifEmpty { null } }.toSet()
    val existingSignatures = existingTxs.map { tx ->
        Triple(tx.date, Math.round(tx.amt * 100) / 100.0, tx.type) to normDesc(tx.desc)
    }

    val seenInBatchKeys = mutableSetOf<String>()
    val seenInBatchSig = mutableListOf<Pair<Triple<String, Double, String>, String>>()

    return newTxs.map { tx ->
        val amtRounded = Math.round(tx.amt * 100) / 100.0
        val sig = Triple(tx.date, amtRounded, tx.type)
        val dNorm = normDesc(tx.desc)

        if (tx.key.isNotEmpty() && tx.key in existingKeys) {
            ImportItem(tx, isDuplicate = true, duplicateReason = "Точний ключ операції вже є у базі", selected = false)
        } else if (tx.key.isNotEmpty() && !seenInBatchKeys.add(tx.key)) {
            ImportItem(tx, isDuplicate = true, duplicateReason = "Повтор рядка у файлі виписки", selected = false)
        } else {
            val match = existingSignatures.firstOrNull { (s, desc) ->
                s == sig && (desc == dNorm || (desc.isNotEmpty() && dNorm.isNotEmpty() && (desc.contains(dNorm) || dNorm.contains(desc) || desc.take(10) == dNorm.take(10))))
            }
            if (match != null) {
                ImportItem(tx, isDuplicate = true, duplicateReason = "Вже збережено: ${tx.date} на ${fmt2(amtRounded)} ₴", selected = false)
            } else {
                val batchMatch = seenInBatchSig.firstOrNull { (s, desc) ->
                    s == sig && (desc == dNorm || (desc.isNotEmpty() && dNorm.isNotEmpty() && (desc.contains(dNorm) || dNorm.contains(desc))))
                }
                if (batchMatch != null) {
                    ImportItem(tx, isDuplicate = true, duplicateReason = "Повторюється у виписці", selected = false)
                } else {
                    seenInBatchSig.add(sig to dNorm)
                    ImportItem(tx, isDuplicate = false, duplicateReason = "", selected = true)
                }
            }
        }
    }
}

object Importer {
    private val CMAP = mapOf(
        "Супермаркети та продукти" to "Харчування", "Ресторани, кафе, бари" to "Харчування", "Таксі" to "Транспорт", "Авто" to "Транспорт",
        "АЗС" to "Транспорт", "Квитки на поїзд" to "Транспорт", "Аптеки" to "Здоров'я", "Медичні послуги" to "Здоров'я", "Краса" to "Здоров'я",
        "Комуналка та Інтернет" to "Житло", "Дім та ремонт" to "Житло", "Цифрові товари" to "Розваги",
    )
    /**
     * Тип операції: переказ між власними рахунками чи «Заощадження» — переказ, але купон і відсотки
     * (надходження з категорії «Заощадження» з «купон» / «%» / «відсот» в описі) — це дохід.
     */
    fun classifyType(cat: String, desc: String, a: Double): String {
        if (cat == "Заощадження" && a > 0) {
            val d = desc.lowercase()
            if (d.contains("купон") && !d.contains("номінал") || d.contains("%") || d.contains("відсот") || d.contains("проценти")) return "income"
        }
        return if (cat in OWN) "transfer" else if (a < 0) "expense" else "income"
    }
    private val OWN = setOf("Переказ на свою картку", "Зарахування зі своєї картки", "Заощадження")

    private const val ERR_NOT_XLSX = "Це не справжній файл .xlsx (можливо, .xls або HTML). Збережіть виписку в банку саме як Excel (.xlsx)"
    private const val ERR_NO_HEADER = "Не знайшов у виписці колонки «Дата» та «Сума» (або «Дебет/Кредит»)"

    /** Файл .xlsx це zip-архів: розпізнаємо за сигнатурою «PK», а не за назвою файлу. */
    fun isZip(b: ByteArray) = b.size > 3 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()

    /** UTF-8, а якщо там «биті» символи (типово для банківських CSV) — windows-1251. Без BOM. */
    fun decodeText(b: ByteArray): String {
        val u = b.toString(Charsets.UTF_8)
        val t = if (u.contains('�')) b.toString(charset("windows-1251")) else u
        return t.removePrefix("﻿")
    }

    private val DATE_DMY = Regex("^(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4})")
    private val DATE_YMD = Regex("^(\\d{4})-(\\d{2})-(\\d{2})")

    private fun iso(y: String, m: String, d: String): String? {
        val mm = m.toInt(); val dd = d.toInt()
        if (mm !in 1..12 || dd !in 1..31) return null
        return "%s-%02d-%02d".format(y, mm, dd)
    }

    /** Розпізнає дату: 01.11.2026, 01/11/2026 14:05, 2026-11-01T…, а також серійне число Excel. */
    fun toIso(raw: String): String? {
        val s = raw.trim()
        DATE_DMY.find(s)?.let { return iso(it.groupValues[3], it.groupValues[2], it.groupValues[1]) }
        DATE_YMD.find(s)?.let { return iso(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
        val n = s.replace(',', '.').toDoubleOrNull() ?: return null
        if (n < 20000 || n > 80000) return null
        return LocalDate.of(1899, 12, 30).plusDays(n.toLong()).toString()
    }

    private val UAH_MARK = Regex("грн\\.?|₴|uah", RegexOption.IGNORE_CASE)

    /**
     * Число з виписки: пробіли й нерозривні пробіли, юнікодний мінус, позначка гривні («грн», «₴», «UAH»).
     * Якщо є і крапка, і кома, десятковий роздільник — той, що останній: 1,234.50 і 1.234,50 — це 1234,5.
     * Кілька однакових роздільників — розряди (1.234.567 або 1,234,567); одна кома — десяткова.
     */
    fun num(raw: String): Double? {
        var s = UAH_MARK.replace(raw, "").replace(Regex("[\\s\\u00A0\\u202F]"), "").replace('−', '-').replace('–', '-')
        if (s.isEmpty()) return null
        val c = s.count { it == ',' }; val d = s.count { it == '.' }
        s = when {
            c > 0 && d > 0 -> if (s.lastIndexOf(',') > s.lastIndexOf('.')) s.replace(".", "").replace(',', '.') else s.replace(",", "")
            c > 1 -> s.replace(",", "")
            d > 1 -> s.replace(".", "")
            else -> s.replace(',', '.')
        }
        return s.toDoubleOrNull()
    }

    /** CSV: дата; опис; сума. Від'ємна сума — витрата. Суми у файлі вважаються в гривнях. */
    fun parseCsv(text: String, curRate: Double, rules: List<CatRule> = DEFAULT_RULES): ImportResult {
        val out = mutableListOf<Tx>(); var bad = 0
        text.removePrefix("﻿").lines().forEach { raw ->
            val l = raw.trim(); if (l.isEmpty()) return@forEach
            val p = l.split(if (l.contains(';')) ';' else ',').map { it.trim().trim('"') }
            if (p.size < 3) { bad++; return@forEach }
            val d = toIso(p[0])
            val a = num(p.last())
            if (d == null || a == null || a == 0.0) { bad++; return@forEach }
            val desc = p.drop(1).dropLast(1).joinToString(" ").trim()
            val c = ruleFor(desc, rules); val inc = a > 0
            out.add(Tx(desc, c ?: if (inc) "Зарплата" else "Інше", if (inc) "income" else "expense", Math.abs(a) * curRate, d, "$d|${desc.replace('|', '/')}|$a"))
        }
        return ImportResult(out, emptyList(), bad)
    }

    // ---------- .xlsx (без зовнішніх бібліотек: zip + SAX) ----------

    private val SHEET_NAME = Regex("^xl/worksheets/[^/]+\\.xml$")

    private fun localName(q: String) = q.substringAfter(':')

    private fun saxParse(bytes: ByteArray, h: DefaultHandler) {
        val f = SAXParserFactory.newInstance()
        // Файл ненадійний: забороняємо DOCTYPE/зовнішні сутності (XXE), якщо парсер це підтримує.
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) {}
        f.newSAXParser().parse(ByteArrayInputStream(bytes), h)
    }

    private abstract class SafeHandler : DefaultHandler() {
        override fun resolveEntity(publicId: String?, systemId: String?) = InputSource(StringReader(""))
    }

    private class SstHandler : SafeHandler() {
        val list = ArrayList<String>()
        private val sb = StringBuilder(); private var inT = false; private var ph = 0
        override fun startElement(u: String?, l: String?, q: String, a: Attributes?) {
            when (localName(q)) { "si" -> sb.setLength(0); "t" -> inT = true; "rPh" -> ph++ }
        }
        override fun characters(ch: CharArray, s: Int, n: Int) { if (inT && ph == 0) sb.append(ch, s, n) }
        override fun endElement(u: String?, l: String?, q: String) {
            when (localName(q)) { "t" -> inT = false; "rPh" -> ph--; "si" -> list.add(sb.toString()) }
        }
    }

    private fun colIndex(letters: String): Int = letters.fold(0) { acc, c -> acc * 26 + (c.uppercaseChar() - 'A' + 1) } - 1
    private fun colName(i: Int): String { var n = i + 1; val sb = StringBuilder(); while (n > 0) { n--; sb.insert(0, 'A' + n % 26); n /= 26 }; return sb.toString() }

    private class SheetHandler(val sst: List<String>) : SafeHandler() {
        val rows = ArrayList<Map<String, String>>()
        private var cur: HashMap<String, String>? = null
        private var col = ""; private var next = 0; private var type = ""
        private val v = StringBuilder(); private val t = StringBuilder()
        private var inV = false; private var inT = false; private var inIs = false; private var ph = 0
        override fun startElement(u: String?, l: String?, q: String, a: Attributes) {
            when (localName(q)) {
                "row" -> { cur = HashMap(); next = 0 }
                "c" -> {
                    val r = a.getValue("r")?.takeWhile { it.isLetter() }.orEmpty()
                    if (r.isNotEmpty()) next = colIndex(r)
                    col = colName(next); next++
                    type = a.getValue("t") ?: ""; v.setLength(0); t.setLength(0)
                }
                "v" -> { inV = true; v.setLength(0) }
                "is" -> inIs = true
                "t" -> if (inIs) inT = true
                "rPh" -> ph++
            }
        }
        override fun characters(ch: CharArray, s: Int, n: Int) {
            if (inV) v.append(ch, s, n)
            if (inT && ph == 0) t.append(ch, s, n)
        }
        override fun endElement(u: String?, l: String?, q: String) {
            when (localName(q)) {
                "v" -> inV = false
                "t" -> inT = false
                "is" -> inIs = false
                "rPh" -> ph--
                "c" -> {
                    val value = when (type) {
                        "s" -> sst.getOrElse(v.toString().trim().toIntOrNull() ?: -1) { "" }
                        "inlineStr" -> t.toString()
                        else -> v.toString().trim()
                    }
                    if (value.isNotEmpty()) cur?.put(col, value)
                }
                "row" -> { cur?.let { if (it.isNotEmpty()) rows.add(it) }; cur = null }
            }
        }
    }

    /**
     * Читає архів через центральний каталог (ZipFile), а не потоково: деякі банки пишуть xlsx з нульовими
     * розмірами в локальних заголовках, і ZipInputStream на них падає («invalid entry size»).
     */
    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val m = LinkedHashMap<String, ByteArray>()
        val tmp = File.createTempFile("bella-import", ".xlsx")
        try {
            tmp.writeBytes(bytes)
            ZipFile(tmp).use { z ->
                for (e in z.entries()) {
                    val n = e.name.replace('\\', '/').trimStart('/').lowercase()
                    if (!e.isDirectory && (n == "xl/sharedstrings.xml" || SHEET_NAME.matches(n))) m[n] = z.getInputStream(e).use { it.readBytes() }
                }
            }
        } finally { tmp.delete() }
        return m
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim().trimEnd(':')

    /** Колонка за точною назвою (у порядку пріоритету), а якщо немає — за входженням підрядка. */
    private fun pick(h: Map<String, String>, exact: List<String>, contains: List<String> = emptyList()): String? {
        for (e in exact) h.entries.firstOrNull { it.value == e }?.let { return it.key }
        for (c in contains) h.entries.firstOrNull { it.value.contains(c) }?.let { return it.key }
        return null
    }

    private class Cols(
        val date: String, val desc: String?, val amt: String?, val debit: String?, val credit: String?,
        val card: String?, val cur: String?, val cat: String?, val balCur: String?, val bal: String?,
    )

    private fun findCols(row: Map<String, String>): Cols? {
        val h = row.mapValues { norm(it.value) }
        val date = pick(h, listOf("дата", "дата операції", "дата і час операції", "дата та час", "date"), listOf("дата", "date")) ?: return null
        val amt = pick(h, listOf("сума в валюті картки", "сума в валюті рахунку", "сума операції", "сума", "amount"), listOf("сума", "amount"))
        val debit = pick(h, listOf("дебет", "debit", "витрати"), listOf("дебет", "debit"))
        val credit = pick(h, listOf("кредит", "credit", "надходження"), listOf("кредит", "credit"))
        if (amt == null && debit == null && credit == null) return null
        return Cols(
            date, pick(h, listOf("опис операції", "опис", "призначення платежу", "призначення", "деталі операції", "деталі", "description", "details"),
                listOf("опис", "призначен", "деталі", "description", "details", "контрагент")),
            amt, debit, credit,
            pick(h, listOf("картка", "номер картки", "card")),
            pick(h, listOf("валюта картки", "валюта рахунку", "валюта", "currency")),
            pick(h, listOf("категорія", "category"), listOf("категор")),
            pick(h, listOf("валюта залишку")),
            pick(h, listOf("залишок на кінець періоду"), listOf("залишок на кінець")),
        )
    }

    private val INVEST_RE = Regex("облігац|ovdp|овдп|інвестиційний капітал|\\bicu\\b|interactive brokers|ibkr", RegexOption.IGNORE_CASE)
    private val REDEEM_RE = Regex("номінал|погашен", RegexOption.IGNORE_CASE)
    private val COUPON_RE = Regex("купон|відсот|%", RegexOption.IGNORE_CASE)
    private val ZNYSH_RE = Regex("знищ|znysh", RegexOption.IGNORE_CASE)
    private val DV_RE = Regex("(?<!\\p{L})(дв|dv)(?!\\p{L})|додатков", RegexOption.IGNORE_CASE)
    private val SALARY_RE = Regex("hroshove|грошове забезпечення|зарплата", RegexOption.IGNORE_CASE)
    private val INTEREST_RE = Regex("% за депозитом|купон|відсот", RegexOption.IGNORE_CASE)
    /** Переказ від людини латиницею: «SEMCHII ANDRII», «VIKTOR CHICHIHIN, KYIV», необов'язково з префіксом «від». */
    private val LATIN_PERSON_RE = Regex("^(від\\s+)?[A-Z][A-Z'’-]+(\\s+[A-Z][A-Z'’-]+)+(,\\s*[A-Z][A-Z \\-]+)?$")

    /**
     * Виписка ПриватБанку: інвестиції — не витрати, спецдоходи за коментарем, перекази людям і на картки.
     * Повертає (тип, категорія) або null, якщо правило не спрацювало (тоді — звичайна класифікація).
     */
    fun classifyPrivat(cat: String, desc: String, a: Double): Pair<String, String>? {
        if (INVEST_RE.containsMatchIn(desc) || INVEST_RE.containsMatchIn(cat)) {
            val coupon = a > 0 && COUPON_RE.containsMatchIn(desc) && !REDEEM_RE.containsMatchIn(desc)
            if (!coupon) return "transfer" to "Інвестиції"
        }
        if (a > 0) {
            // відсотки й купони з накопичувальних рахунків — окрема категорія (інвестиційний дохід у звіті)
            if (cat == "Заощадження" && INTEREST_RE.containsMatchIn(desc)) return "income" to "Відсотки та купони"
            if (cat == "Зарахування переказу" || cat == "Перекази" || LATIN_PERSON_RE.matches(desc.trim())) return "income" to "Перекази від людей"
            val c = when {
                ZNYSH_RE.containsMatchIn(desc) -> "За знищену техніку"
                DV_RE.containsMatchIn(desc) -> "Бойові (ДВ)"
                SALARY_RE.containsMatchIn(desc) -> "Грошове забезпечення"
                desc.contains("Соціальні", ignoreCase = true) -> "Соціальні виплати"
                else -> null
            }
            if (c != null) return "income" to c
        } else if (cat == "Перекази") {
            return "expense" to (if (desc.trim().equals("На картку", ignoreCase = true)) "Перекази на картки (перевірити)" else "Перекази людям")
        }
        return null
    }

    /** Курс на дату операції: з архіву НБУ, інакше поточний (тоді [approx] = true). */
    fun rateOn(cur: String, iso: String, archive: Map<String, Map<String, Double>>): Pair<Double, Boolean> {
        if (cur == "UAH") return 1.0 to false
        archive[iso]?.get(cur)?.let { return it to false }
        return (RATES[cur] ?: 1.0) to true
    }

    internal fun readRows(rows: List<Map<String, String>>, rules: List<CatRule>, archive: Map<String, Map<String, Double>> = emptyMap()): ImportResult? {
        val hi = rows.indexOfFirst { findCols(it) != null }
        if (hi < 0) return null
        val c = findCols(rows[hi]) ?: return null
        val txs = mutableListOf<Tx>(); val accs = mutableListOf<Account>(); val seen = HashSet<String>(); var bad = 0; var approx = 0
        rows.drop(hi + 1).forEach { r ->
            fun g(col: String?) = col?.let { r[it] } ?: ""
            val dRaw = g(c.date); val iso = toIso(dRaw)
            val a = if (c.amt != null) num(g(c.amt)) else {
                val d = num(g(c.debit)); val cr = num(g(c.credit))
                if (d == null && cr == null) null else Math.abs(cr ?: 0.0) - Math.abs(d ?: 0.0)
            }
            if (iso == null || a == null || a == 0.0) { bad++; return@forEach }
            val card = g(c.card); val cur = g(c.cur).uppercase().ifEmpty { "UAH" }; val cat = g(c.cat)
            val desc = g(c.desc).ifEmpty { cat }.ifEmpty { "Операція" }
            val special = classifyPrivat(cat, desc, a)
            val (type, c1) = special ?: run {
                val t = classifyType(cat, desc, a)
                t to (if (t == "income" && cat.startsWith("Зарахування")) "Надходження" else (CMAP[cat] ?: cat.ifEmpty { "Інше" }))
            }
            // правило користувача важливіше за категорію банку; перекази й спецкатегорії не чіпаємо
            val c2 = if (type == "transfer" || special != null) c1 else ruleFor(desc, rules) ?: c1
            val (rate, isApprox) = rateOn(cur, iso, archive)
            if (isApprox) approx++
            txs.add(Tx(desc, c2, type, Math.abs(a) * rate, iso, "$dRaw|$card|$a|${desc.replace('|', '/')}", accAmt = Math.abs(a)))
            val last4 = card.takeLast(4)
            if (last4.isNotEmpty() && seen.add(last4)) {
                val bc = g(c.balCur).uppercase().ifEmpty { cur }
                accs.add(Account("Картка •$last4", if (RATES.containsKey(bc)) bc else if (RATES.containsKey(cur)) cur else "UAH", num(g(c.bal)) ?: 0.0, card = last4))
            }
        }
        return ImportResult(txs, accs, bad, approxRates = approx)
    }

    fun parseXlsx(ins: InputStream): ImportResult = parseXlsx(ins.readBytes())

    fun parseXlsx(bytes: ByteArray, rules: List<CatRule> = DEFAULT_RULES, archive: Map<String, Map<String, Double>> = emptyMap()): ImportResult = try {
        val files = readZip(bytes)
        val sheets = files.keys.filter { SHEET_NAME.matches(it) }.sortedWith(compareBy({ it.length }, { it }))
        if (sheets.isEmpty()) ImportResult(emptyList(), emptyList(), 0, ERR_NOT_XLSX)
        else {
            val sst = SstHandler().also { h -> files["xl/sharedstrings.xml"]?.let { saxParse(it, h) } }.list
            var res: ImportResult? = null
            for (s in sheets) {
                val h = SheetHandler(sst).also { saxParse(files.getValue(s), it) }
                res = readRows(h.rows, rules, archive)
                if (res != null) break
            }
            res ?: ImportResult(emptyList(), emptyList(), 0, ERR_NO_HEADER)
        }
    } catch (e: java.util.zip.ZipException) {
        ImportResult(emptyList(), emptyList(), 0, ERR_NOT_XLSX)
    } catch (e: org.xml.sax.SAXException) {
        ImportResult(emptyList(), emptyList(), 0, "Файл пошкоджений або має незвичний формат (${e.javaClass.simpleName})")
    }

    /** Точка входу для UI: сама визначає тип (xlsx за сигнатурою, інакше CSV). */
    fun parseBytes(b: ByteArray, curRate: Double, rules: List<CatRule> = DEFAULT_RULES, archive: Map<String, Map<String, Double>> = emptyMap()): ImportResult =
        if (isZip(b)) parseXlsx(b, rules, archive) else parseCsv(decodeText(b), curRate, rules)
}
