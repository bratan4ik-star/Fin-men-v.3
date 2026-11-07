package ua.bella.finance

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.atan2

/** Горизонтальний вибір популярних випусків ОВДП. */
@Composable
fun OvdpPresetsRow(
    currentIsin: String,
    items: List<OvdpPreset> = activeOvdpPresets(LocalDate.parse(TODAY)),
    title: String = "Приклади випусків",
    note: String = "звірте з Мінфіном",
    onSelect: (OvdpPreset) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            T(title, 12.sp, C.n500, FontWeight.Medium)
            T(note, 11.sp, C.n500)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items.forEach { preset ->
                val isSelected = preset.isin.equals(currentIsin, ignoreCase = true)
                val bg = if (isSelected) C.accent.copy(alpha = 0.25f) else C.surface
                val border = if (isSelected) C.accent else C.divider
                val textCol = if (isSelected) C.accent else C.text
                val curCol = if (preset.cur == "USD") BlueC else if (preset.cur == "EUR") GoldC else C.pos

                Column(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(bg)
                        .border(1.dp, border, RoundedCornerShape(8.dp))
                        .clickable { onSelect(preset) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        T(preset.name, 12.sp, textCol, FontWeight.SemiBold)
                        Box(Modifier.clip(RoundedCornerShape(4.dp)).background(curCol.copy(alpha = 0.2f)).padding(horizontal = 4.dp, vertical = 1.dp)) {
                            T(preset.cur, 9.sp, curCol, FontWeight.Bold)
                        }
                    }
                    T(preset.desc, 10.sp, C.n500, maxLines = 1)
                }
            }
        }
    }
}

