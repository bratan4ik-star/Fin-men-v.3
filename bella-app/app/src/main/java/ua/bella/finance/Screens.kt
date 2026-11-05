package ua.bella.finance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Похідні суми, що в прототипі рахуються в renderVals(). */
class Totals(val vm: BellaViewModel) {
    val d = vm.data
    val invGroups = GROUPS.map { (g, c) ->
        val ps = d.pos.filter { it.group == g }; val v = ps.sumOf { posValueUah(it, RATES) }
        val rt = if (v > 0) ps.sumOf { posValueUah(it, RATES) * it.rate } / v else 0.0
        Triple(g, c, Triple(v, rt, ps.size))
    }
    val invT = invGroups.sumOf { it.third.first }
    val cash = d.accs.filter { !it.sav }.sumOf { vm.base(it) }
    val savT = d.accs.filter { it.sav }.sumOf { vm.base(it) }
    val goalT = d.goals.sumOf { it.saved }
    val debtSum = d.debts.filter { !it.done }.sumOf { if (it.owedToMe) it.amt else -it.amt }
    val net = cash + savT + invT + goalT + debtSum
    val novT = d.txs.filter { it.date.startsWith(CUR_MONTH) }
    val novNet = novT.sumOf { if (it.type == "income") it.amt else if (it.type == "expense") -it.amt else 0.0 }
    val passM = invGroups.sumOf { it.third.first * it.third.second / 1200 * (if (it.first == "Депозити") 0.77 else 1.0) }
}

@Composable
fun Page(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(22.dp), content = content)

@Composable
fun BigMoney(label: String, value: String, sym: String) {
    Column(Modifier.padding(top = 6.dp)) {
        Label(label)
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
            T(value, 40.sp, weight = FontWeight.Medium, maxLines = 1, letter = (-.8).sp); Spacer(Modifier.width(6.dp)); T(sym, 22.sp, C.n500, modifier = Modifier.padding(bottom = 5.dp))
        }
    }
}

// ================= ГОЛОВНА =================
@Composable
fun HomeScreen(vm: BellaViewModel) {
    val t = Totals(vm)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f)) { T("BELLA FINANCE", 11.sp, C.n500, letter = 1.1.sp); T(monthLabel(CUR_MONTH), 18.sp, weight = FontWeight.Medium) }
            CurPicker(vm)
            IconBtn(if (vm.hide) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility) { vm.hide = !vm.hide }
            IconBtn(Icons.Outlined.Undo, if (vm.undoN > 0) C.accent else C.n600) { vm.undo() }
            IconBtn(Icons.Outlined.HelpOutline, C.n500) { vm.openHelp() }
            IconBtn(Icons.Outlined.Settings, C.n500) { vm.openSettings() }
        }
        Page {
            Column(Modifier.padding(top = 0.dp)) {
                BigMoney("ЗАГАЛЬНИЙ КАПІТАЛ", vm.m(t.net), vm.sym)
                val dl = if (vm.hide) "•••• ${vm.sym}" else (if (t.novNet >= 0) "+" else "−") + fmtInt(vm.cv(Math.abs(t.novNet))) + " " + vm.sym
                T("$dl за місяць", 13.sp, if (t.novNet >= 0) C.pos else C.neg, modifier = Modifier.padding(top = 2.dp))
                CapitalChart(vm)
                Box(Modifier.padding(top = 14.dp)) { RatesSection(vm) }
            }
            Btn("Нова операція", true, Modifier.fillMaxWidth(), Icons.Outlined.Add) { vm.openTx() }
            Column {
                SectionHead("Останні операції", "Всі (${vm.data.txs.size})") { vm.go(Screen.Bud) }
                t.novT.sortedByDescending { it.date }.take(4).forEach { Div(); TxRowView(it, vm) }
            }
            Column {
                T("Розділи", 13.sp, weight = FontWeight.Medium, modifier = Modifier.padding(bottom = 4.dp))
                val inc = t.novT.filter { it.type == "income" }.sumOf { it.amt }; val ex = t.novT.filter { it.type == "expense" }.sumOf { it.amt }
                SectionRow(Icons.Outlined.AccountBalanceWallet, C.accent, "Бюджет", "Доходи +${vm.m(inc)} · Витрати −${vm.m(ex)}", vm.m(t.novNet) + " " + vm.sym) { vm.go(Screen.Bud) }
                SectionRow(Icons.Outlined.Savings, C.accent, "Капітал та Рахунки", "Картки ${vm.m(t.cash)} · Заощадження ${vm.m(t.savT)} · Інвестиції ${vm.m(t.invT)}", vm.m(t.net) + " " + vm.sym) { vm.go(Screen.Acc) }
                SectionRow(Icons.Outlined.Calculate, GoldC, "Калькулятор ОВДП", "Дохідність, купони, прибуток", "%.2f%%".format(calcOvdp(vm.calcIn).ytm)) { vm.go(Screen.Calc) }
                SectionRow(Icons.Outlined.TrendingUp, BlueC, "Інвестиції", "ОВДП, Депозити, ETF, Крипто", vm.m(t.invT) + " " + vm.sym) { vm.go(Screen.Inv) }
                SectionRow(Icons.Outlined.Flag, PurpleC, "Цілі", "Накопичено ${vm.m(t.goalT)}", "${vm.data.goals.size} цілі") { vm.go(Screen.Goals) }
                SectionRow(Icons.Outlined.Toll, C.pos, "Пасивний дохід", "ОВДП, Депозити, дивіденди ETF", "≈ ${vm.m(t.passM)} ${vm.sym}/міс") { vm.go(Screen.Pass) }
            }
        }
    }
}


