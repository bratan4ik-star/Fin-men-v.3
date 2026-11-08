package ua.bella.finance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

/* Вкладка «Порівняти» в «ОВДП та Інвестиції»: 2–3 випуски за офіційним графіком НБУ (OvdpCompare.kt). */

private val CMP_COLORS = listOf(Color(0xFF9184D9), BlueC, TealC, GoldC)
/** Колір варіанту — за порядком погашення (той самий у картці, таблиці, легенді й на графіку); драбина — жовта. */
private fun cmpColor(title: String, order: List<String>): Color = if (title == "Драбина") CMP_COLORS[3] else CMP_COLORS[order.indexOf(title).coerceAtLeast(0) % 3]

private fun pct(v: Double?) = if (v == null) "—" else fmt2(v * 100) + "%"
private fun uah(v: Double) = fmtInt(v) + " ₴"

@Composable
fun OvdpCompareTab(vm: BellaViewModel) {
    val today = LocalDate.parse(TODAY)
    T("Порівняння 2–3 випусків на одну суму за офіційним графіком виплат НБУ. Ціна — повна: скільки списують за 1 шт разом із накопиченим купоном.", 12.sp, C.n500)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Field("Сума інвестиції, ₴", vm.cmpAmount, Modifier.weight(1f), hint = "напр. 338000", numeric = true, need = parseNum(vm.cmpAmount) <= 0) { vm.cmpAmount = it; vm.cmpRate = null }
        DateField("Дата купівлі", vm.cmpDate, Modifier.weight(1f)) { vm.cmpDate = it; vm.cmpRate = null }
    }
    val order = vm.cmp.map { it.bond }.sortedBy { it.mat }.map { it.isin }
    vm.cmp.forEachIndexed { i, e -> CmpEntryCard(vm, i, e, today, cmpColor(e.bond.isin, order)) }
    if (vm.cmpMsg.isNotEmpty()) T(vm.cmpMsg, 12.sp, C.warn)
    if (vm.cmp.size < 3) {
        var showPos by remember { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("З довідника", false, Modifier.weight(1f), Icons.Outlined.Search) { vm.openOvdpPick(forCompare = true) }
            Btn("З моїх позицій", false, Modifier.weight(1f), Icons.Outlined.AccountBalanceWallet) { showPos = !showPos }
        }
        if (showPos) {
            val ps = vm.cmpPositions()
            if (ps.isEmpty()) T("Немає позицій ОВДП, чий випуск є в довіднику НБУ. Виберіть випуск у формі позиції або додайте з довідника.", 12.sp, C.n500)
            else Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, C.divider, RoundedCornerShape(8.dp))) {
                ps.forEachIndexed { k, p ->
                    if (k > 0) Div()
                    Row(Modifier.fillMaxWidth().clickable { vm.cmpAddPosition(p); showPos = false }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { T(p.name, 13.sp, maxLines = 1); T("${fmtQty(p.qty)} шт. · погашення ${ddmmyy(p.mat)}", 11.sp, C.n500) }
                        Ico(Icons.Outlined.Add, C.accent400, 18.dp)
                    }
                }
            }
        }
    }
    val results = vm.cmpResults()
    val singles = results.filter { it.single }
    if (singles.size < 2) {
        T(if (vm.cmp.size < 2) "Додайте щонайменше 2 випуски." else "Введіть ціну купівлі для кожного випуску.", 13.sp, C.n500)
        TaxField(vm); return
    }
    if (singles.any { it.legs.single().qty == 0 }) T("Суми не вистачає навіть на 1 облігацію одного з випусків — збільште суму.", 12.sp, C.warn)
    val horizon = results.maxOf { it.returnDate }
    val shortest = singles.minByOrNull { it.returnDate }!!
    val r = vm.cmpRate ?: shortest.ytm ?: 0.15
    CmpTable(results, horizon, r, order)
    // повзунок ставки реінвестування
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Припущена ставка реінвестування", 13.sp, weight = FontWeight.Medium, modifier = Modifier.weight(1f)); T(pct(r), 15.sp, C.accent400, FontWeight.Bold)
        }
        Slider(value = (r * 100).toFloat().coerceIn(0f, 30f), onValueChange = { vm.cmpRate = Math.round(it * 10) / 1000.0 }, valueRange = 0f..30f,
            colors = SliderDefaults.colors(thumbColor = C.accent, activeTrackColor = C.accent, inactiveTrackColor = C.divider))
        T("Під цю ставку вкладаються всі отримані купони й номінал до ${ddmmyyyy(horizon.toString())} (погашення найдовшого). За замовчуванням — дохідність коротшого ${shortest.title}" +
            (if (vm.cmpRate != null) "; " else ".") , 11.sp, C.n500)
        if (vm.cmpRate != null) T("Повернути до дохідності коротшого", 12.sp, C.accent400, modifier = Modifier.clickable { vm.cmpRate = null }.padding(vertical = 2.dp))
        results.forEachIndexed { k, v ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(cmpColor(v.title, order)))
                T(v.title, 13.sp, modifier = Modifier.weight(1f), maxLines = 1); T(uah(valueAt(v, horizon, r)), 13.sp, weight = FontWeight.Medium)
            }
        }
        // ставки беззбитковості: кожен коротший проти найдовшого
        val longest = singles.maxByOrNull { it.returnDate }!!
        singles.filter { it !== longest && it.returnDate < longest.returnDate }.forEach { s ->
            val be = breakEvenRate(s, longest)
            T("Ставка беззбитковості ${s.title} → ${longest.title}: " + (if (be != null) pct(be) + " річних" else "немає (один варіант кращий за будь-якої ставки)"), 12.sp, weight = FontWeight.Medium)
        }
    }
    CmpChart(results, vm.cmpSettle(), horizon, r, order)
    T(compareConclusion(results, r), 13.sp, lineHeight = 19.sp)
    // виплати й «Додати в портфель»
    singles.forEach { v -> CmpFlows(vm, v) }
    TaxField(vm)
    T("Розрахунок — за графіком виплат НБУ і введеними цінами; це не порада щодо купівлі. Дохідність аукціону — середньозважена дохідність розміщення, а не ринкова ціна.", 11.sp, C.n500)
}

