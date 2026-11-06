package ua.bella.finance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Точка даних динаміки капіталу з реінвестуванням купонів та без. */
data class ReinvestPoint(
    val year: Int,
    val capitalWithout: Double,
    val totalWithdrawn: Double,
    val totalValueWithout: Double, // capital + withdrawn
    val capitalWith: Double,       // compound reinvested
    val totalProfitWith: Double,
    val extraBenefit: Double       // capitalWith - totalValueWithout
)

/**
 * Моделювання ефекту складного відсотка при піврічному реінвестуванні купонів ОВДП (0% податку).
 * Порівнює:
 * А) Вилучення купонів (тіло інвестицій лишається незмінним, купони йдуть на споживання).
 * Б) Повне реінвестування купонів у нові випуски ОВДП кожні 6 місяців.
 */
fun calculateReinvestmentCurve(
    initialCapital: Double,
    couponRatePct: Double,
    monthlyAddition: Double = 0.0,
    years: Int = 10
): List<ReinvestPoint> {
    val rSemi = (couponRatePct / 100.0) / 2.0
    val points = mutableListOf<ReinvestPoint>()

    var capWithout = initialCapital
    var cumWithdrawn = 0.0
    var capWith = initialCapital
    var totalInvested = initialCapital

    // Початкова точка (Рік 0)
    points.add(
        ReinvestPoint(
            year = 0,
            capitalWithout = initialCapital,
            totalWithdrawn = 0.0,
            totalValueWithout = initialCapital,
            capitalWith = initialCapital,
            totalProfitWith = 0.0,
            extraBenefit = 0.0
        )
    )

    for (y in 1..years) {
        // Симуляція 12 місяців (два піврічні купони)
        for (m in 1..12) {
            capWithout += monthlyAddition
            capWith += monthlyAddition
            totalInvested += monthlyAddition

            // Купон кожні 6 місяців (червень та грудень)
            if (m == 6 || m == 12) {
                // Без реінвестування: купон вилучається
                val cpnWithout = capWithout * rSemi
                cumWithdrawn += cpnWithout

                // З реінвестуванням: купон купує нові ОВДП
                val cpnWith = capWith * rSemi
                capWith += cpnWith
            }
        }

        val totalValueWithout = capWithout + cumWithdrawn
        val profitWith = capWith - totalInvested
        val extra = capWith - totalValueWithout

        points.add(
            ReinvestPoint(
                year = y,
                capitalWithout = capWithout,
                totalWithdrawn = cumWithdrawn,
                totalValueWithout = totalValueWithout,
                capitalWith = capWith,
                totalProfitWith = profitWith,
                extraBenefit = extra
            )
        )
    }

    return points
}

