package ua.bella.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** «Розумна аналітика» на вкладці «Огляд» бюджету: запас міцності, прогноз, поради, топ-5 витрат. */
@Composable
fun SmartAnalyticsSection(vm: BellaViewModel, periodTxs: List<Tx>) {
    val r = smartAnalytics(vm.data.txs, periodTxs, Totals(vm).cash, TODAY)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Label("РОЗУМНА АНАЛІТИКА")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(C.surface).padding(12.dp)) {
                T("Запас міцності", 11.sp, C.n500, maxLines = 1)
                T(if (r.runwayMonths == null) "—" else fmtMonths(r.runwayMonths) + " міс.", 20.sp, weight = FontWeight.Medium, maxLines = 1)
                T("готівки за середніми витратами", 11.sp, C.n500)
            }
            Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(C.surface).padding(12.dp)) {
                T("Витрати цього місяця", 11.sp, C.n500, maxLines = 1)
                T(vm.m(r.projectedUah) + " " + vm.sym, 20.sp, weight = FontWeight.Medium, maxLines = 1)
                T("прогноз · ≈ ${vm.m(r.burnPerDayUah)} ${vm.sym} на день", 11.sp, C.n500)
            }
        }
        r.insights.forEach { i ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.padding(top = 6.dp)) { Dot(if (i.ok) C.pos else GoldC) }
                Column(Modifier.weight(1f)) {
                    T((if (i.ok) "✓ " else "! ") + i.title, 13.sp, weight = FontWeight.Medium)
                    T(i.text, 12.sp, C.n500)
                }
            }
        }
        if (r.top.isNotEmpty()) {
            Label("НАЙБІЛЬШІ ВИТРАТИ ЗА ПЕРІОД")
            Column { r.top.forEach { Div(); TxRowView(it, vm) } }
        }
    }
}

/** Курси НБУ з датою, кнопка оновлення і конвертер між UAH, USD, EUR, PLN. */
@Composable
fun RatesSection(vm: BellaViewModel) {
    @Suppress("UNUSED_VARIABLE") val subscribe = vm.ratesTick // перемальовка після оновлення курсів
    var amount by remember { mutableStateOf("100") }
    var from by remember { mutableStateOf(CURRENCIES.indexOf("USD")) }
    var to by remember { mutableStateOf(0) }
    val note = when {
        vm.ratesMsg.isNotEmpty() -> vm.ratesMsg
        vm.ratesAt > 0 -> (if (vm.ratesDate.isNotEmpty()) "Курс НБУ на ${vm.ratesDate}" else "Курс НБУ") +
            " · оновлено " + SimpleDateFormat("dd.MM HH:mm", Locale("uk", "UA")).format(Date(vm.ratesAt))
        else -> "Показано орієнтовні курси: оновлення з НБУ ще не було."
    }
    val failed = vm.ratesMsg.startsWith("Не вдалося")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHead("Курси НБУ і конвертер", if (vm.ratesBusy) "Оновлення…" else "Оновити курси") { vm.refreshRates() }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("USD", "EUR", "PLN").forEach { c ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(C.surface).padding(10.dp)) {
                    T("$c → UAH", 11.sp, C.n500, maxLines = 1)
                    T(fmt2(RATES[c] ?: 0.0), 16.sp, weight = FontWeight.Medium, maxLines = 1)
                }
            }
        }
        T(note, 11.sp, if (failed) C.neg else C.n500)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
            Field("Сума", amount, Modifier.weight(1.2f), numeric = true) { amount = it }
            Dropdown("З", CURRENCIES, from, Modifier.weight(1f)) { from = it }
            Dropdown("У", CURRENCIES, to, Modifier.weight(1f)) { to = it }
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.surface).padding(12.dp)) {
            Label("РЕЗУЛЬТАТ")
            T(fmt2(convertAmount(parseNum(amount), CURRENCIES[from], CURRENCIES[to], RATES)) + " " + CURRENCIES[to], 22.sp, C.accent300, FontWeight.Medium, Modifier.padding(top = 4.dp), maxLines = 1)
        }
    }
}