@Composable
private fun SectionRow(icon: ImageVector, color: Color, title: String, sub: String, metric: String, onClick: () -> Unit) {
    Div()
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Ico(icon, color, 20.dp)
        Column(Modifier.weight(1f)) { T(title, 14.sp, weight = FontWeight.Medium); T(sub, 11.sp, C.n500, maxLines = 1) }
        T(metric, 14.sp, weight = FontWeight.Medium, maxLines = 1); Ico(Icons.Outlined.ChevronRight, C.n600, 14.dp)
    }
}

// ================= КАПІТАЛ ТА РАХУНКИ =================
@Composable
fun AccScreen(vm: BellaViewModel) {
    val t = Totals(vm)
    Column(Modifier.fillMaxSize()) {
        TopBar("Капітал та Рахунки", vm) {
            CurPicker(vm)
            IconBtn(if (vm.hide) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, C.n500) { vm.hide = !vm.hide }
            IconBtn(Icons.Outlined.SwapHoriz) { vm.openTransfer() }
        }
        Page {
            Column {
                BigMoney("ЧИСТИЙ КАПІТАЛ", vm.m(t.net), vm.sym)
                Row(Modifier.fillMaxWidth().padding(top = 16.dp).height(8.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    @Composable fun seg(w: Double, c: Color) { if (w > 0) Box(Modifier.weight(w.toFloat()).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(c)) }
                    seg(Math.max(t.invT, 1.0), C.accent); seg(Math.max(t.cash, 1.0), BlueC); if (t.savT > 0) seg(t.savT, TealC)
                    seg(Math.max(t.goalT, 1.0), GoldC); seg(Math.max(Math.abs(t.debtSum), 1.0), if (t.debtSum < 0) C.neg else C.pos)
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Legend("Гроші на картках", BlueC, vm.m(t.cash) + " " + vm.sym)
                        if (t.savT > 0) Legend("Заощадження", TealC, vm.m(t.savT) + " " + vm.sym)
                        Legend("Борги та позики", C.pos, (if (t.debtSum >= 0) "+" else "−") + vm.m(Math.abs(t.debtSum)) + " " + vm.sym, if (t.debtSum >= 0) C.pos else C.neg)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Legend("Інвестиції", C.accent, vm.m(t.invT) + " " + vm.sym)
                        Legend("Скарбнички та цілі", GoldC, vm.m(t.goalT) + " " + vm.sym)
                    }
                }
            }
            Column {
                SectionHead("Рахунки та картки", "${vm.data.accs.size} рахунків", C.n500)
                groupAccounts(vm.data.accs).forEach { (g, items) ->
                    val total = items.sumOf { vm.base(it.value) }
                    GroupBlock(g.title, "${items.size} · " + vm.m(total) + " " + vm.sym) {
                        items.forEach { (ix, a) -> AccRow(vm, ix, a, total) }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Btn("Рахунок", true, Modifier.weight(1f), Icons.Outlined.Add) { vm.openAcc() }
                    Btn("Переказ", false, Modifier.weight(1f), Icons.Outlined.SwapHoriz) { vm.openTransfer() }
                }
            }
            Column {
                SectionHead("Борги та позики", "+ Додати") { vm.openDebt() }
                groupDebts(vm.data.debts).forEach { g ->
                    GroupBlock(g.title, (if (g.owedToMe) "+" else "−") + vm.m(g.openTotal) + " " + vm.sym, if (g.owedToMe) C.pos else C.neg) {
                        g.items.forEach { (i, d) ->
                            Div()
                            val col = if (d.done) C.n500 else if (d.owedToMe) C.pos else C.neg
                            Row(Modifier.fillMaxWidth().clickable { vm.editDebt(i) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(Modifier.size(28.dp).clickable { vm.toggleDebt(i) }, contentAlignment = Alignment.Center) { Box(Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(if (d.done) C.accent else Color.Transparent).then(Modifier.androidBorder(C.n700)), contentAlignment = Alignment.Center) { if (d.done) Ico(Icons.Outlined.Check, C.bg, 12.dp) } }
                                Column(Modifier.weight(1f)) {
                                    T(d.who, 14.sp, strike = d.done); T((if (d.done) "Повернено" else if (d.owedToMe) "Мені винні" else "Я винен") + if (d.due.length >= 10) " · до ${ddmm(d.due)}" else "", 11.sp, col)
                                }
                                T(vm.m(d.amt) + " " + vm.sym, 14.sp, col, FontWeight.Medium)
                            }
                        }
                    }
                }
            }
            RatesSection(vm)
        }
    }
}

/** Група рядків, що згортається: заголовок із підсумком; за замовчуванням розгорнута. */
@Composable
private fun GroupBlock(title: String, summary: String, summaryColor: Color = C.n500, content: @Composable ColumnScope.() -> Unit) {
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
    Column {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            T(title, 13.sp, weight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1)
            T(summary, 12.sp, summaryColor, maxLines = 1)
            Ico(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, C.n600, 16.dp)
        }
        if (open) content()
    }
}

private fun Modifier.androidBorder(c: Color) = this.then(Modifier.border(androidx.compose.foundation.BorderStroke(1.5.dp, c), RoundedCornerShape(4.dp)))

@Composable private fun Legend(label: String, dot: Color, value: String, vc: Color = C.text) {
    Column { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Dot(dot); T(label, 11.sp, C.n500, maxLines = 1) }; T(value, 16.sp, vc, FontWeight.Medium, Modifier.padding(top = 2.dp), maxLines = 1) }
}

