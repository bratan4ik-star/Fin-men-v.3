package ua.bella.finance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Графік чистого капіталу з вибором періоду; дотик або ведення пальцем показує значення за день. */
@Composable
fun CapitalChart(vm: BellaViewModel) {
    var days by remember { mutableIntStateOf(90) }
    var sel by remember { mutableStateOf(-1) }
    val all = vm.hist
    if (all.size < 2) { T("Графік капіталу з'явиться, коли накопичиться історія: знімок зберігається щодня.", 11.sp, C.n500, modifier = Modifier.padding(top = 12.dp)); return }
    val h = histPeriod(all, days, TODAY).let { if (it.size < 2) all.takeLast(2) else it }
    val vs = h.map { vm.cv(it.v) }; val mn = vs.min(); val mx = vs.max()
    val pts = normalize(vs)
    val acc = C.accent; val grid = C.divider; val dotC = C.text
    val i = sel.takeIf { it in h.indices }
    Column(Modifier.padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            if (i != null) T("${ddmmyyyy(h[i].d)}: ${vm.m(h[i].v)} ${vm.sym}", 12.sp, weight = FontWeight.Medium)
            else { val ch = h.last().v - h.first().v
                T((if (ch >= 0) "+" else "−") + vm.m(Math.abs(ch)) + " ${vm.sym} за період", 12.sp, if (ch >= 0) C.pos else C.neg) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(30 to "1М", 90 to "3М", 365 to "1Р", 0 to "Усе").forEach { (d, l) ->
                    T(l, 11.sp, if (d == days) C.bg else C.n500, FontWeight.Medium, Modifier.clip(CircleShape).background(if (d == days) C.accent else Color.Transparent).clickable { days = d; sel = -1 }.padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Canvas(Modifier.weight(1f).height(120.dp)
                .pointerInput(h.size) { detectTapGestures { o -> sel = Math.round(o.x / size.width * (h.size - 1)).coerceIn(0, h.size - 1) } }
                .pointerInput(h.size) { detectDragGestures(onDragEnd = { sel = -1 }) { c, _ -> sel = Math.round(c.position.x / size.width * (h.size - 1)).coerceIn(0, h.size - 1) } }) {
                val dx = size.width / (pts.size - 1); val top = 6.dp.toPx(); val hh = size.height - 2 * top
                fun y(v: Double) = top + (1 - v.toFloat()) * hh
                listOf(0.0, 0.5, 1.0).forEach { g -> drawLine(grid, Offset(0f, y(g)), Offset(size.width, y(g)), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))) }
                val line = Path().apply { pts.forEachIndexed { k, v -> if (k == 0) moveTo(0f, y(v)) else lineTo(k * dx, y(v)) } }
                val fill = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
                drawPath(fill, Brush.verticalGradient(listOf(acc.copy(alpha = .28f), Color.Transparent)))
                drawPath(line, acc, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                if (i != null) { val x = i * dx; drawLine(dotC.copy(alpha = .4f), Offset(x, 0f), Offset(x, size.height), 1f); drawCircle(acc, 4.dp.toPx(), Offset(x, y(pts[i]))) }
            }
            Column(Modifier.height(120.dp).padding(start = 6.dp), verticalArrangement = Arrangement.SpaceBetween) {
                T(if (vm.hide) "••••" else short(mx), 10.sp, C.n500); T(if (vm.hide) "••••" else short(mn), 10.sp, C.n500)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            T(ddmmyy(h.first().d), 10.sp, C.n500); T("${h.size} дн.", 10.sp, C.n500); T(ddmmyy(h.last().d), 10.sp, C.n500)
        }
    }
}

/** Скорочений запис суми для осі: 1,2 млн / 350 тис. */
fun short(v: Double): String { val a = Math.abs(v); val s = if (v < 0) "−" else ""
    return s + when { a >= 1e6 -> "%.1f млн".format(a / 1e6); a >= 1e4 -> "${Math.round(a / 1e3)} тис"; else -> fmtInt(a) } }


data class Slice(val label: String, val color: Color, val value: Double)

/** Кільцева діаграма структури портфеля з легендою. */
@Composable
fun PortfolioDonut(vm: BellaViewModel, slices: List<Slice>, unit: Pair<String, String> = "клас" to "класи") {
    val ss = slices.filter { it.value > 0 }; val total = ss.sumOf { it.value }
    if (total <= 0) return
    val bg = C.bg
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(116.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = 18.dp.toPx(); val r = Size(size.width - w, size.height - w); val tl = Offset(w / 2, w / 2)
                var start = -90f
                ss.forEach { s -> val sw = (s.value / total * 360).toFloat()
                    drawArc(s.color, start, sw, false, tl, r, style = Stroke(w))
                    if (ss.size > 1) drawArc(bg, start + sw - 0.8f, 0.8f, false, tl, r, style = Stroke(w))
                    start += sw }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) { T("${ss.size}", 16.sp, weight = FontWeight.Medium); T(if (ss.size == 1) unit.first else unit.second, 10.sp, C.n500) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ss.sortedByDescending { it.value }.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(s.color))
                    T(s.label, 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    T("${sharePct(s.value, total)}%", 12.sp, C.n500)
                }
            }
        }
    }
}

/** Стовпчики доходів і витрат по місяцях; дотик до місяця показує суми. */
@Composable
fun FlowBars(vm: BellaViewModel, flows: List<MonthFlow>) {
    var sel by remember { mutableStateOf(-1) }
    val mx = flows.maxOfOrNull { Math.max(it.inc, it.exp) } ?: 0.0
    if (mx <= 0) return
    val pos = C.pos; val neg = C.neg
    val i = sel.takeIf { it in flows.indices } ?: flows.lastIndex
    val f = flows[i]
    Column(Modifier.padding(bottom = 14.dp)) {
        Label("ДОХОДИ Й ВИТРАТИ ЗА ${flows.size} МІС.")
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            T(monthLabel(f.month), 12.sp, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
            T("+" + vm.m(f.inc), 12.sp, C.pos); T("−" + vm.m(f.exp), 12.sp, C.neg)
            T((if (f.inc - f.exp >= 0) "= +" else "= −") + vm.m(Math.abs(f.inc - f.exp)) + " " + vm.sym, 12.sp, C.n500)
        }
        Canvas(Modifier.fillMaxWidth().padding(top = 8.dp).height(110.dp).pointerInput(flows.size) { detectTapGestures { o -> sel = (o.x / size.width * flows.size).toInt().coerceIn(0, flows.size - 1) } }) {
            val slot = size.width / flows.size; val bw = Math.min(slot * 0.32f, 14.dp.toPx()); val gap = 2.dp.toPx()
            flows.forEachIndexed { k, m ->
                val cx = slot * k + slot / 2; val a = if (k == i) 1f else .55f
                val hi = (m.inc / mx * size.height).toFloat(); val he = (m.exp / mx * size.height).toFloat()
                if (hi > 0) drawRoundRect(pos.copy(alpha = a), Offset(cx - gap / 2 - bw, size.height - hi), Size(bw, hi), CornerRadius(3.dp.toPx()))
                if (he > 0) drawRoundRect(neg.copy(alpha = a), Offset(cx + gap / 2, size.height - he), Size(bw, he), CornerRadius(3.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            flows.forEachIndexed { k, m -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { T(m.month.substring(5, 7), 10.sp, if (k == i) C.text else C.n500, maxLines = 1) } }
        }
    }
}