/** Картка податкової переваги ОВДП над банківським депозитом (0% vs 23%). */
@Composable
fun DepositAdvantageCard(
    ytm: Double,
    cur: String,
    invAmount: Double
) {
    if (ytm <= 0) return
    val depEq = ytm / 0.77
    val extraYield = depEq - ytm
    val sign = curSign(cur)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(C.surface)
            .border(1.dp, C.divider, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Ico(Icons.Outlined.Shield, C.pos, 16.dp)
                T("Податкова перевага ОВДП", 12.sp, C.n500, FontWeight.Medium)
            }
            Box(Modifier.clip(RoundedCornerShape(4.dp)).background(C.pos.copy(alpha = 0.2f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                T("0% податків", 11.sp, C.pos, FontWeight.Bold)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Column {
                T("Еквівалент депозиту в банку", 11.sp, C.n500)
                T("%.2f%%".format(depEq), 22.sp, C.pos, FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.End) {
                T("Ваша ставка ОВДП", 11.sp, C.n500)
                T("%.2f%%".format(ytm), 15.sp, C.text, FontWeight.Medium)
            }
        }

        T(
            "Для отримання тих самих %.2f%% на банківському депозиті потрібна ставка %.2f%% річних, оскільки з депозиту утримується 18%% ПДФО та 5%% військового збору (разом 23%%).".format(ytm, depEq),
            11.sp,
            C.n500,
            lineHeight = 14.sp
        )

        if (invAmount > 0) {
            val taxSaved = invAmount * (ytm / 100.0) * (0.23 / 0.77)
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                T("Заощаджено на податках за рік:", 11.sp, C.n500)
                T("+${fmtInt(taxSaved)} $sign", 11.sp, C.pos, FontWeight.SemiBold)
            }
        }
    }
}

/** Інтерактивний стовпчиковий графік помісячного грошового потоку виплат ОВДП. */
@Composable
fun OvdpCashFlowChart(
    months: List<MonthCashFlow>,
    selectedYm: java.time.YearMonth?,
    onSelectMonth: (java.time.YearMonth) -> Unit
) {
    if (months.isEmpty()) return

    val maxTotal = months.maxOfOrNull { it.totalUah }?.takeIf { it > 0 } ?: 1.0

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            T("Графік виплат (купони + погашення)", 12.sp, C.n500, FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(C.accent))
                    T("Купон", 10.sp, C.n500)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(BlueC))
                    T("Тіло", 10.sp, C.n500)
                }
            }
        }

        BoxWithConstraints(Modifier.fillMaxWidth().height(160.dp)) {
            val totalWidth = maxWidth
            val barCount = months.size
            val barSpacing = 6.dp
            val availableWidth = totalWidth - barSpacing * (barCount - 1)
            val barWidth = (availableWidth / barCount).coerceIn(18.dp, 44.dp)

            Row(
                Modifier.fillMaxSize().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(barSpacing),
                verticalAlignment = Alignment.Bottom
            ) {
                months.forEach { m ->
                    val isSelected = m.ym == selectedYm
                    val couponRatio = (m.couponUah / maxTotal).toFloat().coerceIn(0f, 1f)
                    val principalRatio = (m.principalUah / maxTotal).toFloat().coerceIn(0f, 1f)
                    val totalRatio = ((m.couponUah + m.principalUah) / maxTotal).toFloat().coerceIn(0.04f, 1f)

                    Column(
                        Modifier
                            .width(barWidth)
                            .fillMaxHeight()
                            .clickable { onSelectMonth(m.ym) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        // Сума зверху
                        if (m.totalUah > 0) {
                            val shortAmt = if (m.totalUah >= 1_000_000) "%.1fМ".format(m.totalUah / 1_000_000)
                            else if (m.totalUah >= 1000) "${Math.round(m.totalUah / 1000)}к"
                            else "${Math.round(m.totalUah)}"
                            T(shortAmt, 9.sp, if (isSelected) C.accent else C.n500, maxLines = 1)
                        } else {
                            Spacer(Modifier.height(12.dp))
                        }

                        Spacer(Modifier.height(4.dp))

                        // Стовпчик з 2 сегментів
                        Box(
                            Modifier
                                .width(barWidth)
                                .weight(1f, fill = false)
                                .height((110 * totalRatio).dp)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .border(
                                    if (isSelected) 1.5.dp else 0.dp,
                                    if (isSelected) Color.White else Color.Transparent,
                                    RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
                                )
                        ) {
                            Column(Modifier.fillMaxSize()) {
                                // Купон (верхній сегмент)
                                if (couponRatio > 0) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .weight(couponRatio)
                                            .background(C.accent)
                                    )
                                }
                                // Погашення номіналу (нижній сегмент)
                                if (principalRatio > 0) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .weight(principalRatio)
                                            .background(BlueC)
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(6.dp))

                        // Підпис місяця
                        T(
                            m.label,
                            9.sp,
                            if (isSelected) C.accent else C.n500,
                            FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/** Розділ зведеного календаря портфеля ОВДП. */
@Composable
fun OvdpPortfolioCalendarSection(
    vm: BellaViewModel,
    today: LocalDate = LocalDate.parse(TODAY)
) {
    val ovdpPositions = vm.data.pos.filter { it.group == "ОВДП" && it.qty > 0 }
    val cashFlows = remember(vm.data.pos, vm.ratesTick, vm.nbu) {
        buildOvdpPortfolioCashFlow(vm.data.pos, RATES, today, vm.nbuIndex)
    }

    if (ovdpPositions.isEmpty()) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(C.surface)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Ico(Icons.Outlined.AccountBalance, C.n500, 36.dp)
            T("У портфелі ще немає доданих ОВДП", 14.sp, C.text, FontWeight.Medium)
            T(
                "Розрахуйте будь-який випуск у вкладці «Калькулятор» та натисніть «Додати в інвестиції», щоб бачити календар надходжень по всіх паперах.",
                12.sp,
                C.n500,
                lineHeight = 16.sp
            )
        }
        return
    }

    var selectedYm by remember(cashFlows) {
        mutableStateOf(cashFlows.firstOrNull()?.ym)
    }

    val totalCoupons = cashFlows.sumOf { it.couponUah }
    val totalPrincipal = cashFlows.sumOf { it.principalUah }
    val totalSum = totalCoupons + totalPrincipal
    // середнє за весь проміжок від поточного місяця до останньої виплати (а не лише за місяці, де є виплати)
    val monthsCount = cashFlows.lastOrNull()?.let { java.time.temporal.ChronoUnit.MONTHS.between(java.time.YearMonth.from(today), it.ym).toInt() + 1 }?.coerceAtLeast(1) ?: 1
    val avgMonthly = totalCoupons / monthsCount

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // звідки графік: офіційний графік НБУ (з датою оновлення кешу) або розрахунок
        val official = ovdpPositions.count { vm.nbuIndex.containsKey(posIsin(it)) }
        T(if (official > 0) "Графік НБУ для $official з ${ovdpPositions.size} поз. · довідник від ${fmtSavedAt(vm.nbu.updatedAt)}" + (if (official < ovdpPositions.size) "; решта — розрахунково" else "")
          else "Виплати розраховано за датами й ставкою позицій. Щоб брати офіційний графік, виберіть випуск з довідника НБУ у формі позиції.", 11.sp, C.n500)
        // Підсумкові метрики портфеля ОВДП
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(C.surface)
                    .padding(12.dp)
            ) {
                T("Купони (дохід)", 11.sp, C.n500)
                T(vm.m(totalCoupons) + " " + vm.sym, 18.sp, C.pos, FontWeight.Bold)
                T("≈ ${vm.m(avgMonthly)} ${vm.sym}/міс", 10.sp, C.n500)
            }
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(C.surface)
                    .padding(12.dp)
            ) {
                T("Повернення номіналу", 11.sp, C.n500)
                T(vm.m(totalPrincipal) + " " + vm.sym, 18.sp, BlueC, FontWeight.Bold)
                T("разом ${vm.m(totalSum)} ${vm.sym}", 10.sp, C.n500)
            }
        }

        // Візуальна гістограма
        OvdpCashFlowChart(
            months = cashFlows,
            selectedYm = selectedYm,
            onSelectMonth = { selectedYm = it }
        )

        // Деталізація вибраного місяця
        val activeMonth = cashFlows.firstOrNull { it.ym == selectedYm } ?: cashFlows.firstOrNull()
        if (activeMonth != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(C.surface)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    T("Виплати у ${activeMonth.label}", 13.sp, C.text, FontWeight.SemiBold)
                    T("Разом: ${vm.m(activeMonth.totalUah)} ${vm.sym}", 13.sp, C.accent, FontWeight.Bold)
                }

                Div()

                activeMonth.items.forEach { item ->
                    val isRedemp = item.isRedemption
                    val kindCol = if (isRedemp) BlueC else C.pos
                    val sign = curSign(item.cur)

                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(Modifier.weight(1.2f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Ico(if (isRedemp) Icons.Outlined.AccountBalance else Icons.Outlined.Savings, kindCol, 16.dp)
                            Column {
                                T(item.isinOrName, 12.sp, maxLines = 1)
                                T(
                                    "%02d.%02d · %s".format(item.date.dayOfMonth, item.date.monthValue, item.kind),
                                    10.sp,
                                    C.n500
                                )
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            T("${fmt2(item.amount)} $sign", 12.sp, kindCol, FontWeight.SemiBold)
                            if (item.cur != "UAH") {
                                T("≈ ${vm.m(item.amountUah)} ₴", 10.sp, C.n500)
                            }
                        }
                    }
                }
            }
        }

        // Картка налаштування та статусу сповіщень WorkManager
        OvdpReminderCard(vm)
    }
}