@Composable
private fun AccRow(vm: BellaViewModel, i: Int, a: Account, groupTotal: Double) {
    val p = vm.base(a) / (if (groupTotal != 0.0) groupTotal else 1.0) * 100
    Div()
    Row(Modifier.fillMaxWidth().clickable { vm.editAcc(i) }.padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Ico(if (a.cash) Icons.Outlined.Payments else Icons.Outlined.CreditCard, C.accent, 20.dp)
        Column(Modifier.weight(1f)) {
            T(a.name, 14.sp, weight = FontWeight.Medium, maxLines = 1)
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Bar((p / 100).toFloat(), modifier = Modifier.weight(1f)); T("${Math.round(p)}%", 11.sp, C.n500, modifier = Modifier.widthIn(min = 28.dp))
            }
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(min = 84.dp)) { T(nativeLabel(a, vm.hide), 14.sp, weight = FontWeight.Medium, maxLines = 1); T(a.cur, 11.sp, C.n500) }
    }
}

// ================= БЮДЖЕТ =================
@Composable
fun BudgetScreen(vm: BellaViewModel) {
    val s = vm.data; val mos = vm.months; val mi = vm.month.coerceIn(0, mos.size - 1); val mk = mos[mi]
    val mt = s.txs.filter { it.date.startsWith(mk) }
    val inc = mt.filter { it.type == "income" }.sumOf { it.amt }; val exp = mt.filter { it.type == "expense" }.sumOf { it.amt }; val nb = inc - exp
    val isLast = mk == CUR_MONTH; val dim = java.time.YearMonth.parse(mk).lengthOfMonth()
    val daysLeft = if (isLast) dim - TODAY.substring(8, 10).toInt() + 1 else dim
    val cats = mt.filter { it.type == "expense" }.groupBy { it.cat }.mapValues { e -> e.value.sumOf { it.amt } }
    val savePct = if (inc > 0) Math.max(0.0, nb / inc * 100) else 0.0
    Column(Modifier.fillMaxSize()) {
        TopBar("Бюджет", vm) { CurPicker(vm); IconBtn(Icons.Outlined.Add) { vm.openTx() } }
        Page {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Icons.Outlined.ChevronLeft, C.n400) { vm.setMonth(mi - 1) }; T(monthLabel(mk), 14.sp, weight = FontWeight.Medium); IconBtn(Icons.Outlined.ChevronRight, C.n400) { vm.setMonth(mi + 1) }
            }
            Column {
                Label("ЧИСТИЙ БАЛАНС")
                T((if (nb >= 0) "+" else "−") + vm.m(Math.abs(nb)) + " " + vm.sym, 32.sp, if (nb >= 0) C.pos else C.neg, FontWeight.Medium, Modifier.padding(top = 4.dp), maxLines = 1)
                Row(Modifier.fillMaxWidth().padding(top = 12.dp).height(8.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.weight(Math.max(inc, 1.0).toFloat()).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(C.pos))
                    Box(Modifier.weight(Math.max(exp, 1.0).toFloat()).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(C.neg))
                }
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stat("Доходи", "+" + vm.m(inc), C.pos, Modifier.weight(1f)); Stat("Витрати", "−" + vm.m(exp), C.neg, Modifier.weight(1f))
                    Stat(if (isLast) "Вільно на день" else "Витрати на день", "≈ " + vm.m(if (isLast) (if (nb > 0) nb / daysLeft else 0.0) else exp / dim) + " " + vm.sym, C.text, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    T("Норма заощаджень", 12.sp, C.n500, maxLines = 1); Bar((savePct / 100).toFloat(), C.pos, 4.dp, Modifier.weight(1f)); T("%.1f%%".format(savePct), 12.sp, C.n500)
                }
            }
            Seg(listOf(0 to "Огляд", 1 to "Операції", 2 to "Звіт", 3 to "Ліміти"), vm.tab) { vm.tab = it }
            when (vm.tab) {
                0 -> Column(verticalArrangement = Arrangement.spacedBy(22.dp)) { Column { cats.entries.sortedByDescending { it.value }.forEach { (c, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Ico(catIcon(c), C.accent, 20.dp)
                        Column(Modifier.weight(1f)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { T(c, 14.sp, maxLines = 1); T(vm.m(v) + " " + vm.sym, 14.sp, weight = FontWeight.Medium, maxLines = 1) }
                            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Bar((v / exp).toFloat(), modifier = Modifier.weight(1f)); T("${Math.round(v / exp * 100)}%", 11.sp, C.n500) }
                        }
                    }
                } }
                    SmartAnalyticsSection(vm, mt)
                }
                1 -> OpsTab(vm)
                2 -> Column {
                    FlowBars(vm, monthlyFlows(s.txs, CUR_MONTH, mos.size.coerceIn(6, 12)))
                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) { listOf("Місяць", "Доходи", "Витрати", "Баланс").forEach { T(it, 11.sp, C.n500, modifier = Modifier.weight(1f)) } }
                    mos.reversed().forEach { k ->
                        val t = s.txs.filter { it.date.startsWith(k) }; val a = t.filter { it.type == "income" }.sumOf { it.amt }; val b = t.filter { it.type == "expense" }.sumOf { it.amt }
                        Div(); Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                            T(k.substring(5, 7) + "." + k.substring(0, 4), 12.sp, modifier = Modifier.weight(1f)); T("+" + vm.m(a), 12.sp, C.pos, modifier = Modifier.weight(1f), maxLines = 1)
                            T("−" + vm.m(b), 12.sp, modifier = Modifier.weight(1f), maxLines = 1); T((if (a - b >= 0) "+" else "−") + vm.m(Math.abs(a - b)), 12.sp, if (a - b >= 0) C.pos else C.neg, modifier = Modifier.weight(1f), maxLines = 1)
                        }
                    }
                }
                else -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Btn("Налаштувати ліміти й категорії", false, Modifier.fillMaxWidth(), Icons.Outlined.Tune) { vm.openCats() }
                    Column {
                        SectionHead("Повторювані операції", "${s.recurring.size}", C.n500)
                        if (s.recurring.isEmpty()) T("Немає. Під час додавання операції поставте «Повторювати щомісяця» — для оренди, підписок, зарплати.", 12.sp, C.n500)
                        s.recurring.forEachIndexed { i, r ->
                            Div()
                            Row(Modifier.fillMaxWidth().clickable { vm.openRec(i) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Ico(catIcon(r.cat), if (r.active) C.accent else C.n600, 20.dp)
                                Column(Modifier.weight(1f)) {
                                    T(r.desc, 14.sp, if (r.active) C.text else C.n500, FontWeight.Medium, maxLines = 1)
                                    T(if (r.active) "щомісяця, ${r.day}-го · наступна ${ddmm(r.next)}" else "призупинено", 11.sp, C.n500, maxLines = 1)
                                }
                                T((if (r.type == "income") "+" else "−") + vm.m(r.amt) + " " + vm.sym, 14.sp, if (r.type == "income") C.pos else C.text, FontWeight.Medium, maxLines = 1)
                            }
                        }
                    }
                    if (s.limits.isEmpty()) T("Лімітів ще немає. Задайте місячний ліміт для потрібних категорій.", 12.sp, C.n500)
                    s.limits.forEach { (c, l) ->
                    val sp = cats[c] ?: 0.0; val over = l > 0 && sp > l
                    Column {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            T(c, 14.sp); T(if (l > 0) "${vm.m(sp)} з ${vm.m(l)} ${vm.sym}" else "${vm.m(sp)} ${vm.sym} · ліміт не встановлено", 12.sp, if (over) C.neg else C.n500, maxLines = 1)
                        }
                        Bar(if (l > 0) (sp / l).toFloat() else 0f, if (over) C.neg else C.accent, 4.dp, Modifier.padding(top = 6.dp))
                    }
                } }
            }
        }
    }
}

