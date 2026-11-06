package ua.bella.finance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

/** Кольори ліній ETF (по черзі). */
private val IB_COLORS = listOf(Color(0xFF6366F1), Color(0xFFF59E0B), Color(0xFF14B8A6), Color(0xFFEC4899), Color(0xFF84CC16), Color(0xFF0EA5E9))
private val IB_CASH = Color(0xFF94A3B8)

/** Лінія графіка: точки (день у епосі, значення). */
data class LSeries(val name: String, val color: Color, val pts: List<Pair<Long, Double>>, val dashed: Boolean = false, val area: Boolean = false)

private fun ep(d: String) = LocalDate.parse(d).toEpochDay()

/** Кілька ліній на спільній шкалі часу; дотик чи ведення пальцем показує значення всіх ліній на дату. */
@Composable
fun MultiLine(series: List<LSeries>, height: Dp, fmtV: (Double) -> String, zero: Boolean = false) {
    val ss = series.filter { it.pts.size >= 2 }; if (ss.isEmpty()) return
    var sel by remember { mutableStateOf<Float?>(null) }
    val x0 = ss.minOf { it.pts.first().first }; val x1 = ss.maxOf { it.pts.last().first }.coerceAtLeast(x0 + 1)
    var lo = ss.minOf { s -> s.pts.minOf { it.second } }; var hi = ss.maxOf { s -> s.pts.maxOf { it.second } }
    if (zero) { lo = Math.min(lo, 0.0); hi = Math.max(hi, 0.0) }
    if (hi - lo < 1e-9) { hi += 1; lo -= 1 }
    val pad = (hi - lo) * 0.06; lo -= pad; hi += pad
    val at = sel?.let { f -> x0 + Math.round(f * (x1 - x0)) }
    fun near(s: LSeries, x: Long) = s.pts.minBy { Math.abs(it.first - x) }  // ss містить лише ряди з ≥ 2 точками
    val grid = C.divider; val axis = C.n500; val dotC = C.text
    Column {
        // шапка: дата й значення під пальцем, інакше назви й останні значення
        Column(Modifier.fillMaxWidth().heightIn(min = 34.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (at != null) T(ddmmyyyy(LocalDate.ofEpochDay(near(ss[0], at).first).toString()), 11.sp, C.n500)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ss.forEach { s ->
                    val v = if (at != null) near(s, at).second else s.pts.last().second
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(Modifier.size(width = 10.dp, height = if (s.dashed) 2.dp else 3.dp).background(s.color))
                        T("${s.name} ${fmtV(v)}", 11.sp, maxLines = 1)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Canvas(Modifier.weight(1f).height(height)
                .pointerInput(x0, x1) { detectTapGestures { o -> sel = (o.x / size.width).coerceIn(0f, 1f) } }
                .pointerInput(x0, x1) { detectDragGestures(onDragEnd = { sel = null }) { c, _ -> sel = (c.position.x / size.width).coerceIn(0f, 1f) } }) {
                val top = 4.dp.toPx(); val hh = size.height - 2 * top
                fun px(x: Long) = ((x - x0).toFloat() / (x1 - x0)) * size.width
                fun py(v: Double) = top + ((hi - v) / (hi - lo)).toFloat() * hh
                listOf(lo + pad, (lo + hi) / 2, hi - pad).forEach { g -> drawLine(grid, Offset(0f, py(g)), Offset(size.width, py(g)), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))) }
                if (zero) drawLine(axis.copy(alpha = .6f), Offset(0f, py(0.0)), Offset(size.width, py(0.0)), 1f)
                ss.forEach { s ->
                    val line = Path().apply { s.pts.forEachIndexed { k, (x, v) -> if (k == 0) moveTo(px(x), py(v)) else lineTo(px(x), py(v)) } }
                    if (s.area) {
                        val fill = Path().apply { addPath(line); lineTo(px(s.pts.last().first), size.height); lineTo(px(s.pts.first().first), size.height); close() }
                        drawPath(fill, Brush.verticalGradient(listOf(s.color.copy(alpha = .26f), Color.Transparent)))
                    }
                    drawPath(line, s.color, style = Stroke(if (s.dashed) 1.5.dp.toPx() else 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round,
                        pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null))
                    val l = s.pts.last(); drawCircle(s.color, 3.dp.toPx(), Offset(px(l.first), py(l.second)))
                }
                if (at != null) {
                    drawLine(dotC.copy(alpha = .35f), Offset(px(at), 0f), Offset(px(at), size.height), 1f)
                    ss.forEach { s -> val p = near(s, at); drawCircle(s.color, 4.dp.toPx(), Offset(px(p.first), py(p.second))) }
                }
            }
            Column(Modifier.height(height).padding(start = 6.dp), verticalArrangement = Arrangement.SpaceBetween) {
                T(fmtV(hi - pad), 10.sp, C.n500); T(fmtV(lo + pad), 10.sp, C.n500)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            T(ddmmyy(LocalDate.ofEpochDay(x0).toString()), 10.sp, C.n500); T(ddmmyy(LocalDate.ofEpochDay(x1).toString()), 10.sp, C.n500)
        }
    }
}

@Composable
private fun Fig(label: String, value: String, color: Color, sub: String, m: Modifier) = Column(m) {
    T(label, 11.sp, C.n500, maxLines = 1); T(value, 16.sp, color, FontWeight.Medium, maxLines = 1); if (sub.isNotEmpty()) T(sub, 11.sp, C.n500, maxLines = 1)
}

/** Вікно «Графіки IBKR»: вартість рахунку й внески, дохідність, ціни ETF, розподіл і прибуток за позиціями. */
@Composable
fun IbkrChartsView(vm: BellaViewModel) {
    val base = vm.ibkr.base
    fun money(v: Double) = if (vm.hide) "•••• ${curSign(base)}" else fxMoney(v, base)
    fun moneyShort(v: Double) = if (vm.hide) "••••" else short(v) + " " + curSign(base)
    fun signed(v: Double) = if (vm.hide) "••••" else (if (v >= 0.005) "+" else if (v <= -0.005) "−" else "") + fxMoney(Math.abs(v), base)
    fun pct(v: Double) = (if (v >= 0.0005) "+" else if (v <= -0.0005) "−" else "") + "%.1f%%".format(Math.abs(v * 100))
    val posC = C.pos; val negC = C.neg; val txtC = C.text
    fun tone(v: Double) = if (v > 0.005) posC else if (v < -0.005) negC else txtC

    // перемикач періоду — спільний для вартості й цін ETF
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IB_PERIODS.forEach { (p, l) ->
            val on = p == vm.ibkr.period
            Box(Modifier.weight(1f).clip(CircleShape).background(if (on) C.accent else Color.Transparent).clickable { vm.ibkr.period = p }.padding(vertical = 5.dp), contentAlignment = Alignment.Center) {
                T(l, 12.sp, if (on) C.bg else C.n500, FontWeight.Medium)
            }
        }
    }

    // ---- вартість рахунку ----
    val w = ibWindow(vm.ibkr.nav, vm.ibkr.flows, vm.ibkr.period)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Label("ВАРТІСТЬ РАХУНКУ, ${base}")
        if (w.pts.size < 2) {
            T(if (vm.ibkr.nav.isEmpty()) "Історія з'явиться після синхронізації з IBKR." else "Поки є лише одна точка (${ddmmyyyy(vm.ibkr.nav.last().d)}). Кожна синхронізація додає вартість за день.", 12.sp, C.n500)
            T("Щоб одразу бачити історію за рік, додайте у свій Activity Flex Query розділ «Net Asset Value (NAV) in Base», а в Cash Transactions — «Deposits & Withdrawals».", 11.sp, C.n400)
        } else {
            if (w.capped) T("Історія рахунку — з ${ddmmyyyy(w.pts.first().d)}, коротша за обраний період, тож показано всю.", 11.sp, C.n500)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Fig("Вартість", money(w.value), C.text, "на початку ${money(w.start)}", Modifier.weight(1f))
                Fig("Внесено", signed(w.deposits), C.text, "зміна ${signed(w.change)}", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Fig("Результат інвестицій", signed(w.gain), tone(w.gain), "зміна мінус внесене", Modifier.weight(1f))
                Fig("Дохідність TWR", pct(w.twr), tone(w.twr), "без впливу внесків", Modifier.weight(1f))
            }
            MultiLine(listOf(
                LSeries("Вартість", C.accent, w.pts.map { ep(it.d) to it.nav }, area = true),
                LSeries("Внесено", C.n500, w.pts.map { ep(it.d) to it.invested }, dashed = true),
            ), 150.dp, ::moneyShort)
            T("Пунктир — скільки грошей внесено (з вартістю на початку періоду). Відстань між лініями — прибуток чи збиток.", 11.sp, C.n500)
            Label("ДОХІДНІСТЬ БЕЗ УРАХУВАННЯ ВНЕСКІВ")
            MultiLine(listOf(LSeries("TWR", if (w.twr >= 0) C.pos else C.neg, w.pts.map { ep(it.d) to it.twr })), 90.dp, ::pct, zero = true)
        }
    }
    Div()

    // ---- ціни ETF (з IBKR) ----
    val etfs = vm.data.pos.filter { it.src == "ibkr" }.distinctBy { it.ibSym.ifEmpty { it.name } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label("ЦІНИ ВАШИХ ETF")
        val series = etfs.mapIndexedNotNull { i, p -> vm.ibkr.px[p.ibSym.ifEmpty { p.name }]?.takeIf { it.size >= 2 }?.let { h ->
            LSeries(p.name, IB_COLORS[i % IB_COLORS.size], thin(priceWindow(h, vm.ibkr.period)).map { ep(it.d) to it.v }) } }
        when {
            etfs.isEmpty() -> T("Немає позицій IBKR.", 12.sp, C.n500)
            series.isNotEmpty() -> {
                val first = series.minOf { it.pts.first().first }
                MultiLine(series, 130.dp, ::pct, zero = true)
                T("Зміна ціни фондів від початку періоду, у їхній валюті, за ціною закриття з IBKR." +
                    (if (LocalDate.ofEpochDay(first).toString() > periodStart(LocalDate.now().toString(), vm.ibkr.period)) " Історія — з ${ddmmyyyy(LocalDate.ofEpochDay(first).toString())}, коротша за період." else ""), 11.sp, C.n500)
            }
            else -> T("Історія цін з'явиться після синхронізації. Щоб одразу отримати ціни за рік, додайте у Flex-запит розділ «Prior Period Positions».", 12.sp, C.n500)
        }
    }
    Div()

    // ---- розподіл ----
    val alloc = ibkrAlloc(vm.data, RATES, base)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label("РОЗПОДІЛ РАХУНКУ ЗАРАЗ")
        if (alloc.isEmpty()) T("Немає позицій чи грошей IBKR.", 12.sp, C.n500)
        else {
            var ci = 0
            PortfolioDonut(vm, alloc.map { Slice(it.name + " · " + money(it.value), if (it.cash) IB_CASH else IB_COLORS[ci++ % IB_COLORS.size], it.value) }, unit = "поз." to "поз.")
        }
    }
    Div()

    // ---- прибуток за позиціями ----
    val pnl = ibkrPnl(vm.data)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label("ПРИБУТОК ЗА ПОЗИЦІЯМИ")
        if (pnl.isEmpty()) T("Немає позицій IBKR у валюті з ціною купівлі.", 12.sp, C.n500)
        else {
            val oneCur = pnl.map { it.cur }.distinct().singleOrNull()
            if (oneCur != null) {
                val t = pnl.sumOf { it.pnl }; val c = pnl.sumOf { it.cost }
                T("Разом: " + (if (vm.hide) "••••" else (if (t >= 0) "+" else "−") + fxMoney(Math.abs(t), oneCur)) + " · " + pct(if (c > 0) t / c else 0.0) + " від вкладеного", 12.sp, tone(t))
            }
            val ext = pnl.maxOf { Math.abs(it.pnl) }.coerceAtLeast(1e-9)
            pnl.forEach { r ->
                val col = if (r.pnl < 0) C.neg else C.pos
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    T(r.name, 12.sp, weight = FontWeight.Medium, modifier = Modifier.width(56.dp), maxLines = 1)
                    // стовпчик від центру: ліва половина — збиток, права — прибуток
                    Row(Modifier.weight(1f).height(18.dp)) {
                        val f = (Math.abs(r.pnl) / ext).toFloat().coerceIn(0.02f, 1f)
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
                            if (r.pnl < 0) Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)).background(col))
                        }
                        Box(Modifier.width(1.dp).fillMaxHeight().background(C.n500))
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                            if (r.pnl >= 0) Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)).background(col))
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        T(if (vm.hide) "••••" else (if (r.pnl >= 0) "+" else "−") + fxMoney(Math.abs(r.pnl), r.cur), 12.sp, col, maxLines = 1)
                        T(pct(r.pct) + " · " + (if (vm.hide) "••••" else fxMoney(r.value, r.cur)), 10.sp, C.n500, maxLines = 1)
                    }
                }
            }
            T("Нереалізований: вартість зараз мінус купівля за середньою ціною, у валюті фонду, без курсу гривні.", 11.sp, C.n500)
        }
    }
}

