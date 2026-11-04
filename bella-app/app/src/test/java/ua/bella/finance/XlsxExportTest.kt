package ua.bella.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class XlsxExportTest {
    private val d = AppData(
        txs = listOf(
            Tx("Зарплата <i>&", "Зарплата", "income", 10000.0, "2026-11-01", "", "m", 10000.0),
            Tx("Оренда", "Житло", "expense", 6000.0, "2026-11-02", "", "m", 6000.0),
            Tx("Кава", "Харчування", "expense", 100.0, "2026-10-15"),
            Tx("Переказ", "Перекази", "transfer", 4000.0, "2026-11-03", "", "m", 4000.0, "u", 100.0),
        ),
        accs = listOf(Account("Monobank", "UAH", 0.0, id = "m"), Account("Готівка USD", "USD", 0.0, cash = true, id = "u")),
        debts = emptyList(), pos = emptyList(), goals = emptyList(), demo = false,
    )

    private fun entries(b: ByteArray): Map<String, String> {
        val m = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(b)).use { z -> generateSequence { z.nextEntry }.forEach { m[it.name] = z.readBytes().toString(Charsets.UTF_8) } }
        return m
    }

    @Test fun packageIsWellFormed() {
        val e = entries(buildXlsx(d))
        assertEquals(setOf("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml", "xl/worksheets/sheet3.xml"), e.keys)
        e.values.forEach { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(it.toByteArray())) } // кожен XML коректний
        File("build/test-xlsx").mkdirs(); File("build/test-xlsx/sample.xlsx").writeBytes(buildXlsx(d))
    }

    @Test fun amountsAndEscaping() {
        val s1 = entries(buildXlsx(d))["xl/worksheets/sheet1.xml"]!!
        assertTrue(s1.contains("Зарплата &lt;i&gt;&amp;"))
        assertTrue(s1.contains("<v>10000.00</v>") && s1.contains("<v>-6000.00</v>") && s1.contains("<v>-100.00</v>"))
        assertTrue(s1.contains("Monobank → Готівка USD"))
        // сортування: найновіша операція першою (переказ 2026-11-03)
        assertTrue(s1.indexOf("2026-11-03") < s1.indexOf("2026-11-01"))
    }

    @Test fun monthlyAndCategorySheets() {
        val s2 = entries(buildXlsx(d))["xl/worksheets/sheet2.xml"]!!
        assertTrue(s2.contains("2026-10") && s2.contains("2026-11"))
        assertTrue(s2.contains("<v>10000.00</v>") && s2.contains("<v>6100.00</v>") && s2.contains("<v>3900.00</v>")) // переказ не входить
        val s3 = entries(buildXlsx(d))["xl/worksheets/sheet3.xml"]!!
        assertTrue(s3.indexOf("Житло") < s3.indexOf("Харчування")) // за спаданням витрат
    }

    @Test fun emptyDataStillValid() {
        entries(buildXlsx(AppData(txs = emptyList(), accs = emptyList(), debts = emptyList(), pos = emptyList(), goals = emptyList(), demo = false)))
            .values.forEach { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(it.toByteArray())) }
    }

    @Test fun controlCharsAreDropped() {
        val s1 = entries(buildXlsx(d.copy(txs = listOf(Tx("a\u0001b", "Інше", "expense", 1.0, "2026-11-01")))))["xl/worksheets/sheet1.xml"]!!
        assertTrue(s1.contains(">ab<"))
        assertEquals("bella-operations-2026-11-04.xlsx", xlsxFileName("2026-11-04"))
    }
}
