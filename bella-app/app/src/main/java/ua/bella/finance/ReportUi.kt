package ua.bella.finance

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

/** Різниця з попереднім періодом: «+12% до мин. року» або порожньо, якщо порівнювати нема з чим. */
private fun vsPrev(now: Double, prev: Double, word: String): String = changePct(now, prev)?.let { (if (it >= 0) "+" else "−") + "${Math.abs(it)}% до $word" } ?: ""

@Composable
private fun Line(l: String, v: String, c: Color = C.text, note: String = "") {
    Column(Modifier.padding(vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { T(l, 13.sp, C.n400, maxLines = 1, modifier = Modifier.weight(1f)); T(v, 13.sp, c, FontWeight.Medium, maxLines = 1) }
        if (note.isNotEmpty()) T(note, 11.sp, C.n500)
    }
}

/** Річний звіт (календарний рік або останні 12 місяців) з експортом у Excel. */
@Composable
fun YearReportView(vm: BellaViewModel) {
    val today = LocalDate.parse(TODAY)
    val years = reportYears(vm.data.txs, today)
    if (vm.repYear !in years) vm.repYear = years.first()
    val r = vm.yearReportNow()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri -> if (uri != null) vm.exportYearXlsx(uri) }
    fun money(v: Double) = vm.m(v) + " " + vm.sym
    fun signed(v: Double) = (if (v >= 0) "+" else "−") + vm.m(Math.abs(v)) + " " + vm.sym
    val word = if (vm.repMode == 2) "попередніх 12 міс." else if (vm.repYear == today.year) "тих самих днів торік" else "${vm.repYear - 1} року"
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (vm.repMode == 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            val i = years.indexOf(vm.repYear)
            IconBtn(Icons.Outlined.ChevronLeft, if (i < years.size - 1) C.n400 else C.n700) { if (i < years.size - 1) vm.repYear = years[i + 1] }
            T(r.period.label, 14.sp, weight = FontWeight.Medium)
            IconBtn(Icons.Outlined.ChevronRight, if (i > 0) C.n400 else C.n700) { if (i > 0) vm.repYear = years[i - 1] }
        } else T(r.period.label, 14.sp, weight = FontWeight.Medium)

        if (r.income == 0.0 && r.expense == 0.0) { T("За цей період операцій немає.", 13.sp, C.n500); return@Column }

        Column {
            Label("ДОХОДИ, ВИТРАТИ, ЗАОЩАДЖЕННЯ")
            Line("Доходи", "+" + money(r.income), C.pos, vsPrev(r.income, r.prevIncome, word))
            Line("Витрати", "−" + money(r.expense), note = vsPrev(r.expense, r.prevExpense, word))
            Line("Баланс", signed(r.net), if (r.net >= 0) C.pos else C.neg)
            Line("Норма заощаджень", r.savingsPct?.let { "$it%" } ?: "—", note = if ((r.savingsPct ?: 0) >= 20) "не менше рекомендованих 20%" else "рекомендовано від 20% доходу")
            Line("У середньому за місяць", "+${vm.m(r.avgIncome)} / −${vm.m(r.avgExpense)} ${vm.sym}", note = "місяців у періоді: ${r.months}")
        }
        FlowBars(vm, r.flows)
        Column {
            Label("ВИТРАТИ ЗА КАТЕГОРІЯМИ")
            r.cats.forEach { (c, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Ico(catIcon(c), C.accent, 18.dp)
                    Column(Modifier.weight(1f)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { T(c, 13.sp, maxLines = 1); T(money(v), 13.sp, weight = FontWeight.Medium, maxLines = 1) }
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Bar((v / r.expense).toFloat(), modifier = Modifier.weight(1f)); T("${Math.round(v / r.expense * 100)}% · ≈${vm.m(v / r.months)}/міс", 11.sp, C.n500)
                        }
                    }
                }
            }
            r.maxMonth?.let { T("Найбільші витрати: ${monthLabel(it.month)} — ${money(it.exp)}", 12.sp, C.n500, modifier = Modifier.padding(top = 6.dp)) }
            r.minMonth?.let { T("Найменші витрати: ${monthLabel(it.month)} — ${money(it.exp)}", 12.sp, C.n500) }
        }
        Column {
            Label("ЗРОСТАННЯ КАПІТАЛУ")
            val a = r.netStart; val b = r.netEnd
            if (a == null || b == null || a.d == b.d) T("Знімки капіталу зберігаються щодня з початку користування застосунком; за цей період їх замало для порівняння.", 12.sp, C.n500)
            else {
                Line("Капітал на ${ddmmyyyy(a.d)}", money(a.v)); Line("Капітал на ${ddmmyyyy(b.d)}", money(b.v))
                Line("Зміна", signed(b.v - a.v), if (b.v >= a.v) C.pos else C.neg, if (a.v > 0) "${if (b.v >= a.v) "+" else "−"}${"%.1f".format(Math.abs(b.v - a.v) / a.v * 100).replace('.', ',')}% (разом зі зміною курсів і цін)" else "")
                if (a.d > r.period.from.toString()) T("Перший знімок у періоді — ${ddmmyyyy(a.d)}.", 11.sp, C.n500)
            }
        }
        Column {
            Label("ІНВЕСТИЦІЙНИЙ ДОХІД")
            if (r.invest.isEmpty()) T("Купонів, відсотків чи дивідендів за період не знайдено. Вони беруться з операцій-доходів, в описі чи категорії яких є «купон», «ОВДП», «відсотки», «депозит» або «дивіденди».", 12.sp, C.n500)
            else {
                r.invest.entries.sortedByDescending { it.value }.forEach { (k, v) ->
                    Line(k, "+" + money(v), C.pos, when (k) { "ОВДП" -> "купони ОВДП податком не обкладаються"; "Депозити" -> "після податку 23%; утримано ≈ ${money(r.depositTax)}"; else -> "" })
                }
                Line("Разом", "+" + money(r.investTotal), C.pos, if (r.income > 0) "${Math.round(r.investTotal / r.income * 100)}% усіх доходів" else "")
            }
        }
        Btn("Експорт звіту в Excel", false, Modifier.fillMaxWidth(), Icons.Outlined.FileDownload) { save.launch(yearXlsxName(r)) }
        if (vm.backupMsg.startsWith("Звіт") || vm.backupMsg.contains("Excel")) T(vm.backupMsg, 12.sp, if (vm.backupMsg.startsWith("Не ")) C.neg else C.n500)
        T("Суми — у вибраній валюті перегляду за поточним курсом НБУ. Перекази між рахунками не входять у доходи й витрати.", 11.sp, C.n500)
    }
}
