package ua.bella.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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

/**
 * Курси НБУ і конвертер у полі, що розгортається: згорнуте показує короткі курси,
 * дотик відкриває курси з датою, «Оновити курси» і конвертер між UAH, USD, EUR, PLN (з кнопкою «⇄»).
 */
@Composable
fun RatesSection(vm: BellaViewModel, startOpen: Boolean = false) {
    @Suppress("UNUSED_VARIABLE") val subscribe = vm.ratesTick // перемальовка після оновлення курсів
    var open by remember { mutableStateOf(startOpen) }
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
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, C.divider, shape)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            T("Курси НБУ", 13.sp, weight = FontWeight.Medium, maxLines = 1)
            T(listOf("$" to "USD", "€" to "EUR", "zł" to "PLN").joinToString("  ") { (sy, c) -> sy + " " + fmt2(RATES[c] ?: 0.0) }, 12.sp, C.n400, modifier = Modifier.weight(1f), maxLines = 1)
            Ico(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, C.n500, 18.dp)
        }
        if (open) Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("USD", "EUR", "PLN").forEach { c ->
                    Column(Modifier.weight(1f).clip(shape).background(C.surface).padding(10.dp)) {
                        T("$c → UAH", 11.sp, C.n500, maxLines = 1)
                        T(fmt2(RATES[c] ?: 0.0), 16.sp, weight = FontWeight.Medium, maxLines = 1)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                T(note, 11.sp, if (failed) C.neg else C.n500, modifier = Modifier.weight(1f))
                T(if (vm.ratesBusy) "Оновлення…" else "↻ Оновити", 12.sp, C.accent400, modifier = Modifier.clickable { vm.refreshRates() }.padding(4.dp), maxLines = 1)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                Field("Сума", amount, Modifier.weight(1.2f), numeric = true) { amount = it }
                Dropdown("З", CURRENCIES, from, Modifier.weight(1f)) { from = it }
                Box(Modifier.padding(bottom = 6.dp)) { IconBtn(Icons.Outlined.SwapHoriz) { val x = from; from = to; to = x } }
                Dropdown("У", CURRENCIES, to, Modifier.weight(1f)) { to = it }
            }
            Column(Modifier.fillMaxWidth().clip(shape).background(C.surface).padding(12.dp)) {
                Label("РЕЗУЛЬТАТ")
                T(fmt2(convertAmount(parseNum(amount), CURRENCIES[from], CURRENCIES[to], RATES)) + " " + CURRENCIES[to], 22.sp, C.accent300, FontWeight.Medium, Modifier.padding(top = 4.dp), maxLines = 1)
                if (from != to) T("1 ${CURRENCIES[from]} = ${fmt2(convertAmount(1.0, CURRENCIES[from], CURRENCIES[to], RATES))} ${CURRENCIES[to]}", 11.sp, C.n500, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** «Вільно до кінця місяця»: скільки можна витратити, не виходячи за ліміти й з урахуванням повторюваних витрат (лише поточний місяць). */
@Composable
fun FreeLeftCard(vm: BellaViewModel) {
    val f = freeLeft(vm.data.txs, vm.data.recurring, vm.data.limits, TODAY)
    val neg = f.freeUah < 0
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, C.accent, RoundedCornerShape(10.dp)).padding(14.dp)) {
        Label("ВІЛЬНО ДО КІНЦЯ МІСЯЦЯ")
        T((if (neg) "−" else "") + vm.m(Math.abs(f.freeUah)) + " " + vm.sym, 28.sp, if (neg) C.neg else C.pos, FontWeight.Medium, Modifier.padding(top = 4.dp), maxLines = 1)
        T("≈ " + vm.m(f.perDayUah) + " " + vm.sym + " на день · лишилось днів: ${f.daysLeft}", 12.sp, C.n500)
        Spacer(Modifier.height(10.dp)); Div(); Spacer(Modifier.height(10.dp))
        listOf(
            Triple("Доходи місяця", "+", f.incomeUah),
            Triple("Витрати місяця", "−", f.expenseUah),
            Triple("Повторювані, що ще будуть", "−", f.recurringUah),
            Triple("Залишок лімітів", "−", f.limitsUah),
        ).forEach { (l, s, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                T(l, 12.sp, C.n500, maxLines = 1); T(s + vm.m(v) + " " + vm.sym, 12.sp, maxLines = 1)
            }
        }
        if (neg) T("Заплановані витрати й ліміти більші за доходи місяця. Зменште ліміти або відкладіть частину витрат.", 11.sp, C.n500, modifier = Modifier.padding(top = 8.dp))
    }
}
