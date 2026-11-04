package ua.bella.finance

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/*
 * Експорт у Excel (.xlsx) без сторонніх бібліотек: ZIP з OpenXML. Три аркуші:
 * «Операції» (усі операції з рахунком і сумами), «По місяцях» (доходи, витрати, баланс),
 * «По категоріях» (витрати за весь період). Суми — числа (₴), переказ не входить у доходи й витрати.
 */

private fun esc(s: String): String {
    val b = StringBuilder(s.length)
    for (c in s) when {
        c == '&' -> b.append("&amp;"); c == '<' -> b.append("&lt;"); c == '>' -> b.append("&gt;"); c == '"' -> b.append("&quot;")
        c == '\t' || c == '\n' || c == '\r' || c >= ' ' -> b.append(c) // службові символи, заборонені в XML, відкидаються
    }
    return b.toString()
}

private fun col(i: Int): String { var n = i; val sb = StringBuilder(); do { sb.insert(0, ('A' + n % 26)); n = n / 26 - 1 } while (n >= 0); return sb.toString() }

/** Клітинка: текст, число або порожня. Стиль 1 — жирний заголовок, 2 — число з двома знаками. */
private sealed class Cell { class S(val v: String, val bold: Boolean = false) : Cell(); class N(val v: Double) : Cell(); object E : Cell() }

private fun sheet(rows: List<List<Cell>>, widths: List<Int>): String {
    val b = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
    b.append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>")
    b.append("<cols>"); widths.forEachIndexed { i, w -> b.append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""") }; b.append("</cols><sheetData>")
    rows.forEachIndexed { r, cells ->
        b.append("""<row r="${r + 1}">""")
        cells.forEachIndexed { c, cell ->
            val ref = col(c) + (r + 1)
            when (cell) {
                is Cell.S -> b.append("""<c r="$ref" t="inlineStr"${if (cell.bold) " s=\"1\"" else ""}><is><t xml:space="preserve">${esc(cell.v)}</t></is></c>""")
                is Cell.N -> b.append("""<c r="$ref" s="2"><v>${java.math.BigDecimal(cell.v).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()}</v></c>""")
                Cell.E -> {}
            }
        }
        b.append("</row>")
    }
    return b.append("</sheetData></worksheet>").toString()
}

private fun head(vararg h: String): List<Cell> = h.map { Cell.S(it, true) }

fun buildXlsx(d: AppData): ByteArray {
    val accName = d.accs.associate { it.id to it.name }
    val accCur = d.accs.associate { it.id to it.cur }
    val txs = d.txs.sortedByDescending { it.date }

    val ops = mutableListOf(head("Дата", "Тип", "Категорія", "Опис", "Рахунок", "Сума, ₴", "Сума у валюті рахунку", "Валюта рахунку"))
    txs.forEach { t ->
        val sign = when (t.type) { "income" -> 1.0; "expense" -> -1.0; else -> 0.0 }
        val acc = if (t.type == "transfer") listOfNotNull(accName[t.acc], accName[t.toAcc]).joinToString(" → ") else accName[t.acc] ?: ""
        ops += listOf(
            Cell.S(t.date), Cell.S(when (t.type) { "income" -> "Дохід"; "expense" -> "Витрата"; else -> "Переказ" }), Cell.S(t.cat), Cell.S(t.desc), Cell.S(acc),
            Cell.N(if (t.type == "transfer") t.amt else sign * t.amt),
            if (t.accAmt != 0.0) Cell.N(if (t.type == "transfer") t.accAmt else sign * t.accAmt) else Cell.E, Cell.S(accCur[t.acc] ?: ""),
        )
    }

    val byMonth = txs.groupBy { it.date.take(7) }.toSortedMap()
    val months = mutableListOf(head("Місяць", "Доходи, ₴", "Витрати, ₴", "Баланс, ₴"))
    byMonth.forEach { (m, l) ->
        val i = l.filter { it.type == "income" }.sumOf { it.amt }; val e = l.filter { it.type == "expense" }.sumOf { it.amt }
        months += listOf(Cell.S(m), Cell.N(i), Cell.N(e), Cell.N(i - e))
    }
    val ti = txs.filter { it.type == "income" }.sumOf { it.amt }; val te = txs.filter { it.type == "expense" }.sumOf { it.amt }
    months += listOf(Cell.S("Разом", true), Cell.N(ti), Cell.N(te), Cell.N(ti - te))

    val cats = txs.filter { it.type == "expense" }.groupBy { it.cat }.mapValues { e -> e.value.sumOf { it.amt } }.entries.sortedByDescending { it.value }
    val catRows = mutableListOf(head("Категорія", "Витрати, ₴", "Частка, %"))
    cats.forEach { (c, v) -> catRows += listOf(Cell.S(c), Cell.N(v), Cell.N(if (te > 0) v / te * 100 else 0.0)) }

    val names = listOf("Операції", "По місяцях", "По категоріях")
    val sheets = listOf(sheet(ops, listOf(12, 10, 20, 34, 24, 14, 22, 14)), sheet(months, listOf(12, 16, 16, 16)), sheet(catRows, listOf(24, 16, 12)))
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { z ->
        fun put(n: String, s: String) { z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray(Charsets.UTF_8)); z.closeEntry() }
        val ns = "http://schemas.openxmlformats.org/"
        put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="${ns}package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>${names.indices.joinToString("") { """<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" }}</Types>""")
        put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="${ns}package/2006/relationships"><Relationship Id="rId1" Type="${ns}officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
        put("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="${ns}spreadsheetml/2006/main" xmlns:r="${ns}officeDocument/2006/relationships"><sheets>${names.mapIndexed { i, n -> """<sheet name="${esc(n)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""" }.joinToString("")}</sheets></workbook>""")
        put("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="${ns}package/2006/relationships">${names.indices.joinToString("") { """<Relationship Id="rId${it + 1}" Type="${ns}officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""" }}<Relationship Id="rId${names.size + 1}" Type="${ns}officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
        put("xl/styles.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="${ns}spreadsheetml/2006/main"><numFmts count="1"><numFmt numFmtId="164" formatCode="#,##0.00"/></numFmts><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="3"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/><xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""")
        sheets.forEachIndexed { i, s -> put("xl/worksheets/sheet${i + 1}.xml", s) }
    }
    return out.toByteArray()
}

fun xlsxFileName(date: String): String = "bella-operations-$date.xlsx"