@Composable
private fun CmpEntryCard(vm: BellaViewModel, i: Int, e: BellaViewModel.CmpEntry, today: LocalDate, color: Color) {
    val b = e.bond; val a = vm.nbu.auctionOf(b.isin)
    val settle = vm.cmpSettle()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, C.divider, RoundedCornerShape(10.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(color)); Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                T("${b.isin} · ${b.cur}", 14.sp, weight = FontWeight.Medium)
                T("купон ${fmt2(b.rate)}% · погашення ${ddmmyyyy(b.mat)} · ${termLeft(b.mat, today)}" + if (e.from.isNotEmpty()) " · з позиції" else "", 11.sp, C.n500)
            }
            IconBtn(Icons.Outlined.Close, C.n500) { vm.cmpRemove(i) }
        }
        val hintPx = a?.let { priceAtYield(b, settle, it.avg / 100) }
        Field("Повна ціна за 1 шт, ${curSign(b.cur)}", e.price, hint = hintPx?.let { fmtPlain(it) } ?: "напр. 1012.40", numeric = true, need = parseNum(e.price) <= 0) { vm.cmpSetPrice(i, it) }
        if (a != null && hintPx != null) Row(verticalAlignment = Alignment.CenterVertically) {
            T("Аукціон ${ddmmyyyy(a.date)}: дохідність ${fmt2(a.avg)}% ≈ ціна ${fmt2(hintPx)} ${curSign(b.cur)}", 11.sp, C.n500, modifier = Modifier.weight(1f))
            T("підставити", 12.sp, C.accent400, modifier = Modifier.clickable { vm.cmpSetPrice(i, fmtPlain(hintPx)) }.padding(4.dp))
        } else T("Аукціонів за рік у довіднику немає — введіть ціну з банку чи брокера.", 11.sp, C.n500)
    }
}