/** Інтерактивний графік динаміки зростання капіталу (Canvas). */
@Composable
fun OvdpReinvestmentChart(
    points: List<ReinvestPoint>,
    selectedYear: Int,
    onSelectYear: (Int) -> Unit
) {
    if (points.size < 2) return

    val maxVal = points.maxOf { Math.max(it.capitalWith, it.totalValueWithout) }.coerceAtLeast(1.0)
    val minVal = points.first().capitalWithout
    val accentColor = C.accent
    val n500Color = C.n500

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            T("Траєкторія капіталу за роками", 12.sp, n500Color, FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Dot(C.accent)
                    T("З реінвестуванням", 10.sp, C.n500)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Dot(C.n500)
                    T("Без реінвестування", 10.sp, C.n500)
                }
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(C.bg)
                .padding(horizontal = 8.dp, vertical = 12.dp)
        ) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(points) {
                        detectTapGestures { offset ->
                            val n = points.size - 1
                            val step = size.width / n.toFloat()
                            val clickedIndex = (offset.x / step).toInt().coerceIn(0, n)
                            onSelectYear(points[clickedIndex].year)
                        }
                    }
            ) {
                val n = points.size - 1
                val w = size.width
                val h = size.height

                fun xFor(i: Int): Float = (i.toFloat() / n.toFloat()) * w
                fun yFor(v: Double): Float {
                    val norm = ((v - minVal) / (maxVal - minVal)).toFloat().coerceIn(0f, 1f)
                    return h - (norm * (h * 0.85f)) - (h * 0.08f)
                }

                // Сітка
                val gridLines = 4
                for (g in 0..gridLines) {
                    val gy = h * (g.toFloat() / gridLines)
                    drawLine(
                        color = Color.White.copy(alpha = 0.05f),
                        start = Offset(0f, gy),
                        end = Offset(w, gy),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                // Лінія "Без реінвестування" (сіра пунктирна або тонка)
                val pathWithout = Path()
                points.forEachIndexed { i, p ->
                    val px = xFor(i)
                    val py = yFor(p.totalValueWithout)
                    if (i == 0) pathWithout.moveTo(px, py) else pathWithout.lineTo(px, py)
                }
                drawPath(
                    path = pathWithout,
                    color = Color(0xFF6B7280),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )

                // Лінія та градієнт "З реінвестуванням" (фіолетова/акцентна)
                val pathWith = Path()
                val fillPath = Path()
                fillPath.moveTo(xFor(0), h)

                points.forEachIndexed { i, p ->
                    val px = xFor(i)
                    val py = yFor(p.capitalWith)
                    if (i == 0) {
                        pathWith.moveTo(px, py)
                        fillPath.lineTo(px, py)
                    } else {
                        pathWith.lineTo(px, py)
                        fillPath.lineTo(px, py)
                    }
                }
                fillPath.lineTo(xFor(n), h)
                fillPath.close()

                // Градієнтна підкладка
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.35f),
                            accentColor.copy(alpha = 0.02f)
                        )
                    )
                )

                drawPath(
                    path = pathWith,
                    color = accentColor,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                )

                // Маркер обраного року
                val selIndex = points.indexOfFirst { it.year == selectedYear }.takeIf { it >= 0 } ?: n
                val selX = xFor(selIndex)
                val selPoint = points[selIndex]
                val selYWith = yFor(selPoint.capitalWith)
                val selYWithout = yFor(selPoint.totalValueWithout)

                // Вертикальна риска
                drawLine(
                    color = Color.White.copy(alpha = 0.3f),
                    start = Offset(selX, 0f),
                    end = Offset(selX, h),
                    strokeWidth = 1.5.dp.toPx()
                )

                // Точки
                drawCircle(color = accentColor, radius = 5.dp.toPx(), center = Offset(selX, selYWith))
                drawCircle(color = Color.White, radius = 2.5.dp.toPx(), center = Offset(selX, selYWith))

                drawCircle(color = Color(0xFF9CA3AF), radius = 4.dp.toPx(), center = Offset(selX, selYWithout))
            }
        }

        // Мітки років по осі X
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            points.filterIndexed { i, _ -> i == 0 || i == points.size / 2 || i == points.size - 1 }.forEach { p ->
                T(
                    if (p.year == 0) "Сьогодні" else "${p.year} р.",
                    10.sp,
                    if (p.year == selectedYear) C.accent else C.n500,
                    FontWeight.Medium
                )
            }
        }
    }
}

