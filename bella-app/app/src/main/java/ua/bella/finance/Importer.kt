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

data class ImportResult(val txs: List<Tx>, val accs: List<Account>, val bad: Int, val error: String = "") {
    val info: String get() {
        if (error.isNotEmpty()) return error
        if (txs.isEmpty()) return "Не вдалося розпізнати рядки"
        val ds = txs.map { it.date }.sorted()
        return "Операцій: ${txs.size} · ${ddmmyyyy(ds.first())} – ${ddmmyyyy(ds.last())}" +
            (if (accs.isNotEmpty()) " · карток: ${accs.size}" else "") + (if (bad > 0) " · пропущено: $bad" else "")
    }
    val info2: String get() {
        val tr = txs.count { it.type == "transfer" }
        return if (tr > 0) "Між власними картками та заощадженнями: $tr. Вони не рахуються доходом чи витратою." else ""
    }
}

object Importer {
    private val CAT = listOf(
        Regex("сільпо|атб|novus|ашан|silpo|продукт", RegexOption.IGNORE_CASE) to "Харчування",
        Regex("uber|bolt|uklon|метро|таксі|wog|okko", RegexOption.IGNORE_CASE) to "Транспорт",
        Regex("netflix|кіно|spotify|steam", RegexOption.IGNORE_CASE) to "Розваги",
        Regex("аптек|лікар|apteka", RegexOption.IGNORE_CASE) to "Здоров'я",
        Regex("оренд", RegexOption.IGNORE_CASE) to "Житло",
        Regex("зарплат|salary|фріланс", RegexOption.IGNORE_CASE) to "Зарплата",
    )
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

    /** Число з пробілами/нерозривними пробілами, комою як десятковим роздільником, юнікодним мінусом. */
    fun num(raw: String): Double? {
        var s = raw.replace(Regex("[\\s\\u00A0\\u202F]"), "").replace('−', '-').replace('–', '-')
        if (s.isEmpty()) return null
        s = if (s.contains(',') && s.contains('.')) s.replace(",", "") else s.replace(',', '.')
        return s.toDoubleOrNull()
    }

    /** CSV: дата; опис; сума. Від'ємна сума — витрата. Суми у файлі вважаються в гривнях. */
    fun parseCsv(text: String, curRate: Double): ImportResult {
        val out = mutableListOf<Tx>(); var bad = 0
        text.removePrefix("﻿").lines().forEach { raw ->
            val l = raw.trim(); if (l.isEmpty()) return@forEach
            val p = l.split(if (l.contains(';')) ';' else ',').map { it.trim().trim('"') }
            if (p.size < 3) { bad++; return@forEach }
            val d = toIso(p[0])
            val a = num(p.last())
            if (d == null || a == null) { bad++; return@forEach }
            val desc = p.drop(1).dropLast(1).joinToString(" ").trim()
            val c = CAT.firstOrNull { it.first.containsMatchIn(desc) }; val inc = a > 0
            out.add(Tx(desc, c?.second ?: if (inc) "Зарплата" else "Інше", if (inc) "income" else "expense", Math.abs(a) * curRate, d, "$d|$desc|$a"))
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

    private fun readRows(rows: List<Map<String, String>>): ImportResult? {
        val hi = rows.indexOfFirst { findCols(it) != null }
        if (hi < 0) return null
        val c = findCols(rows[hi])!!
        val txs = mutableListOf<Tx>(); val accs = mutableListOf<Account>(); val seen = HashSet<String>(); var bad = 0
        rows.drop(hi + 1).forEach { r ->
            fun g(col: String?) = col?.let { r[it] } ?: ""
            val dRaw = g(c.date); val iso = toIso(dRaw)
            val a = if (c.amt != null) num(g(c.amt)) else {
                val d = num(g(c.debit)); val cr = num(g(c.credit))
                if (d == null && cr == null) null else Math.abs(cr ?: 0.0) - Math.abs(d ?: 0.0)
            }
            if (iso == null || a == null) { bad++; return@forEach }
            val card = g(c.card); val cur = g(c.cur).uppercase().ifEmpty { "UAH" }; val cat = g(c.cat)
            val desc = g(c.desc).ifEmpty { cat }.ifEmpty { "Операція" }
            val type = classifyType(cat, desc, a)
            val c2 = if (type == "income" && cat.startsWith("Зарахування")) "Надходження" else (CMAP[cat] ?: cat.ifEmpty { "Інше" })
            txs.add(Tx(desc, c2, type, Math.abs(a) * (RATES[cur] ?: 1.0), iso, "$dRaw|$card|$a|$desc"))
            if (card.isNotEmpty() && seen.add(card)) {
                val bc = g(c.balCur).uppercase().ifEmpty { cur }
                accs.add(Account("Картка •" + card.takeLast(4), if (RATES.containsKey(bc)) bc else if (RATES.containsKey(cur)) cur else "UAH", num(g(c.bal)) ?: 0.0))
            }
        }
        return ImportResult(txs, accs, bad)
    }

    fun parseXlsx(ins: InputStream): ImportResult = parseXlsx(ins.readBytes())

    fun parseXlsx(bytes: ByteArray): ImportResult = try {
        val files = readZip(bytes)
        val sheets = files.keys.filter { SHEET_NAME.matches(it) }.sortedWith(compareBy({ it.length }, { it }))
        if (sheets.isEmpty()) ImportResult(emptyList(), emptyList(), 0, ERR_NOT_XLSX)
        else {
            val sst = SstHandler().also { h -> files["xl/sharedstrings.xml"]?.let { saxParse(it, h) } }.list
            var res: ImportResult? = null
            for (s in sheets) {
                val h = SheetHandler(sst).also { saxParse(files.getValue(s), it) }
                res = readRows(h.rows)
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
    fun parseBytes(b: ByteArray, curRate: Double): ImportResult =
        if (isZip(b)) parseXlsx(b) else parseCsv(decodeText(b), curRate)
}