@Composable private fun Stat(l: String, v: String, c: Color, m: Modifier) = Column(m) { T(l, 11.sp, C.n500, maxLines = 1); T(v, 14.sp, c, FontWeight.Medium, maxLines = 1) }

@Composable
private fun OpsTab(vm: BellaViewModel) {
    val f = vm.filters; val q = f.q.trim().lowercase(); val rt = vm.rate
    val mn = f.min.takeIf { it.isNotEmpty() }?.let { parseNum(it) }; val mx = f.max.takeIf { it.isNotEmpty() }?.let { parseNum(it) }
    val found = vm.data.txs.filter { f.acc == "*" || it.acc == f.acc || (f.acc.isNotEmpty() && it.toAcc == f.acc) }.filter { (f.tf == "all" || it.type == f.tf) && (q.isEmpty() || it.desc.lowercase().contains(q) || it.cat.lowercase().contains(q)) && (f.dateFrom.isEmpty() || it.date >= f.dateFrom) && (f.dateTo.isEmpty() || it.date <= f.dateTo) && (mn == null || it.amt / rt >= mn) && (mx == null || it.amt / rt <= mx) }
        .let { l -> when (f.sort) { "dateA" -> l.sortedBy { it.date }; "amtD" -> l.sortedByDescending { it.amt }; "amtA" -> l.sortedBy { it.amt }; else -> l.sortedByDescending { it.date } } }
    val fi = found.filter { it.type == "income" }.sumOf { it.amt }; val fe = found.filter { it.type == "expense" }.sumOf { it.amt }
    val cnt = listOf(f.dateFrom, f.dateTo, f.min, f.max).count { it.isNotEmpty() } + if (f.acc != "*") 1 else 0
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Field("", f.q, hint = "Пошук (Сільпо, Зарплата…)") { vm.filters = f.copy(q = it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("date" to ("Дата" to ("dateD" to "dateA")), "amt" to ("Сума" to ("amtD" to "amtA"))).forEach { (_, v) ->
                val (l, p) = v; val on = f.sort == p.first || f.sort == p.second
                Btn(l + if (f.sort == p.first) " ↓" else if (f.sort == p.second) " ↑" else "", false, icon = Icons.Outlined.SwapVert) { vm.filters = f.copy(sort = if (f.sort == p.first) p.second else p.first) }
            }
        }
        Row(Modifier.clickable { vm.filters = f.copy(show = !f.show) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Ico(Icons.Outlined.FilterList, C.n500, 16.dp); T("Фільтри: дата, сума, рахунок" + if (cnt > 0) " ($cnt)" else "", 12.sp, C.n400); Ico(if (f.show) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, C.n500, 14.dp)
        }
        if (f.show) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { DateField("Дата від", f.dateFrom, Modifier.weight(1f), "РРРР-ММ-ДД") { vm.filters = f.copy(dateFrom = it) }; DateField("Дата до", f.dateTo, Modifier.weight(1f), "РРРР-ММ-ДД") { vm.filters = f.copy(dateTo = it) } }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Field("Сума від (${vm.sym})", f.min, Modifier.weight(1f), numeric = true) { vm.filters = f.copy(min = it) }; Field("Сума до (${vm.sym})", f.max, Modifier.weight(1f), numeric = true) { vm.filters = f.copy(max = it) } }
            val accs = vm.data.accs; val aOpts = listOf("Усі рахунки", "Без рахунку") + accs.map { it.name }
            val aSel = when (f.acc) { "*" -> 0; "" -> 1; else -> accs.indexOfFirst { it.id == f.acc }.let { if (it < 0) 0 else it + 2 } }
            Dropdown("Рахунок", aOpts, aSel) { vm.filters = f.copy(acc = when (it) { 0 -> "*"; 1 -> ""; else -> accs[it - 2].id }) }
            T("Скинути все", 12.sp, C.accent400, modifier = Modifier.clickable { vm.filters = Filters(show = true) })
        }
        Seg(listOf("all" to "Усі", "expense" to "Витрати", "income" to "Доходи", "transfer" to "Перекази"), f.tf) { vm.filters = f.copy(tf = it) }
        T("За весь період · знайдено: ${found.size}" + (if (found.size > 100) " (перші 100)" else "") + " · −${vm.m(fe)} · +${vm.m(fi)} ${vm.sym}", 11.sp, C.n500)
        Column { found.take(100).forEach { Div(); TxRowView(it, vm) } }
    }
}