/** Картка статусу фонових нагадувань WorkManager про виплати купонів та погашення ОВДП. */
@Composable
fun OvdpReminderCard(vm: BellaViewModel) {
    val askPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        vm.setRemind(true, ok)
    }
    val nextEvent = vm.nextOvdpEvent()
    val isRemindOn = vm.remindOn

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(C.surface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Ico(
                    if (isRemindOn) Icons.Outlined.NotificationsActive else Icons.Outlined.NotificationsOff,
                    if (isRemindOn) C.accent else C.n500,
                    20.dp
                )
                Column {
                    T("Нагадування про виплати", 13.sp, C.text, FontWeight.SemiBold)
                    T(
                        if (isRemindOn) "Щоденна перевірка о 09:00 · за 3 дні й у день виплати" else "Нагадування вимкнено",
                        11.sp,
                        if (isRemindOn) C.pos else C.n500
                    )
                }
            }

            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isRemindOn) C.accent.copy(alpha = 0.2f) else C.bg)
                    .border(1.dp, if (isRemindOn) C.accent else C.divider, RoundedCornerShape(6.dp))
                    .clickable {
                        if (isRemindOn) {
                            vm.setRemind(false)
                        } else {
                            if (Build.VERSION.SDK_INT >= 33) {
                                askPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                vm.setRemind(true, true)
                            }
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                T(
                    if (isRemindOn) "Увімкнено" else "Увімкнути",
                    12.sp,
                    if (isRemindOn) C.accent else C.n400,
                    FontWeight.Medium
                )
            }
        }

        if (nextEvent != null) {
            val sign = curSign(nextEvent.cur)
            val leftDays = ChronoUnit.DAYS.between(LocalDate.parse(TODAY), nextEvent.date)
            val what = if (nextEvent.maturity) "погашення" else "купон"
            val leftText = if (leftDays == 0L) "сьогодні" else "через $leftDays ${dayWord(leftDays)}"

            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(C.bg)
                    .padding(10.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        T("Найближча виплата ($leftText):", 10.sp, C.n500)
                        T(
                            "${nextEvent.name} ($what)",
                            12.sp,
                            C.text,
                            FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        T("${fmt2(nextEvent.total)} $sign", 12.sp, C.pos, FontWeight.Bold)
                        T(ddmmyyyy(nextEvent.date.toString()), 10.sp, C.n500)
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            T(
                "Перевірка щодня близько 9:00, зокрема після перезавантаження телефону. Android може трохи зсунути час, щоб берегти батарею.",
                10.sp,
                C.n500,
                lineHeight = 13.sp,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, C.divider, RoundedCornerShape(6.dp))
                    .clickable { vm.testOvdpNotification() }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Ico(Icons.Outlined.Send, C.accent, 14.dp)
                    T("Тест", 11.sp, C.accent, FontWeight.Medium)
                }
            }
        }
    }
}

