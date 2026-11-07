package ua.bella.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.TrendingUp
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

private fun pctText(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else "%.1f".format(v).replace('.', ',')

@Composable
private fun groupColor(name: String): Color {
    val found = GROUPS.find { it.first == name }?.second
    return if (found != null) Color(found) else C.accent
}

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
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(C.surface)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                (GROUPS.map { it.first } + rows.map { it.group }).distinct().forEach { g ->
                    Field("$g, % портфеля", draft[g] ?: "", Modifier.fillMaxWidth(), numeric = true) {
                        draft = draft + (g to it)
                    }
                }
                val sum = draft.values.sumOf { parseNum(it) }
                T(
                    "Разом: ${pctText(sum)}%" + if (Math.abs(sum - 100) > 0.05) " — сума має бути 100%" else "",
                    12.sp,
                    if (Math.abs(sum - 100) > 0.05) C.neg else C.pos
                )
                Btn("Зберегти цілі", true, Modifier.fillMaxWidth()) {
                    vm.setTargets(draft.mapValues { parseNum(it.value) })
                    edit = false
                }
            }
            return@Column
        }

        val tsum = vm.data.targets.values.sum()

        rows.forEach { r ->
            val col = groupColor(r.group)
            val currentFraction = (r.nowPct / 100.0).toFloat().coerceIn(0f, 1f)
            val targetFraction = (r.targetPct / 100.0).toFloat().coerceIn(0f, 1f)

            Div()
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Заголовок групи та поточні відсотки
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Dot(col)
                        T(r.group, 14.sp, weight = FontWeight.SemiBold)
                        T("(${vm.m(r.valueUah)} ${vm.sym})", 11.sp, C.n500)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        T("зараз ${Math.round(r.nowPct)}%", 12.sp, if (r.ok) C.text else col, FontWeight.Medium)
                        T("·", 12.sp, C.n500)
                        T("ціль ${pctText(r.targetPct)}%", 12.sp, C.n500)
                    }
                }

                // Дворівневий прогрес-бар (поточна частка vs позначка цілі)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(C.surface)
                ) {
                    // Заповнення поточної частки
                    if (currentFraction > 0f) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(currentFraction)
                                .clip(RoundedCornerShape(4.dp))
                                .background(col)
                        )
                    }
                    // Позначка цільової частки (вертикальний маркер)
                    if (targetFraction > 0f) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(targetFraction)
                        ) {
                            Box(
                                Modifier
                                    .align(Alignment.CenterEnd)
                                    .width(2.5.dp)
                                    .fillMaxHeight()
                                    .background(Color.White)
                            )
                        }
                    }
                }

                // Статус та рекомендація щодо ребалансування
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when {
                        r.ok -> {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Ico(Icons.Outlined.CheckCircle, C.pos, 14.dp)
                                T("У нормі (відхилення < 1%)", 11.sp, C.pos, FontWeight.Medium)
                            }
                        }
                        r.diffUah > 0 -> {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Ico(Icons.Outlined.TrendingUp, C.accent, 14.dp)
                                T("Докупити на ${vm.m(r.diffUah)} ${vm.sym}", 12.sp, C.accent, FontWeight.SemiBold)
                            }
                        }
                        else -> {
                            T("Перевищення на ${vm.m(-r.diffUah)} ${vm.sym}", 11.sp, C.n500, FontWeight.Medium)
                        }
                    }

                    // Швидка дія при необхідності докупити ОВДП
                    if (r.group == "ОВДП" && r.diffUah > 0) {
                        T(
                            "калькулятор ОВДП →",
                            11.sp,
                            C.accent,
                            FontWeight.Medium,
                            modifier = Modifier.clickable {
                                vm.calcTab = 0
                                vm.go(Screen.Calc)
                            }
                        )
                    }
                }
            }
        }

        if (Math.abs(tsum - 100) > 0.05) {
            T(
                "Сума цілей ${pctText(tsum)}%, а не 100%: поради неточні. Натисніть «Змінити цілі».",
                11.sp,
                C.neg,
                modifier = Modifier.padding(top = 6.dp)
            )
        } else {
            T(
                "Біла позначка на смужці — ваша цільова частка. База — вартість інвестицій без готівки.",
                11.sp,
                C.n500,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}