// ================= ІНВЕСТИЦІЇ =================
@Composable
fun InvScreen(vm: BellaViewModel) {
    val t = Totals(vm)
    val cashAccs = vm.data.accs.withIndex().filter { it.value.cash }
    val cashT = cashAccs.sumOf { vm.base(it.value) }
    val total = t.invT + cashT
    Column(Modifier.fillMaxSize()) {
        TopBar("Інвестиції", vm)
        Page {
            Column {
                BigMoney("ПОРТФЕЛЬ", vm.m(total), vm.sym)
                if (cashT > 0) T("Інвестиції ${vm.m(t.invT)} · готівка ${vm.m(cashT)} ${vm.sym}", 12.sp, C.n500, modifier = Modifier.padding(top = 2.dp))
                PortfolioDonut(vm, t.invGroups.map { (g, c, v) -> Slice(g, Color(c), v.first) } + Slice("Готівка", CashC, cashT))
            }
            Column {
                t.invGroups.filter { it.third.third > 0 }.forEach { (g, c, v) ->
                    val isOpen = g in vm.open
                    Div()
                    Row(Modifier.fillMaxWidth().clickable { vm.open = if (isOpen) vm.open - g else vm.open + g }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(c)))
                        Column(Modifier.weight(1f)) { T(g, 14.sp, weight = FontWeight.Medium); T("${v.third} поз. · " + if (v.second > 0) "%.1f%% річних".format(v.second) else "без виплат", 11.sp, C.n500, maxLines = 1) }
                        T("${sharePct(v.first, total)}%", 12.sp, C.n500); T(vm.m(v.first) + " " + vm.sym, 14.sp, weight = FontWeight.Medium, maxLines = 1)
                        Ico(if (isOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, C.n600, 16.dp)
                    }
                    if (isOpen && g == "ETF") Column(Modifier.padding(start = 18.dp, top = 4.dp)) {
                        T(if (vm.quoteBusy) "Оновлення цін…" else "↻ Оновити ціни з біржі", 13.sp, C.accent400, modifier = Modifier.clickable { vm.refreshQuotes() }.padding(vertical = 4.dp))
                        if (vm.quoteMsg.isNotEmpty() && vm.sheet == Sheet.None) T(vm.quoteMsg, 11.sp, C.n500)
                    }
                    if (isOpen) vm.data.pos.forEachIndexed { ix, p ->
                        if (p.group == g) {
                            val value = posValueUah(p, RATES); val cost = posCostUah(p); val fxp = isFxPos(p)
                            // для ETF у валюті: відсоток — зміна ціни фонду в його валюті
                            val pl = if (fxp) plCurPct(p) else if (cost > 0) (value - cost) / cost * 100 else 0.0
                            fun fx(v: Double) = if (vm.hide) "•••• ${curSign(p.cur)}" else fxMoney(v, p.cur)
                            Column(Modifier.fillMaxWidth().clickable { vm.editPos(ix) }.padding(start = 18.dp, top = 8.dp, bottom = 8.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { T(p.name, 13.sp, maxLines = 1, modifier = Modifier.weight(1f)); T(if (fxp) fx(posValueCur(p)) else vm.m(value) + " " + vm.sym, 13.sp, weight = FontWeight.Medium) }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    T("${fmtQty(p.qty)} шт. · куплено по " + if (fxp) fx(p.pxBuy) else vm.m(p.buy), 11.sp, C.n500, maxLines = 1, modifier = Modifier.weight(1f)); T((if (pl >= 0) "+" else "−") + "%.1f%%".format(Math.abs(pl)), 11.sp, if (pl >= 0) C.pos else C.neg)
                                }
                                if (fxp) {
                                    val sp = plSplit(p, RATES); fun sg(v: Double) = (if (v >= 0) "+" else "−") + vm.m(Math.abs(v)) + " " + vm.sym
                                    val plc = posValueCur(p) - posCostCur(p)
                                    T("≈ ${vm.m(value)} ${vm.sym} · прибуток " + (if (plc >= 0) "+" else "−") + fx(Math.abs(plc)), 11.sp, if (plc >= 0) C.pos else C.neg, maxLines = 1)
                                    T("ціна ${fx(p.pxNow)}" + (if (p.ticker.isNotEmpty()) " · ${p.ticker}" else "") + " · у гривні: ціна ${sg(sp.price)} · курс ${sg(sp.fx)}", 11.sp, C.n500, maxLines = 2)
                                } else if (p.ticker.isNotEmpty()) T(p.ticker, 11.sp, C.n500)
                                T(listOfNotNull(if (p.mat.isNotEmpty()) (if (p.group == "Депозити") "Повернення " else "До ") + ddmmyy(p.mat) else "Без погашення", if (p.rate != 0.0) "${p.rate}% річних" else "без виплат", if (p.next.isNotEmpty()) "виплата ${ddmmyy(p.next)}" else null, if (p.monthly) "щомісяця" else null).joinToString(" · "), 11.sp, C.n500)
                            }
                        }
                    }
                }
            }
            if (cashAccs.isNotEmpty()) Column {
                val g = "Готівка"; val isOpen = g in vm.open
                Div()
                Row(Modifier.fillMaxWidth().clickable { vm.open = if (isOpen) vm.open - g else vm.open + g }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(CashC))
                    Column(Modifier.weight(1f)) { T(g, 14.sp, weight = FontWeight.Medium); T(if (cashAccs.isEmpty()) "немає готівкових рахунків" else "${cashAccs.size} рах. · за курсом НБУ", 11.sp, C.n500, maxLines = 1) }
                    T("${sharePct(cashT, total)}%", 12.sp, C.n500); T(vm.m(cashT) + " " + vm.sym, 14.sp, weight = FontWeight.Medium, maxLines = 1)
                    Ico(if (isOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, C.n600, 16.dp)
                }
                if (isOpen) {
                    cashAccs.forEach { (ix, a) ->
                        Column(Modifier.fillMaxWidth().clickable { vm.editAcc(ix) }.padding(start = 18.dp, top = 8.dp, bottom = 8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { T(a.name, 13.sp, maxLines = 1, modifier = Modifier.weight(1f)); T(vm.m(vm.base(a)) + " " + vm.sym, 13.sp, weight = FontWeight.Medium) }
                            T(nativeLabel(a, vm.hide) + if (a.cur != "UAH") " · 1 ${a.cur} = ${fmt2(RATES[a.cur] ?: 0.0)} ₴" else "", 11.sp, C.n500, maxLines = 1)
                        }
                    }
                    T("+ Додати готівку", 13.sp, C.accent400, modifier = Modifier.clickable { vm.openCash() }.padding(start = 18.dp, top = 8.dp, bottom = 8.dp))
                    T("Суми беруться з рахунків «Капітал та Рахунки» і в капіталі не дублюються. Позначка «Готівка» — у формі рахунку.", 11.sp, C.n500, modifier = Modifier.padding(start = 18.dp))
                }
            }
            val up = vm.upcomingOvdp()
            if (up.isNotEmpty()) Column {
                SectionHead("Найближчі виплати ОВДП", if (vm.remindOn) "нагадування увімк." else "нагадування вимк.", C.n500)
                up.take(5).forEach { e ->
                    Div()
                    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        T(ddmmyy(e.date.toString()), 12.sp, C.n500)
                        Column(Modifier.weight(1f)) { T(e.name, 13.sp, maxLines = 1); T(if (e.maturity) "погашення" + (if (e.coupon > 0) " + купон" else "") else "купон", 11.sp, C.n500) }
                        T("+" + vm.m(e.total) + " " + vm.sym, 13.sp, C.pos, FontWeight.Medium, maxLines = 1)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Btn("Позиція", true, Modifier.weight(1f), Icons.Outlined.Add) { vm.openPos() }; Btn("ОВДП", false, Modifier.weight(1f), Icons.Outlined.Calculate) { vm.go(Screen.Calc) } }
            CompoundSection(vm, t.invT)
        }
    }
}
private val CashC = Color(0xFF4ADE80)
private fun fmtQty(q: Double) = if (q == Math.floor(q)) q.toLong().toString() else q.toString()

// ================= ЦІЛІ =================
@Composable
fun GoalsScreen(vm: BellaViewModel) {
    val g = vm.data.goals
    Column(Modifier.fillMaxSize()) {
        TopBar("Цілі", vm) { IconBtn(Icons.Outlined.Add) { vm.openGoal() } }
        Page {
            Column { BigMoney("НАКОПИЧЕНО", vm.m(g.sumOf { it.saved }), vm.sym); T("із ${vm.m(g.sumOf { it.target })} ${vm.sym}", 13.sp, C.n500) }
            if (g.isEmpty()) T("Цілей ще немає. Створіть першу: подушка безпеки, подорож, техніка.", 13.sp, C.n500)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { g.forEachIndexed { i, x ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.surface).clickable { vm.editGoal(i) }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Ico(goalIcon(x.icon), PurpleC, 22.dp)
                        Column(Modifier.weight(1f)) {
                            T(x.name, 14.sp, weight = FontWeight.Medium)
                            T("${vm.m(x.saved)} з ${vm.m(x.target)} ${vm.sym}" + if (x.deadline.length >= 10) " · до ${ddmmyy(x.deadline)}" else "", 11.sp, C.n500, maxLines = 1)
                        }
                        T("${Math.min(100, Math.round(x.saved / x.target * 100))}%", 14.sp, weight = FontWeight.Medium)
                    }
                    Bar((x.saved / x.target).toFloat(), PurpleC, 4.dp)
                    GoalForecastLine(vm, x)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Btn("+ " + vm.m(x.step), false, Modifier.weight(1f)) { vm.addToGoal(i) }
                        Btn("Внести / зняти", false, Modifier.weight(1f), Icons.Outlined.SwapVert) { vm.openGoalMove(i) }
                    }
                }
            } }
            Btn("Ціль", true, Modifier.fillMaxWidth(), Icons.Outlined.Add) { vm.openGoal() }
            if (g.isNotEmpty()) T("Дотик до цілі — редагування й видалення. «+» додає крок без рахунку; «Внести / зняти» — довільна сума, за бажанням з рахунку.", 11.sp, C.n500)
        }
    }
}

