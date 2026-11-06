package ua.bella.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun pct(v: Double) = String.format(java.util.Locale.US, "%.1f", v * 100).replace('.', ',') + "%"

@Composable
private fun Collapsible(title: String, startOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(startOpen) }
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, C.divider, shape)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            T(title, 14.sp, weight = FontWeight.Medium, modifier = Modifier.weight(1f)); Ico(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, C.n500, 18.dp)
        }
        if (open) Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun ResultRow(label: String, value: String, color: Color = C.text, note: String = "") {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { T(label, 12.sp, C.n500); if (note.isNotEmpty()) T(note, 10.sp, C.n500) }
        T(value, 15.sp, color, FontWeight.Medium, maxLines = 1)
    }
}

/** Калькулятор складного відсотка з інфляцією, комісіями й податком (як у вебверсії). */
@Composable
fun CompoundSection(vm: BellaViewModel, portfolioUah: Double) {
    val c = vm.compIn
    val defInfl = if (vm.cur == "UAH") "8" else "3"
    fun v(k: String, d: String) = c[k] ?: d
    Collapsible("Калькулятор складного відсотка") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("Початковий капітал (${vm.sym})", v("start", "100000"), Modifier.weight(1f), numeric = true) { vm.setComp("start", it) }
            Field("Внесок на місяць", v("monthly", "5000"), Modifier.weight(1f), numeric = true) { vm.setComp("monthly", it) }
        }
        T("↳ підставити вартість портфеля", 12.sp, C.accent400, modifier = Modifier.clickable { vm.setComp("start", Math.round(portfolioUah / vm.rate).toString()) })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("Дохідність, % річних", v("rate", "10"), Modifier.weight(1f), numeric = true) { vm.setComp("rate", it) }
            Field("Років (1–50)", v("years", "10"), Modifier.weight(1f), numeric = true) { vm.setComp("years", it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("Інфляція, % на рік", v("infl", defInfl), Modifier.weight(1f), numeric = true) { vm.setComp("infl", it) }
            Field("Комісії, % на рік", v("fees", "0.2"), Modifier.weight(1f), numeric = true) { vm.setComp("fees", it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) { T("Податок при виході", 12.sp, C.n500, modifier = Modifier.padding(bottom = 4.dp)); Seg(listOf("0" to "0% ОВДП", "23" to "23%"), v("tax", "23")) { vm.setComp("tax", it) } }
            Field("Ріст внеску, %/рік", v("growth", "0"), Modifier.weight(1f), numeric = true) { vm.setComp("growth", it) }
        }
        val r = compound(parseNum(v("start", "100000")), parseNum(v("monthly", "5000")), parseNum(v("rate", "10")) / 100, parseNum(v("years", "10")).toInt(),
            parseNum(v("infl", defInfl)) / 100, parseNum(v("fees", "0.2")).coerceIn(0.0, 20.0) / 100, parseNum(v("tax", "23")) / 100, parseNum(v("growth", "0")) / 100)
        fun mm(x: Double) = (if (vm.hide) "••••" else fmtInt(x)) + " " + vm.sym
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ResultRow("Внесено", mm(r.deposits))
            ResultRow("Прибуток (після комісій)", mm(r.profit), if (r.profit >= 0) C.pos else C.neg)
            ResultRow("Разом (до податку)", mm(r.total))
            ResultRow("На руки", mm(r.net), C.accent300, if (r.tax > 0) "податок −${mm(r.tax)}" else "без податку")
            ResultRow("У сьогоднішніх грошах", mm(r.real), GoldC, "реальна дохідність ${pct(r.realRate)}/рік")
        }
        T("Відсотки нараховуються щомісяця за ефективною річною ставкою, внески — наприкінці місяця. Комісії зменшують дохідність, податок береться з прибутку при виході.", 11.sp, C.n500)
    }
}