// ================= КРУГОВА ДІАГРАМА РОЗПОДІЛУ ОВДП =================

data class OvdpSlice(
    val id: String,
    val title: String,
    val valueUah: Double,
    val percentage: Double,
    val color: Color,
    val count: Int,
    val positions: List<Pos>,
    val subtitle: String = "",
    val extra: String = ""
)

/** Розрахунок часток ОВДП за валютами (UAH, USD, EUR, PLN). */
fun computeOvdpCurrencyDistribution(
    positions: List<Pos>,
    rates: Map<String, Double>
): List<OvdpSlice> {
    val ovdp = positions.filter { it.group == "ОВДП" && it.qty > 0 }
    val total = ovdp.sumOf { posValueUah(it, rates) }
    if (total <= 0) return emptyList()

    val groups = ovdp.groupBy { it.cur.uppercase().ifEmpty { "UAH" } }
    val colorMap = mapOf(
        "UAH" to Color(0xFF9184D9), // Bella Purple
        "USD" to Color(0xFF38BDF8), // Sky Blue
        "EUR" to Color(0xFF34D399), // Mint Emerald
        "PLN" to Color(0xFFFBBF24)  // Amber
    )
    val namesMap = mapOf(
        "UAH" to "Гривня (UAH ₴)",
        "USD" to "Долар США (USD $)",
        "EUR" to "Євро (EUR €)",
        "PLN" to "Польський злотий (PLN)"
    )

    return groups.map { (cur, list) ->
        val sumUah = list.sumOf { posValueUah(it, rates) }
        val pct = (sumUah / total) * 100.0
        val originalSum = list.sumOf { it.qty * (if (it.now > 0) it.now else it.buy) }
        val sub = if (cur != "UAH") "≈ ${fmt2(originalSum)} ${curSign(cur)}" else "${list.size} " + plural(list.size, "випуск", "випуски", "випусків")
        OvdpSlice(
            id = cur,
            title = namesMap[cur] ?: cur,
            valueUah = sumUah,
            percentage = pct,
            color = colorMap[cur] ?: Color(0xFFA78BFA),
            count = list.size,
            positions = list,
            subtitle = sub,
            extra = if (cur != "UAH") "Курс НБУ: ${fmt2(rates[cur] ?: 1.0)} ₴" else "Базова валюта"
        )
    }.sortedByDescending { it.valueUah }
}