/** Рядок прогнозу на картці цілі. */
@Composable
private fun GoalForecastLine(vm: BellaViewModel, g: Goal) {
    val f = goalForecast(g, TODAY); val dl = if (g.deadline.length >= 10) ddmmyy(g.deadline) else ""
    fun need() = if (f.deadlinePassed) "дедлайн $dl минув" else if (f.needMonthly > 0) "щоб устигнути до $dl, треба ≈ ${vm.m(f.needMonthly)} ${vm.sym}/міс" else ""
    val (text, col) = when (f.status) {
        GoalStatus.DONE -> "Ціль досягнута" to C.pos
        GoalStatus.NO_PLAN -> (listOf("Вкажіть внесок на місяць у цілі — покажу, коли її досягнете", need()).filter { it.isNotEmpty() }.joinToString(" · ")) to C.n500
        GoalStatus.TOO_LONG -> "При ${vm.m(g.monthly)} ${vm.sym}/міс — понад 50 років" + need().let { if (it.isEmpty()) "" else " · $it" } to GoldC
        GoalStatus.NO_DEADLINE -> "Досягнете ≈ ${monthNameLower(f.reachMonth)} · через ${f.months} міс. при ${vm.m(g.monthly)} ${vm.sym}/міс" to C.n400
        GoalStatus.ON_TIME -> "Досягнете ≈ ${monthNameLower(f.reachMonth)} · через ${f.months} міс. · встигаєте до $dl" to C.pos
        GoalStatus.LATE -> "Досягнете ≈ ${monthNameLower(f.reachMonth)} · через ${f.months} міс. · не встигаєте: ${need()}" to GoldC
    }
    T(text, 11.sp, col)
}

