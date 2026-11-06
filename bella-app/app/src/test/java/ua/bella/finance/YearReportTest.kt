package ua.bella.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class YearReportTest {
    private val today = LocalDate.parse("2026-11-10")
    private fun t(desc: String, cat: String, type: String, amt: Double, date: String) = Tx(desc, cat, type, amt, date)
    private val txs = listOf(
        t("Зарплата", "Зарплата", "income", 50000.0, "2026-01-05"),
        t("Зарплата", "Зарплата", "income", 50000.0, "2026-11-05"),
        t("Оренда", "Житло", "expense", 18000.0, "2026-01-10"),
        t("Сільпо", "Харчування", "expense", 6000.0, "2026-03-02"),
        t("Сільпо", "Харчування", "expense", 4000.0, "2026-11-08"),
        t("Пізно", "Харчування", "expense", 999.0, "2026-11-20"), // після сьогодні — не входить у поточний рік
        t("Переказ", "Перекази", "transfer", 7000.0, "2026-02-01"),
        t("Купон ОВДП UA4000227045", "Надходження", "income", 1550.0, "2026-06-10"),
        t("Відсотки: Депозит Моно", "Надходження", "income", 770.0, "2026-07-01"),
        t("Дивіденди VWCE", "Надходження", "income", 100.0, "2026-08-01"),
        t("Зарплата", "Зарплата", "income", 40000.0, "2025-01-05"),
        t("Оренда", "Житло", "expense", 20000.0, "2025-06-10"),
        t("Оренда", "Житло", "expense", 5000.0, "2025-12-10"), // пізніше 10.11 — не входить у порівняння
    )

    @Test fun currentYearIsYearToDateAndComparedWithSameDaysLastYear() {
        val p = yearPeriod(2026, today)
        assertEquals("2026-01-01", p.from.toString()); assertEquals("2026-11-10", p.to.toString()); assertEquals("2025-11-10", p.prevTo.toString())
        val r = yearReport(txs, emptyList(), p)
        assertEquals(100000.0 + 1550 + 770 + 100, r.income, 1e-9)
        assertEquals(28000.0, r.expense, 1e-9)
        assertEquals(40000.0, r.prevIncome, 1e-9); assertEquals(20000.0, r.prevExpense, 1e-9)
        assertEquals(11, r.months); assertEquals(r.expense / 11, r.avgExpense, 1e-9)
        assertEquals(Math.round((r.income - r.expense) / r.income * 100).toInt(), r.savingsPct)
    }

    @Test fun pastYearIsWholeYear() {
        val r = yearReport(txs, emptyList(), yearPeriod(2025, today))
        assertEquals(12, r.months); assertEquals(25000.0, r.expense, 1e-9)
        assertEquals("2025-06", r.maxMonth!!.month); assertEquals(20000.0, r.maxMonth!!.exp, 1e-9)
        assertEquals("2025-12", r.minMonth!!.month); assertEquals(5000.0, r.minMonth!!.exp, 1e-9)
    }

    @Test fun categoriesSortedAndTransfersExcluded() {
        val r = yearReport(txs, emptyList(), yearPeriod(2026, today))
        assertEquals(listOf("Житло" to 18000.0, "Харчування" to 10000.0), r.cats)
    }

    @Test fun investmentIncomeByKindAndDepositTax() {
        val r = yearReport(txs, emptyList(), yearPeriod(2026, today))
        assertEquals(mapOf("ОВДП" to 1550.0, "Депозити" to 770.0, "Дивіденди" to 100.0), r.invest)
        assertEquals(230.0, r.depositTax, 1e-9) // 770 після податку → 1000 до, утримано 230
        assertNull(investKind(t("Зарплата", "Зарплата", "income", 1.0, "2026-01-01")))
        assertNull(investKind(t("Купон", "Інше", "expense", 1.0, "2026-01-01")))
    }

    @Test fun capitalGrowthFromSnapshotsInsidePeriod() {
        val hist = listOf(HistPoint("2025-12-31", 1.0), HistPoint("2026-03-01", 100000.0), HistPoint("2026-11-09", 130000.0), HistPoint("2026-11-11", 9.0))
        val r = yearReport(txs, hist, yearPeriod(2026, today))
        assertEquals("2026-03-01", r.netStart!!.d); assertEquals("2026-11-09", r.netEnd!!.d)
        assertEquals(30000.0, r.netChange!!, 1e-9)
        assertNull(yearReport(txs, listOf(HistPoint("2026-05-01", 5.0)), yearPeriod(2026, today)).netChange)
    }

    @Test fun last12MonthsPeriod() {
        val p = last12Period(today)
        assertEquals("2025-11-11", p.from.toString()); assertEquals("2026-11-10", p.to.toString())
        val r = yearReport(txs, emptyList(), p)
        assertEquals(13, r.months) // листопад 2025 … листопад 2026 (неповні крайні місяці)
        assertEquals(28000.0 + 5000.0, r.expense, 1e-9)
    }

    @Test fun yearsList() {
        assertEquals(listOf(2026, 2025), reportYears(txs, today))
        assertEquals(listOf(2026), reportYears(emptyList(), today))
    }

    @Test fun excelHasThreeSheets() {
        val bytes = buildYearXlsx(yearReport(txs, emptyList(), yearPeriod(2026, today)))
        val names = java.util.zip.ZipInputStream(bytes.inputStream()).use { z -> generateSequence { z.nextEntry }.map { it.name }.toList() }
        assertTrue(names.containsAll(listOf("xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml", "xl/worksheets/sheet3.xml", "xl/workbook.xml")))
        val wb = java.util.zip.ZipInputStream(bytes.inputStream()).use { z -> generateSequence { z.nextEntry }.first { it.name == "xl/workbook.xml" }; z.readBytes().toString(Charsets.UTF_8) }
        assertTrue(wb.contains("Підсумок") && wb.contains("По місяцях") && wb.contains("По категоріях"))
    }
}
