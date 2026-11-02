package ua.bella.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImporterTest {
    private fun xml(s: String) = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>$s"

    private fun cell(ref: String, v: String, inline: Boolean) =
        if (inline) "<c r=\"$ref\" t=\"inlineStr\"><is><t>$v</t></is></c>" else "<c r=\"$ref\"><v>$v</v></c>"

    private fun xlsx(sheetRows: String, shared: List<String>? = null, sheetPath: String = "xl/worksheets/sheet1.xml"): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z ->
            fun put(n: String, c: String) { z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() }
            if (shared != null) put("xl/sharedStrings.xml", xml("<sst>" + shared.joinToString("") { "<si><t>$it</t></si>" } + "</sst>"))
            put(sheetPath, xml("<worksheet><sheetData>$sheetRows</sheetData></worksheet>"))
        }
        return bo.toByteArray()
    }

    @Test fun monobankStyle_titleRow_inlineStrings_datetime() {
        val head = listOf("Дата", "Категорія", "Картка", "Опис операції", "Сума в валюті картки", "Валюта картки", "Залишок на кінець періоду", "Валюта залишку")
        val rows = "<row r=\"1\">${cell("A1", "Історія операцій", true)}</row>" +
            "<row r=\"2\">" + head.mapIndexed { i, h -> cell("${'A' + i}2", h, true) }.joinToString("") + "</row>" +
            "<row r=\"3\">${cell("A3", "01.11.2026 19:34:44", true)}${cell("B3", "Супермаркети та продукти", true)}${cell("C3", "4149 **** **** 6543", true)}" +
            "${cell("D3", "МАГАЗИН", true)}${cell("E3", "-180.0", false)}${cell("F3", "UAH", true)}${cell("G3", "226.9", false)}${cell("H3", "UAH", true)}</row>" +
            "<row r=\"4\">${cell("A4", "31.10.2026 10:00:00", true)}${cell("B4", "Переказ на свою картку", true)}${cell("C4", "4149 **** **** 6543", true)}" +
            "${cell("D4", "На картку", true)}${cell("E4", "-500.0", false)}${cell("F4", "UAH", true)}${cell("G4", "406.9", false)}${cell("H4", "UAH", true)}</row>"
        val r = Importer.parseBytes(xlsx(rows), 1.0)
        assertEquals("", r.error)
        assertEquals(2, r.txs.size)
        assertEquals("2026-11-01", r.txs[0].date); assertEquals("expense", r.txs[0].type); assertEquals("Харчування", r.txs[0].cat); assertEquals(180.0, r.txs[0].amt, 0.001)
        assertEquals("transfer", r.txs[1].type)
        assertEquals(1, r.accs.size); assertEquals("Картка •6543", r.accs[0].name); assertEquals(226.9, r.accs[0].bal, 0.001)
    }

    @Test fun otherBank_sharedStrings_debitCredit_excelSerialDate_otherSheetName() {
        val sst = listOf("Дата операції", "Призначення платежу", "Дебет", "Кредит", "Оренда &amp; комуналка", "Зарплата")
        // 46023 = 2026-01-01 у системі дат Excel
        val rows = "<row><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c><c r=\"C1\" t=\"s\"><v>2</v></c><c r=\"D1\" t=\"s\"><v>3</v></c></row>" +
            "<row><c r=\"A2\"><v>46023</v></c><c r=\"B2\" t=\"s\"><v>4</v></c><c r=\"C2\"><v>7500</v></c></row>" +
            "<row><c r=\"A3\"><v>46024</v></c><c r=\"B3\" t=\"s\"><v>5</v></c><c r=\"D3\"><v>1 000,50</v></c></row>"
        val r = Importer.parseBytes(xlsx(rows, sst, sheetPath = "/xl/worksheets/Sheet1.xml"), 1.0)
        assertEquals("", r.error)
        assertEquals(2, r.txs.size)
        assertEquals("2026-01-01", r.txs[0].date); assertEquals("expense", r.txs[0].type); assertEquals("Оренда & комуналка", r.txs[0].desc)
        assertEquals("income", r.txs[1].type); assertEquals(1000.5, r.txs[1].amt, 0.001)
    }

    @Test fun xlsx_withoutHeader_givesClearError() {
        val r = Importer.parseBytes(xlsx("<row>${cell("A1", "щось інше", true)}</row>"), 1.0)
        assertTrue(r.error.contains("Дата"))
    }

    @Test fun zipThatIsNotXlsx_givesClearError() {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z -> z.putNextEntry(ZipEntry("readme.txt")); z.write("hi".toByteArray()); z.closeEntry() }
        assertTrue(Importer.parseBytes(bo.toByteArray(), 1.0).error.contains(".xlsx"))
    }

    @Test fun csv_windows1251_withTimeInDate() {
        val text = "01.11.2026 14:05;Сільпо;-1250,40\n02.11.2026;Зарплата;45 000"
        val r = Importer.parseBytes(text.toByteArray(charset("windows-1251")), 1.0)
        assertEquals(2, r.txs.size)
        assertEquals("Сільпо", r.txs[0].desc); assertEquals("Харчування", r.txs[0].cat); assertEquals(1250.4, r.txs[0].amt, 0.001)
        assertEquals("income", r.txs[1].type)
    }
}