// ================= ПАСИВНИЙ ДОХІД =================
@Composable
fun PassScreen(vm: BellaViewModel) {
    val t = Totals(vm)
    Column(Modifier.fillMaxSize()) {
        TopBar("Пасивний дохід", vm)
        Page {
            Column { BigMoney("НА МІСЯЦЬ", vm.m(t.passM), vm.sym); T("≈ ${vm.m(t.passM * 12)} ${vm.sym} на рік · %.1f%% від портфеля".format(t.passM * 12 / (if (t.invT > 0) t.invT else 1.0) * 100), 13.sp, C.n500) }
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                t.invGroups.filter { it.third.second > 0 }.forEach { (g, _, v) ->
                    val m = v.first * v.second / 1200 * (if (g == "Депозити") 0.77 else 1.0)
                    Column {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { T(g, 14.sp, weight = FontWeight.Medium); T("+" + vm.m(m) + " " + vm.sym, 14.sp, C.pos, FontWeight.Medium) }
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Bar((m / (if (t.passM > 0) t.passM else 1.0)).toFloat(), C.pos, 4.dp, Modifier.weight(1f)); T("${v.second}" + if (g == "Депозити") " · після податку 23%" else " · без податку", 11.sp, C.n500, maxLines = 1)
                        }
                    }
                }
            }
            val avgY = if (t.invT > 0) t.invGroups.sumOf { it.third.first * it.third.second } / t.invT else 0.0
            FireSection(vm, t.invT, avgY)
        }
    }
}

