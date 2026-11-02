package ua.bella.finance

import java.io.InputStream
import java.util.zip.ZipInputStream

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
    private val OWN = setOf("Переказ на свою картку", "Зарахування зі своєї картки", "Заощадження")

    /** CSV: дата; опис; сума. Від'ємна сума — витрата. Суми у файлі вважаються в гривнях. */
    fun parseCsv(text: String, curRate: Double): ImportResult {
        val out = mutableListOf<Tx>(); var bad = 0
        text.lines().forEach { raw ->
            val l = raw.trim(); if (l.isEmpty()) return@forEach
            val p = l.split(if (l.contains(';')) ';' else ',')
            if (p.size < 3) { bad++; return@forEach }
            var d = p[0].trim()
            Regex("^(\\d{2})[./](\\d{2})[./](\\d{4})$").find(d)?.let { d = "${it.groupValues[3]}-${it.groupValues[2]}-${it.groupValues[1]}" }
            val a = p.last().replace(Regex("\\s"), "").replace(',', '.').toDoubleOrNull()
            if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(d) || a == null) { bad++; return@forEach }
            val desc = p.drop(1).dropLast(1).joinToString(" ").trim()
            val c = CAT.firstOrNull { it.first.containsMatchIn(desc) }; val inc = a > 0
            out.add(Tx(desc, c?.second ?: if (inc) "Зарплата" else "Інше", if (inc) "income" else "expense", Math.abs(a) * curRate, d, "$d|$desc|$a"))
        }
        return ImportResult(out, emptyList(), bad)
    }

    private fun unesc(x: String) = x.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** Мінімальний читач .xlsx без зовнішніх бібліотек: розпаковує zip, читає sharedStrings і перший аркуш. */
    fun parseXlsx(ins: InputStream): ImportResult {
        val files = HashMap<String, String>()
        ZipInputStream(ins).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (e.name == "xl/sharedStrings.xml" || Regex("^xl/worksheets/sheet\\d+\\.xml$").matches(e.name)) files[e.name] = z.readBytes().toString(Charsets.UTF_8)
                e = z.nextEntry
            }
        }
        val st = Regex("<si>([\\s\\S]*?)</si>").findAll(files["xl/sharedStrings.xml"] ?: "").map { m ->
            unesc(Regex("<t[^>]*>([\\s\\S]*?)</t>").findAll(m.groupValues[1]).joinToString("") { it.groupValues[1] })
        }.toList()
        val sn = files.keys.filter { it.startsWith("xl/worksheets/") }.minOrNull() ?: return ImportResult(emptyList(), emptyList(), 0, "Не вдалося прочитати файл")
        val rows = Regex("<row [^>]*>([\\s\\S]*?)</row>").findAll(files[sn]!!).map { r ->
            val o = HashMap<String, String>()
            Regex("<c r=\"([A-Z]+)\\d+\"([^>]*?)(?:/>|>([\\s\\S]*?)</c>)").findAll(r.groupValues[1]).forEach { c ->
                val body = c.groupValues[3]
                val t = Regex("<t[^>]*>([\\s\\S]*?)</t>").find(body); val v = Regex("<v>([\\s\\S]*?)</v>").find(body)
                o[c.groupValues[1]] = when { t != null -> unesc(t.groupValues[1]); v != null -> if (c.groupValues[2].contains("t=\"s\"")) st.getOrElse(v.groupValues[1].toInt()) { "" } else v.groupValues[1]; else -> "" }
            }
            o
        }.toList()
        val hi = rows.indexOfFirst { it.containsValue("Дата") && it.containsValue("Опис операції") }
        if (hi < 0) return ImportResult(emptyList(), emptyList(), 0, "Не схоже на виписку: немає колонок «Дата» та «Опис операції»")
        val col = rows[hi].entries.associate { it.value to it.key }
        fun g(r: Map<String, String>, nm: String) = col[nm]?.let { r[it] } ?: ""
        val txs = mutableListOf<Tx>(); val accs = mutableListOf<Account>(); val seen = HashSet<String>(); var bad = 0
        rows.drop(hi + 1).forEach { r ->
            val dt = Regex("^(\\d{2})\\.(\\d{2})\\.(\\d{4})").find(g(r, "Дата")); val a = g(r, "Сума в валюті картки").toDoubleOrNull()
            if (dt == null || a == null) { bad++; return@forEach }
            val iso = "${dt.groupValues[3]}-${dt.groupValues[2]}-${dt.groupValues[1]}"
            val card = g(r, "Картка"); val cur = g(r, "Валюта картки").ifEmpty { "UAH" }; val cat = g(r, "Категорія"); val desc = g(r, "Опис операції").ifEmpty { cat }
            val type = if (cat in OWN) "transfer" else if (a < 0) "expense" else "income"
            val c2 = if (type == "income" && cat.startsWith("Зарахування")) "Надходження" else (CMAP[cat] ?: cat.ifEmpty { "Інше" })
            txs.add(Tx(desc, c2, type, Math.abs(a) * (RATES[cur] ?: 1.0), iso, "${g(r, "Дата")}|$card|$a|$desc"))
            if (card.isNotEmpty() && seen.add(card)) {
                val bc = g(r, "Валюта залишку").ifEmpty { cur }
                accs.add(Account("Картка •" + card.takeLast(4), if (RATES.containsKey(bc)) bc else cur, g(r, "Залишок на кінець періоду").toDoubleOrNull() ?: 0.0))
            }
        }
        return ImportResult(txs, accs, bad)
    }
}