/** Прогноз фінансової незалежності: коли пасивний дохід покриє бажану суму (у сьогоднішніх грошах). */
@Composable
fun FireSection(vm: BellaViewModel, capitalUah: Double, avgYieldPct: Double) {
    val f = vm.fireIn
    val defs = mapOf(
        "cap" to Math.round(capitalUah / vm.rate).toString(), "monthly" to "0",
        "yield" to String.format(java.util.Locale.US, "%.1f", avgYieldPct), "infl" to (if (vm.cur == "UAH") "8" else "3"),
        "swr" to "4", "target" to Math.round(vm.avgMonthlyExpenseUah() / vm.rate).toString(),
    )
    fun v(k: String) = f[k] ?: defs[k] ?: ""
    fun lbl(k: String, t: String) = t + if (f.containsKey(k)) " ✎" else ""
    Collapsible("Прогноз фінансової незалежності", startOpen = true) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(lbl("cap", "Капітал (${vm.sym})"), v("cap"), Modifier.weight(1f), numeric = true) { vm.setFire("cap", it) }
            Field(lbl("monthly", "Внесок на місяць"), v("monthly"), Modifier.weight(1f), numeric = true) { vm.setFire("monthly", it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(lbl("yield", "Дохідність, % річних"), v("yield"), Modifier.weight(1f), numeric = true) { vm.setFire("yield", it) }
            Field(lbl("infl", "Інфляція, %"), v("infl"), Modifier.weight(1f), numeric = true) { vm.setFire("infl", it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(lbl("swr", "Ставка знімання, %"), v("swr"), Modifier.weight(1f), numeric = true) { vm.setFire("swr", it) }
            Field(lbl("target", "Бажаний дохід/міс"), v("target"), Modifier.weight(1f), numeric = true) { vm.setFire("target", it) }
        }
        T("↺ Скинути капітал, дохідність і ціль до автоматичних", 12.sp, C.accent400, modifier = Modifier.clickable { vm.resetFire() })
        val r = fireProjection(parseNum(v("cap")).coerceAtLeast(0.0), parseNum(v("monthly")).coerceAtLeast(0.0), parseNum(v("yield")) / 100,
            parseNum(v("infl")) / 100, parseNum(v("swr")).coerceIn(0.0, 100.0) / 100, parseNum(v("target")).coerceAtLeast(0.0))
        fun mm(x: Double) = (if (vm.hide) "••••" else fmtInt(x)) + " " + vm.sym
        val (head, col) = when (r.status) {
            FireStatus.NO_TARGET -> "Вкажіть бажаний дохід" to C.n500
            FireStatus.NO_SWR -> "Вкажіть ставку знімання" to C.n500
            FireStatus.DONE -> "Ціль уже досягнута" to C.pos
            FireStatus.UNREACHABLE -> "Недосяжно: дохідність не перекриває інфляцію, а внесків немає" to C.neg
            FireStatus.REACHED -> "Через ${fmtYearsMonths(r.months)} · ≈ ${monthNameLower(java.time.YearMonth.now().plusMonths(r.months.toLong()).toString())}" to C.pos
            FireStatus.TOO_LONG -> "Понад 50 років — збільшіть внесок або дохідність" to GoldC
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            T(head, 15.sp, col, FontWeight.Medium)
            ResultRow("Можна знімати зараз / міс", mm(r.incomeNow))
            ResultRow("Потрібний капітал", if (r.capitalNeeded > 0) mm(r.capitalNeeded) else "—", note = "у сьогоднішніх грошах")
            if (r.status == FireStatus.REACHED || r.status == FireStatus.DONE) ResultRow("Ціль у грошах на дату досягнення", mm(r.targetNominal))
            ResultRow("Реальна дохідність", pct(r.realRate) + "/рік")
        }
        T("Усе в сьогоднішніх цінах: дохідність очищено від інфляції, внесок і ціль індексуються на інфляцію. ✎ — значення, змінене вручну; решта підставляється з портфеля й витрат.", 11.sp, C.n500)
    }
}