/** Розрахунок часток ОВДП за строками до погашення. */
fun computeOvdpMaturityDistribution(
    positions: List<Pos>,
    rates: Map<String, Double>,
    today: LocalDate = LocalDate.parse(TODAY)
): List<OvdpSlice> {
    val ovdp = positions.filter { it.group == "ОВДП" && it.qty > 0 }
    val total = ovdp.sumOf { posValueUah(it, rates) }
    if (total <= 0) return emptyList()

    val b1 = mutableListOf<Pos>() // <= 180 d
    val b2 = mutableListOf<Pos>() // 181..365 d
    val b3 = mutableListOf<Pos>() // 366..730 d
    val b4 = mutableListOf<Pos>() // > 730 d

    ovdp.forEach { p ->
        val matDate = pd(p.mat) ?: today
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, matDate)
        when {
            days <= 180 -> b1.add(p)
            days <= 365 -> b2.add(p)
            days <= 730 -> b3.add(p)
            else -> b4.add(p)
        }
    }

    val buckets = listOf(
        Triple("m1", "До 6 місяців (короткі)", b1) to (Color(0xFFF472B6) to "найвища ліквідність"),
        Triple("m2", "6–12 місяців (до 1 року)", b2) to (Color(0xFFFBBF24) to "короткострокові"),
        Triple("m3", "1–2 роки (середньострокові)", b3) to (Color(0xFF60A5FA) to "фіксація ставок"),
        Triple("m4", "Понад 2 роки (довгострокові)", b4) to (Color(0xFF9184D9) to "довгі облігації")
    )

    return buckets.mapNotNull { (info, styling) ->
        val (id, title, list) = info
        val (color, sub) = styling
        if (list.isEmpty()) return@mapNotNull null
        val sumUah = list.sumOf { posValueUah(it, rates) }
        val pct = (sumUah / total) * 100.0
        OvdpSlice(
            id = id,
            title = title,
            valueUah = sumUah,
            percentage = pct,
            color = color,
            count = list.size,
            positions = list,
            subtitle = sub
        )
    }.sortedByDescending { it.valueUah }
}