// ================= КАЛЬКУЛЯТОР ОВДП =================
@Composable
fun CalcScreen(vm: BellaViewModel) {
    val s = vm.calcIn; val c = calcOvdp(s)
    fun n2(v: Double) = fmt2(v) + " ₴"
    fun sg(v: Double) = (if (v >= 0) "+" else "−") + fmt2(Math.abs(v)) + " ₴"
    Column(Modifier.fillMaxSize()) {
        TopBar("Калькулятор ОВДП", vm)
        Page {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("ISIN / назва", s.isin, hint = "UA4000236228") { vm.calcIn = s.copy(isin = it) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Field("Номінал (₴)", s.nom, Modifier.weight(1f), numeric = true) { vm.calcIn = s.copy(nom = it) }; Field("Ставка купона, %", s.coupon, Modifier.weight(1f), numeric = true) { vm.calcIn = s.copy(coupon = it) } }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { DateField("Дата купівлі", s.buy, Modifier.weight(1f), "РРРР-ММ-ДД") { vm.calcIn = s.copy(buy = it) }; DateField("Дата погашення", s.mat, Modifier.weight(1f), "РРРР-ММ-ДД") { vm.calcIn = s.copy(mat = it) } }
                if (s.freq == 0) DateField("Початок купона (випуск)", s.issue, hint = "РРРР-ММ-ДД · порожньо = дата купівлі") { vm.calcIn = s.copy(issue = it) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Field("Ціна купівлі (₴)", s.price, Modifier.weight(1f), numeric = true) { vm.calcIn = s.copy(price = it) }; Field("Кількість", s.qty, Modifier.weight(1f), numeric = true) { vm.calcIn = s.copy(qty = it) } }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Field("Ціна продажу (₴)", s.sell, Modifier.weight(1f), numeric = true) { vm.calcIn = s.copy(sell = it) }; DateField("Дата продажу", s.sellD, Modifier.weight(1f), "РРРР-ММ-ДД") { vm.calcIn = s.copy(sellD = it) } }
                T("Виплата купона", 12.sp, C.n500)
                Seg(listOf(1 to "Щомісяця", 3 to "Щокв.", 6 to "6 міс.", 12 to "Рік", 0 to "В кінці"), s.freq) { vm.calcIn = s.copy(freq = it) }
            }
            Column {
                Label("ДОХІДНІСТЬ ДО ПОГАШЕННЯ")
                T("%.2f%%".format(c.ytm), 32.sp, C.pos, FontWeight.Medium, Modifier.padding(top = 4.dp))
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) { T(if (c.days > 0) "До погашення ${c.days} дн." else "Погашено", 11.sp, C.n500); T("${c.prog}%", 11.sp, C.n500) }
                Bar(c.prog / 100f, C.accent, 4.dp, Modifier.padding(top = 4.dp))
            }
            Column { listOf("Кількість паперів" to "${fmtInt(c.qty.toDouble())} шт.", "Вкладення" to n2(c.inv), "Купонні виплати" to "+" + n2(c.cpn), "Різниця з номіналом" to sg(c.disc), "Усього отримаю" to n2(c.total), "Чистий прибуток" to sg(c.cpn + c.disc)).forEach { (a, b) ->
                Div(); Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), horizontalArrangement = Arrangement.SpaceBetween) { T(a, 13.sp, C.n500); T(b, 13.sp, weight = FontWeight.Medium) }
            } }
            if (c.hasSell) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.surface).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Label("ДОХІДНІСТЬ ПРОДАЖУ"); T("%.2f%%".format(c.sYield), 24.sp, if (c.sProfit >= 0) C.pos else C.neg, FontWeight.Medium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { T("Виручка", 13.sp, C.n500); T(n2(c.sTotal), 13.sp) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { T("Прибуток разом з купонами", 13.sp, C.n500); T(sg(c.sProfit), 13.sp, if (c.sProfit >= 0) C.pos else C.neg, FontWeight.Medium) }
            }
            Column {
                Label("ГРАФІК ВИПЛАТ")
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) { T("Дата", 11.sp, C.n500, modifier = Modifier.weight(1.2f)); T("Виплата", 11.sp, C.n500, modifier = Modifier.weight(1f)); T("Я отримаю", 11.sp, C.n500, modifier = Modifier.weight(1f)) }
                c.sched.forEach { r ->
                    Div(); Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1.2f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Ico(if (r.paid) Icons.Outlined.CheckCircle else Icons.Outlined.Schedule, if (r.paid) C.pos else C.n500, 16.dp)
                            Column { T("%02d.%02d.%d".format(r.date.dayOfMonth, r.date.monthValue, r.date.year), 12.sp); T(r.kind, 10.sp, C.n500, maxLines = 1) }
                        }
                        T(fmt2(r.amount), 12.sp, modifier = Modifier.weight(1f), maxLines = 1); T(fmt2(r.cum), 12.sp, C.n500, modifier = Modifier.weight(1f), maxLines = 1)
                    }
                }
                Div(); Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) { T("ВСЬОГО", 12.sp, weight = FontWeight.Medium); T(n2(c.total), 12.sp, weight = FontWeight.Medium) }
            }
            T("ОВДП для фізичних осіб не оподатковуються. Дохідність проста, у річних.", 11.sp, C.n500)
            Btn("Додати в інвестиції", true, Modifier.fillMaxWidth(), Icons.Outlined.Add) { vm.addCalcToInv() }
        }
    }
}