/** Таблиця в стовпчики (гортається вбік): кожен варіант — стовпчик. */
@Composable
private fun CmpTable(results: List<CmpResult>, horizon: LocalDate, r: Double, order: List<String>) {
    fun cur(v: CmpResult, x: Double) = if (v.cur == "UAH") uah(x) else fxMoney(x, v.cur)
    fun inCur(v: CmpResult, xUah: Double) = if (v.cur == "UAH") uah(xUah) else fxMoney(xUah / (RATES[v.cur] ?: 1.0), v.cur) + "\n" + uah(xUah)
    val rows: List<Pair<String, (CmpResult) -> String>> = listOf(
        "Валюта" to { v -> v.cur },
        "Повернення грошей" to { v -> ddmmyyyy(v.returnDate.toString()) },
        "Днів до погашення" to { v -> v.days.toString() },
        "Кількість, шт" to { v -> v.legs.joinToString(" + ") { it.qty.toString() } },
        "Ціна 1 шт" to { v -> if (v.single) cur(v, v.legs.single().price) else "—" },
        "Фактично вкладено" to { v -> inCur(v, v.investedUah) },
        "Залишок грошей" to { v -> uah(v.leftoverUah) },
        "Купони" to { v -> inCur(v, v.legs.sumOf { it.coupons * (if (it.cur == "UAH") 1.0 else RATES[it.cur] ?: 1.0) }) },
        "Номінал" to { v -> inCur(v, v.legs.sumOf { it.principal * (if (it.cur == "UAH") 1.0 else RATES[it.cur] ?: 1.0) }) },
        "Податок" to { v -> v.legs.sumOf { it.tax * (if (it.cur == "UAH") 1.0 else RATES[it.cur] ?: 1.0) }.let { if (it > 0) uah(it) else "0" } },
        "Дохід до погашення" to { v -> uah(v.incomeUah) },
        "На кожні 100 000 ₴" to { v -> uah(v.per100k) },
        "Дохідність до погашення (YTM)" to { v -> pct(v.ytm) + if (v.cur != "UAH") " у ${v.cur}" else "" },
        "Простий річний дохід" to { v -> pct(v.simple) },
        "Підсумок на ${ddmmyy(horizon.toString())} при ${fmt2(r * 100)}%" to { v -> uah(valueAt(v, horizon, r)) },
    )
    val fx = results.any { it.cur != "UAH" }
    // висота рядка: довгий підпис або сума у валюті й гривнях — у два рядки
    fun h(l: String) = if (l.length > 17 || (fx && (l == "Фактично вкладено" || l == "Купони" || l == "Номінал"))) 44.dp else 30.dp
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Column(Modifier.width(104.dp)) {
            Box(Modifier.height(30.dp)) {}
            rows.forEach { (l, _) -> Box(Modifier.height(h(l)), contentAlignment = Alignment.CenterStart) { T(l, 11.sp, C.n500, lineHeight = 13.sp) } }
        }
        results.forEach { v ->
            Column(Modifier.width(84.dp).padding(start = 6.dp)) {
                Row(Modifier.height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(cmpColor(v.title, order))); Spacer(Modifier.width(4.dp))
                    T(if (v.single) "…" + v.title.takeLast(6) else v.title, 12.sp, weight = FontWeight.Medium, maxLines = 1)
                }
                rows.forEach { (l, f) -> Box(Modifier.height(h(l)), contentAlignment = Alignment.CenterStart) {
                    T(f(v), 12.sp, if (l.startsWith("Дохід до") || l.startsWith("На кожні")) C.pos else C.text, if (l.startsWith("Підсумок") || l.startsWith("Дохідність")) FontWeight.Medium else FontWeight.Normal, lineHeight = 14.sp) } }
            }
        }
    }
}