/** Картка рахунку IBKR угорі «Інвестицій»: вартість, результат і дохідність за місяць; торкання відкриває графіки. */
@Composable
fun IbkrCard(vm: BellaViewModel) {
    val base = vm.ibkr.base
    val now = ibkrSnapshot(vm.data, RATES, base) ?: vm.ibkr.nav.lastOrNull()?.v
    val w = ibWindow(vm.ibkr.nav, vm.ibkr.flows, "1M")
    val pos = C.pos; val neg = C.neg
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.surface).clickable { vm.openIbkrCharts() }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Label("РАХУНОК IBKR")
            T(if (now == null) "—" else if (vm.hide) "•••• ${curSign(base)}" else fxMoney(now, base), 18.sp, weight = FontWeight.Medium, maxLines = 1)
            if (w.pts.size >= 2) {
                val g = w.gain
                T((if (vm.hide) "••••" else (if (g >= 0) "+" else "−") + fxMoney(Math.abs(g), base)) + " · TWR " +
                    (if (w.twr >= 0) "+" else "−") + "%.1f%%".format(Math.abs(w.twr * 100)) + (if (w.capped) " за всю історію" else " за місяць"),
                    12.sp, if (g >= 0) pos else neg, maxLines = 1)
            } else T(if (vm.ibkr.busy) "Синхронізація…" else "Графіки з'являться після синхронізації", 12.sp, C.n500, maxLines = 1)
        }
        T("Графіки ›", 13.sp, C.accent400, FontWeight.Medium)
    }
}
