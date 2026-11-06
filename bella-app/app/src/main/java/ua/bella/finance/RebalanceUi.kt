package ua.bella.finance

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun pctText(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else "%.1f".format(v).replace('.', ',')

/** «Цільовий розподіл і ребалансування» в «Інвестиціях»: зараз / ціль для кожної групи і скільки докупити чи зменшити. */
@Composable
fun RebalanceSection(vm: BellaViewModel, values: Map<String, Double>) {
    val rows = rebalance(values, vm.data.targets)
    var edit by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(mapOf<String, String>()) }
    Column {
        SectionHead("Цільовий розподіл і ребалансування", if (edit) "Скасувати" else "Змінити цілі") {
            if (!edit) draft = rows.associate { it.group to if (it.targetPct > 0) pctText(it.targetPct) else "" }
            edit = !edit
        }
        if (edit) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                (GROUPS.map { it.first } + rows.map { it.group }).distinct().forEach { g ->
                    Field("$g, % портфеля", draft[g] ?: "", Modifier.fillMaxWidth(), numeric = true) { draft = draft + (g to it) }
                }
                val sum = draft.values.sumOf { parseNum(it) }
                T("Разом: ${pctText(sum)}%" + if (Math.abs(sum - 100) > 0.05) " — сума має бути 100%" else "", 12.sp, if (Math.abs(sum - 100) > 0.05) C.neg else C.n500)
                Btn("Зберегти цілі", true, Modifier.fillMaxWidth()) { vm.setTargets(draft.mapValues { parseNum(it.value) }); edit = false }
            }
            return@Column
        }
        val tsum = vm.data.targets.values.sum()
        rows.forEach { r ->
            Div()
            Column(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    T(r.group, 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    T("зараз ${Math.round(r.nowPct)}% · ціль ${pctText(r.targetPct)}%", 12.sp, C.n500, maxLines = 1)
                }
                T(when { r.ok -> "У нормі"; r.diffUah > 0 -> "Докупити на ${vm.m(r.diffUah)} ${vm.sym}"; else -> "Зменшити на ${vm.m(-r.diffUah)} ${vm.sym}" }, 12.sp, if (r.ok) C.n500 else C.accent400, FontWeight.Medium, Modifier.padding(top = 2.dp))
            }
        }
        if (Math.abs(tsum - 100) > 0.05) T("Сума цілей ${pctText(tsum)}%, а не 100%: поради неточні. Натисніть «Змінити цілі».", 11.sp, C.neg, modifier = Modifier.padding(top = 6.dp))
        else T("База — вартість інвестицій без готівки. «У нормі» — відхилення менше 1% портфеля.", 11.sp, C.n500, modifier = Modifier.padding(top = 6.dp))
    }
}