/** Накопичена вартість варіантів у часі (₴): отримане реінвестується під r, ще не отримане — за дохідністю варіанту. */
@Composable
private fun CmpChart(results: List<CmpResult>, settle: LocalDate, horizon: LocalDate, r: Double, order: List<String>) {
    val curves = results.map { valueCurve(it, settle, horizon, r) }
    val all = curves.flatten(); if (all.size < 2) return
    val mn = all.minOf { it.amount }; val mx = all.maxOf { it.amount }; val span = (mx - mn).takeIf { it > 0 } ?: 1.0
    val total = yearFrac(settle, horizon).takeIf { it > 0 } ?: 1.0
    val grid = C.divider
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.surface).padding(12.dp)) {
        T("Накопичена вартість, ₴", 13.sp, weight = FontWeight.Medium)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Canvas(Modifier.weight(1f).height(160.dp)) {
                val top = 6.dp.toPx(); val hh = size.height - 2 * top
                fun x(d: LocalDate) = (yearFrac(settle, d) / total).toFloat() * size.width
                fun y(v: Double) = top + (1 - ((v - mn) / span).toFloat()) * hh
                listOf(mn, (mn + mx) / 2, mx).forEach { g -> drawLine(grid, Offset(0f, y(g)), Offset(size.width, y(g)), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))) }
                curves.forEachIndexed { k, c ->
                    val p = Path().apply { c.forEachIndexed { j, f -> if (j == 0) moveTo(x(f.date), y(f.amount)) else lineTo(x(f.date), y(f.amount)) } }
                    drawPath(p, cmpColor(results[k].title, order), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round,
                        pathEffect = if (!results[k].single) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null))
                }
            }
            Column(Modifier.height(160.dp).padding(start = 6.dp), verticalArrangement = Arrangement.SpaceBetween) { T(short(mx), 10.sp, C.n500); T(short(mn), 10.sp, C.n500) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) { T(ddmmyy(settle.toString()), 10.sp, C.n500); T(ddmmyy(horizon.toString()), 10.sp, C.n500) }
        T("Після погашення коротшого гроші ростуть під припущену ставку; пунктир — драбина.", 10.sp, C.n500)
    }
}

/** Виплати варіанту за датами і кнопка «Додати в портфель». */
@Composable
private fun CmpFlows(vm: BellaViewModel, v: CmpResult) {
    var open by remember(v.title) { mutableStateOf(false) }
    val leg = v.legs.single()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, C.divider, RoundedCornerShape(10.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T(v.title, 13.sp, weight = FontWeight.Medium)
                T("${leg.qty} шт · виплат ${leg.flows.size} · повернення ${ddmmyyyy(v.returnDate.toString())}", 11.sp, C.n500)
            }
            Ico(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, C.n600, 18.dp)
        }
        if (open) leg.flows.forEach { f ->
            Row(Modifier.fillMaxWidth()) {
                T(ddmmyyyy(f.date.toString()), 12.sp, C.n500, modifier = Modifier.width(90.dp))
                T(if (f.date == leg.mat && leg.principal > 0) "купон + номінал" else "купон", 12.sp, C.n500, modifier = Modifier.weight(1f))
                T(if (leg.cur == "UAH") uah(f.amount) else fxMoney(f.amount, leg.cur), 12.sp, C.pos)
            }
        }
        if (v.fxRisk) T("Валютний випуск: виплати в ${leg.cur}; у гривнях — за поточним курсом НБУ ${fmt2(RATES[leg.cur] ?: 0.0)} ₴, на дати виплат курс може бути іншим.", 11.sp, C.warn)
        Btn("Додати в портфель", false, Modifier.fillMaxWidth(), Icons.Outlined.Add) { vm.cmpToPortfolio(v) }
    }
}

@Composable
private fun TaxField(vm: BellaViewModel) {
    var t by remember { mutableStateOf(if (vm.ovdpTax == 0.0) "" else fmtPlain(vm.ovdpTax)) }
    Field("Податок на дохід ОВДП, %", t, hint = "0 — зараз ПДФО не сплачується", numeric = true) { t = it; vm.setOvdpTax(it) }
    T("Для фізичних осіб купони й дисконт ОВДП зараз не оподатковуються. Якщо закон зміниться, вкажіть ставку: вона віднімається з купонів і з різниці «номінал − ціна».", 11.sp, C.n500)
}