/** Розділ симулятора реінвестування купонів ОВДП. */
@Composable
fun OvdpReinvestmentSection(vm: BellaViewModel) {
    val ovdpPositions = vm.data.pos.filter { it.group == "ОВДП" && it.qty > 0 }
    val portfolioOvdpUah = ovdpPositions.sumOf { posValueUah(it, RATES) }
    val defaultInitial = if (portfolioOvdpUah > 0) portfolioOvdpUah else 100000.0

    var initialStr by remember { mutableStateOf(Math.round(defaultInitial).toString()) }
    var rateStr by remember { mutableStateOf("16.5") }
    var monthlyStr by remember { mutableStateOf("0") }
    var yearsHorizon by remember { mutableStateOf(5) }
    var selectedYear by remember { mutableStateOf(5) }

    val initial = parseNum(initialStr).coerceAtLeast(1000.0)
    val rate = parseNum(rateStr).coerceIn(1.0, 50.0)
    val monthly = parseNum(monthlyStr).coerceAtLeast(0.0)

    val points = remember(initial, rate, monthly, yearsHorizon) {
        calculateReinvestmentCurve(initial, rate, monthly, yearsHorizon)
    }

    val activePoint = points.find { it.year == selectedYear } ?: points.last()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Ввід параметрів симуляції
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(C.surface)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                T("Параметри симуляції ОВДП", 13.sp, C.text, FontWeight.SemiBold)
                if (portfolioOvdpUah > 0) {
                    T(
                        "↳ мій портфель (${fmtInt(portfolioOvdpUah)} ₴)",
                        11.sp,
                        C.accent,
                        modifier = Modifier.clickable {
                            initialStr = Math.round(portfolioOvdpUah).toString()
                        }
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Капітал (₴)", initialStr, Modifier.weight(1.2f), numeric = true) {
                    initialStr = it
                }
                Field("Ставка ОВДП, %", rateStr, Modifier.weight(1f), numeric = true) {
                    rateStr = it
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Поповнення / міс (₴)", monthlyStr, Modifier.weight(1.2f), numeric = true) {
                    monthlyStr = it
                }
                Column(Modifier.weight(1f)) {
                    T("Горизонт (років)", 11.sp, C.n500, modifier = Modifier.padding(bottom = 2.dp))
                    Seg(
                        listOf(3 to "3 р.", 5 to "5 р.", 10 to "10 р."),
                        yearsHorizon
                    ) {
                        yearsHorizon = it
                        selectedYear = it
                    }
                }
            }
        }

        // Інтерактивний графік траєкторії капіталу
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(C.surface)
                .padding(14.dp)
        ) {
            OvdpReinvestmentChart(
                points = points,
                selectedYear = selectedYear,
                onSelectYear = { selectedYear = it }
            )
        }

        // Порівняльні картки: З реінвестуванням vs Без реінвестування на обраному році
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(C.surface)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                T(
                    "Результат через $selectedYear ${dayWord(selectedYear.toLong()).replace("днів", "років").replace("день", "рік").replace("дні", "роки")}:",
                    13.sp,
                    C.text,
                    FontWeight.SemiBold
                )
                Box(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(C.pos.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    T("+${fmtInt(activePoint.extraBenefit)} ₴ бонус", 11.sp, C.pos, FontWeight.Bold)
                }
            }

            Div()

            // Сценарій 1: З реінвестуванням ОВДП
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Dot(C.accent)
                    Column {
                        T("З реінвестуванням купонів", 13.sp, C.text, FontWeight.Medium)
                        T("кожен купон купує нові ОВДП під $rateStr%", 10.sp, C.n500)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    T("${fmtInt(activePoint.capitalWith)} ₴", 15.sp, C.accent, FontWeight.Bold)
                    T("прибуток +${fmtInt(activePoint.totalProfitWith)} ₴", 10.sp, C.pos)
                }
            }

            Div()

            // Сценарій 2: Без реінвестування
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Dot(Color(0xFF6B7280))
                    Column {
                        T("Без реінвестування (зняття)", 13.sp, C.text, FontWeight.Medium)
                        T("купони витрачаються на життя", 10.sp, C.n500)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    T("${fmtInt(activePoint.totalValueWithout)} ₴", 14.sp, C.n400, FontWeight.SemiBold)
                    T("знято купонів ${fmtInt(activePoint.totalWithdrawn)} ₴", 10.sp, C.n500)
                }
            }

            Div()

            // Пояснення податкової переваги (0% vs 23%)
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Ico(Icons.Outlined.Shield, C.pos, 16.dp)
                T(
                    "Усі отримані та реінвестовані купони на 100% звільнені від податків (0% ПДФО, 0% ВЗ), що значно прискорює ріст капіталу порівняно з депозитами.",
                    11.sp,
                    C.n500,
                    lineHeight = 14.sp
                )
            }
        }
    }
}