/** Інтерактивна кругова діаграма (Donut Chart) розподілу ОВДП, побудована засобами Compose Canvas. */
@Composable
fun OvdpDonutChart(
    slices: List<OvdpSlice>,
    totalUah: Double,
    selectedSlice: OvdpSlice?,
    onSelectSlice: (OvdpSlice?) -> Unit,
    modifier: Modifier = Modifier
) {
    if (slices.isEmpty()) return

    Box(
        modifier = modifier
            .size(240.dp)
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        // Compose Canvas малювання сегментів кругової діаграми
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(slices, selectedSlice) {
                    detectTapGestures { offset ->
                        val centerX = size.width / 2f
                        val centerY = size.height / 2f
                        val dx = offset.x - centerX
                        val dy = offset.y - centerY
                        val dist = Math.sqrt((dx * dx + dy * dy).toDouble())
                        val outerRadius = Math.min(centerX, centerY) * 0.95f
                        val innerRadius = outerRadius - 38.dp.toPx()

                        // Перевіряємо, чи клік у кільці діаграми
                        if (dist in innerRadius..outerRadius) {
                            var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                            if (angle < 0) angle += 360f
                            // Оскільки починаємо з -90° (12:00)
                            val normAngle = (angle + 90f) % 360f

                            var currentSweepStart = 0f
                            var tapped: OvdpSlice? = null
                            for (slice in slices) {
                                val sweep = (slice.percentage.toFloat() / 100f) * 360f
                                if (normAngle in currentSweepStart..(currentSweepStart + sweep)) {
                                    tapped = slice
                                    break
                                }
                                currentSweepStart += sweep
                            }

                            if (tapped != null) {
                                if (selectedSlice?.id == tapped.id) onSelectSlice(null)
                                else onSelectSlice(tapped)
                            }
                        } else if (dist < innerRadius) {
                            // Клік у центрі скидає вибір
                            onSelectSlice(null)
                        }
                    }
                }
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = Math.min(size.width, size.height) / 2f * 0.82f
            val baseStroke = 26.dp.toPx()
            val selectedStroke = 34.dp.toPx()

            var startAngle = -90f // 12 годин
            val gap = if (slices.size > 1) 2.5f else 0f

            slices.forEach { slice ->
                val isSelected = selectedSlice?.id == slice.id
                val sweep = (slice.percentage.toFloat() / 100f) * 360f
                val sweepWithGap = (sweep - gap).coerceAtLeast(0.5f)

                // Тіньове/акцентне підсвічування для обраного сегмента
                if (isSelected) {
                    drawArc(
                        color = Color.White.copy(alpha = 0.25f),
                        startAngle = startAngle + gap / 2f - 1f,
                        sweepAngle = sweepWithGap + 2f,
                        useCenter = false,
                        topLeft = Offset(center.x - baseRadius, center.y - baseRadius),
                        size = Size(baseRadius * 2, baseRadius * 2),
                        style = Stroke(width = selectedStroke + 8.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                drawArc(
                    color = slice.color,
                    startAngle = startAngle + gap / 2f,
                    sweepAngle = sweepWithGap,
                    useCenter = false,
                    topLeft = Offset(center.x - baseRadius, center.y - baseRadius),
                    size = Size(baseRadius * 2, baseRadius * 2),
                    style = Stroke(
                        width = if (isSelected) selectedStroke else baseStroke,
                        cap = StrokeCap.Round
                    )
                )

                startAngle += sweep
            }
        }

        // Центральний інформаційний блок усередині Donut
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            if (selectedSlice != null) {
                T(
                    selectedSlice.title.substringBefore("(").trim(),
                    10.sp,
                    C.n500,
                    FontWeight.Medium,
                    maxLines = 1
                )
                T(
                    "${fmtInt(selectedSlice.valueUah)} ₴",
                    15.sp,
                    C.text,
                    FontWeight.Bold,
                    maxLines = 1
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Dot(selectedSlice.color)
                    T(
                        "%.1f%%".format(selectedSlice.percentage),
                        11.sp,
                        selectedSlice.color,
                        FontWeight.SemiBold
                    )
                }
            } else {
                T("Всього ОВДП", 10.sp, C.n500, FontWeight.Medium)
                T(
                    "${fmtInt(totalUah)} ₴",
                    15.sp,
                    C.text,
                    FontWeight.Bold,
                    maxLines = 1
                )
                T("${slices.size} сектори", 10.sp, C.accent, FontWeight.Medium)
            }
        }
    }
}

/** Повний розділ аналітики розподілу ОВДП за валютою та термінами погашення. */
@Composable
fun OvdpDistributionSection(
    vm: BellaViewModel,
    today: LocalDate = LocalDate.parse(TODAY)
) {
    val ovdpPositions = vm.data.pos.filter { it.group == "ОВДП" && it.qty > 0 }
    val totalUah = ovdpPositions.sumOf { posValueUah(it, RATES) }

    if (ovdpPositions.isEmpty()) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(C.surface)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Ico(Icons.Outlined.PieChart, C.n500, 36.dp)
            T("У портфелі ще немає доданих ОВДП", 14.sp, C.text, FontWeight.Medium)
            T(
                "Додайте випуски через калькулятор, щоб бачити кругову діаграму розподілу за валютою та строками погашення.",
                12.sp,
                C.n500
            )
        }
        return
    }

    var distMode by remember { mutableStateOf(0) } // 0 = за валютою, 1 = за термінами
    var selectedSliceId by remember(distMode) { mutableStateOf<String?>(null) }

    val currencySlices = remember(ovdpPositions, vm.ratesTick) {
        computeOvdpCurrencyDistribution(ovdpPositions, RATES)
    }
    val maturitySlices = remember(ovdpPositions, vm.ratesTick) {
        computeOvdpMaturityDistribution(ovdpPositions, RATES, today)
    }

    val currentSlices = if (distMode == 0) currencySlices else maturitySlices
    val activeSlice = currentSlices.find { it.id == selectedSliceId }

    // Розрахунок аналітичних метрик портфеля
    // середня ставка лише гривневих ОВДП: змішувати гривневі й валютні ставки в одне число некоректно
    val uahOvdp = ovdpPositions.filter { it.cur == "UAH" }
    val uahTotal = uahOvdp.sumOf { posValueUah(it, RATES) }
    val weightedYield = if (uahTotal > 0) uahOvdp.sumOf { posValueUah(it, RATES) * it.rate } / uahTotal else 0.0

    val weightedDays = if (totalUah > 0) {
        ovdpPositions.sumOf { p ->
            val d = java.time.temporal.ChronoUnit.DAYS.between(today, pd(p.mat) ?: today).coerceAtLeast(0)
            d * posValueUah(p, RATES)
        } / totalUah
    } else 0.0

    val fxValueUah = ovdpPositions.filter { it.cur != "UAH" }.sumOf { posValueUah(it, RATES) }
    val fxShare = if (totalUah > 0) (fxValueUah / totalUah) * 100.0 else 0.0

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Перемикач виміру аналітики: Валюта vs Терміни погашення
        Seg(
            listOf(0 to "За валютою", 1 to "За термінами"),
            distMode
        ) {
            distMode = it
            selectedSliceId = null
        }

        // Картка з круговою діаграмою (Donut Chart)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(C.surface)
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                T(
                    if (distMode == 0) "Розподіл за валютою номіналу" else "Дюрація: розподіл за строками погашення",
                    12.sp,
                    C.n500,
                    FontWeight.Medium
                )
                if (activeSlice != null) {
                    T("Скинути", 11.sp, C.accent, modifier = Modifier.clickable { selectedSliceId = null })
                }
            }

            OvdpDonutChart(
                slices = currentSlices,
                totalUah = totalUah,
                selectedSlice = activeSlice,
                onSelectSlice = { selectedSliceId = it?.id }
            )

            T(
                "Торкніться сектору на діаграмі або рядка нижче для деталей випусків",
                10.sp,
                C.n500,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        // Інтерактивна легенда та список секторів
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            T(
                if (distMode == 0) "Валютні частки в портфелі" else "Строкові кошики портфеля",
                12.sp,
                C.n500,
                FontWeight.Medium
            )

            currentSlices.forEach { slice ->
                val isSelected = slice.id == selectedSliceId
                val bg = if (isSelected) slice.color.copy(alpha = 0.14f) else C.surface
                val borderCol = if (isSelected) slice.color else C.divider

                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(bg)
                        .border(1.dp, borderCol, RoundedCornerShape(10.dp))
                        .clickable {
                            selectedSliceId = if (isSelected) null else slice.id
                        }
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Dot(slice.color)
                            Column {
                                T(slice.title, 13.sp, C.text, FontWeight.SemiBold)
                                if (slice.subtitle.isNotEmpty()) {
                                    T(slice.subtitle, 10.sp, C.n500)
                                }
                            }
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            T(
                                "${fmtInt(slice.valueUah)} ₴",
                                13.sp,
                                C.text,
                                FontWeight.Bold
                            )
                            T(
                                "%.1f%% (%d вип.)".format(slice.percentage, slice.count),
                                11.sp,
                                slice.color,
                                FontWeight.Medium
                            )
                        }
                    }

                    // Прогрес-бар частки сектору
                    Bar(
                        frac = (slice.percentage / 100.0).toFloat(),
                        color = slice.color,
                        h = 4.dp
                    )

                    // Якщо сектор обраний — показуємо список випусків, що входять до нього
                    if (isSelected) {
                        Div()
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            T("Облігації в цьому секторі:", 11.sp, C.n500, FontWeight.Medium)
                            slice.positions.forEach { p ->
                                val posVal = posValueUah(p, RATES)
                                val curSign = curSign(p.cur)
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1.2f)) {
                                        T(p.name, 12.sp, C.text, maxLines = 1)
                                        T(
                                            "Ставка: %.1f%% · до %s".format(p.rate, ddmmyy(p.mat)),
                                            10.sp,
                                            C.n500
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        T("${fmtInt(posVal)} ₴", 12.sp, C.accent, FontWeight.SemiBold)
                                        if (p.cur != "UAH") {
                                            T("${fmt2(p.qty * p.now)} $curSign", 10.sp, C.n500)
                                        } else {
                                            T("${p.qty.toInt()} шт.", 10.sp, C.n500)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Підсумкові аналітичні метрики портфеля ОВДП
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(C.surface)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                T("Аналітичні показники портфеля", 12.sp, C.n500, FontWeight.Medium)
                Ico(Icons.Outlined.Analytics, C.accent, 16.dp)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(C.bg).padding(8.dp)
                ) {
                    Column {
                        T("Сер. ставка ₴", 10.sp, C.n500)
                        T("%.2f%%".format(weightedYield), 15.sp, C.pos, FontWeight.Bold)
                    }
                }
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(C.bg).padding(8.dp)
                ) {
                    Column {
                        T("Сер. строк", 10.sp, C.n500)
                        val months = weightedDays / 30.4
                        T(
                            if (months >= 12) "%.1f р.".format(months / 12) else "%.0f міс.".format(months),
                            15.sp,
                            C.text,
                            FontWeight.Bold
                        )
                    }
                }
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(C.bg).padding(8.dp)
                ) {
                    Column {
                        T("У валюті", 10.sp, C.n500)
                        T(
                            if (fxShare > 0) "%.0f%%".format(fxShare) else "0%",
                            15.sp,
                            if (fxShare > 0) BlueC else C.accent,
                            FontWeight.Bold
                        )
                    }
                }
            }

            T(
                "Диверсифікація строків («драбина облігацій») та поєднання гривневих ОВДП з валютними забезпечує стабільний потік ліквідності та захист від девальвації.",
                11.sp,
                C.n500,
                lineHeight = 15.sp
            )
        }
    }
}
