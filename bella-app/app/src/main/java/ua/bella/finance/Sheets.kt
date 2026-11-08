package ua.bella.finance

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val TITLES = mapOf(
    Sheet.OvdpPick to "Вибрати випуск ОВДП", Sheet.OvdpCheck to "Звірка ОВДП з НБУ",
    Sheet.Help to "Довідка", Sheet.PosE to "Редагувати позицію", Sheet.AccE to "Редагувати рахунок", Sheet.Security to "Безпека", Sheet.Settings to "Налаштування",
    Sheet.Import to "Імпорт виписки", Sheet.Pos to "Нова позиція", Sheet.Transfer to "Переказ між рахунками", Sheet.Acc to "Новий рахунок", Sheet.Debt to "Новий запис боргу", Sheet.Tx to "Нова операція", Sheet.TxE to "Редагувати операцію", Sheet.DebtE to "Редагувати борг", Sheet.Rec to "Повторювана операція", Sheet.Rule to "Правило категорії", Sheet.Ibkr to "Interactive Brokers", Sheet.IbkrCharts to "Графіки IBKR", Sheet.Cats to "Ліміти й категорії", Sheet.Goal to "Нова ціль", Sheet.GoalE to "Редагувати ціль", Sheet.GoalMove to "Внести або зняти",
)
private const val EDIT_NOTE = "Щоб побачити, що було, очистіть поле: старе значення з'явиться сірим. Порожнє поле під час збереження лишається як було."
private val SUBMIT = mapOf(Sheet.PosE to "Зберегти", Sheet.AccE to "Зберегти", Sheet.Import to "Імпортувати", Sheet.Pos to "Додати", Sheet.Transfer to "Виконати", Sheet.Acc to "Створити", Sheet.Debt to "Зберегти", Sheet.Tx to "Додати", Sheet.TxE to "Зберегти", Sheet.DebtE to "Зберегти", Sheet.Rec to "Зберегти", Sheet.Rule to "Зберегти", Sheet.Ibkr to "Зберегти й синхронізувати", Sheet.Cats to "Зберегти", Sheet.Goal to "Створити", Sheet.GoalE to "Зберегти", Sheet.GoalMove to "Виконати")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetHost(vm: BellaViewModel) {
    val sh = vm.sheet; if (sh == Sheet.None) return
    // вибір випуску ОВДП — окрема шторка: після її закриття (змах, «Назад», ×) форма позиції відкривається новою шторкою,
    // а не лишається прихованою
    androidx.compose.runtime.key(sh == Sheet.OvdpPick) {
    ModalBottomSheet(onDismissRequest = { vm.closeSheet() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = C.bg, contentColor = C.text) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                T(TITLES[sh] ?: "", 16.sp, weight = FontWeight.Medium, modifier = Modifier.weight(1f)); IconBtn(Icons.Outlined.Close, C.n500) { vm.closeSheet() }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (sh) {
                    Sheet.Transfer -> TransferForm(vm); Sheet.Acc -> AccForm(vm); Sheet.Debt -> DebtForm(vm, false); Sheet.DebtE -> DebtForm(vm, true); Sheet.Tx -> TxForm(vm, false); Sheet.TxE -> TxForm(vm, true); Sheet.Cats -> CatsForm(vm); Sheet.Rec -> RecForm(vm); Sheet.Rule -> RuleForm(vm); Sheet.Ibkr -> IbkrForm(vm); Sheet.Goal -> GoalForm(vm, false); Sheet.GoalE -> GoalForm(vm, true); Sheet.GoalMove -> GoalMoveForm(vm)
                    Sheet.Pos, Sheet.PosE -> PosForm(vm, sh == Sheet.PosE); Sheet.AccE -> AccEditForm(vm)
                    Sheet.Settings -> SettingsList(vm); Sheet.Security -> SecurityList(vm); Sheet.Help -> HelpList(vm); Sheet.IbkrCharts -> IbkrChartsView(vm); Sheet.Import -> ImportForm(vm)
                    Sheet.OvdpPick -> OvdpPickView(vm); Sheet.OvdpCheck -> OvdpCheckView(vm)
                    else -> {}
                }
            }
            SUBMIT[sh]?.let { label ->
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Btn("Скасувати", false, Modifier.weight(1f)) { vm.closeSheet() }; Btn(label, true, Modifier.weight(1f)) { vm.submit() }
                }
            }
        }
    }
    }
}

@Composable
private fun TransferForm(vm: BellaViewModel) {
    val f = vm.form; val accs = vm.data.accs; val fa = accs.getOrNull(f.from); val ta = accs.getOrNull(f.to); val amt = parseNum(f.amount)
    val names = accs.map { "${it.name} · ${nativeLabel(it, false)}" }
    Dropdown("З рахунку", names, f.from) { vm.form = f.copy(from = it) }
    Dropdown("На рахунок", names, f.to) { vm.form = f.copy(to = it) }
    Field("Сума (${fa?.cur ?: ""})", f.amount, hint = "0", numeric = true) { vm.form = f.copy(amount = it) }
    val hint = when {
        f.from == f.to -> "Оберіть різні рахунки"; fa != null && amt > fa.bal -> "Недостатньо коштів"
        amt > 0 && fa != null && ta != null -> "Надійде ${Math.round(amt * rateOf(fa.cur) / rateOf(ta.cur) * 100) / 100.0} ${ta.cur}"; else -> ""
    }
    T(hint, 12.sp, C.n500)
    if (vm.formErr.isNotEmpty()) T(vm.formErr, 12.sp, C.neg)
}

@Composable
private fun AccForm(vm: BellaViewModel) {
    val f = vm.form
    Field("Назва", f.name, hint = "напр. Monobank Чорна") { vm.form = f.copy(name = it) }
    Field("Початковий баланс", f.bal, hint = "0", numeric = true) { vm.form = f.copy(bal = it) }
    Seg(CURRENCIES.map { it to it }, f.cur) { vm.form = f.copy(cur = it) }
    Row(Modifier.clickable { vm.form = f.copy(cashF = !f.cashF) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Ico(if (f.cashF) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, C.accent, 20.dp); T("Готівка (показувати в портфелі)", 13.sp)
    }
    if (vm.formErr.isNotEmpty()) T(vm.formErr, 12.sp, C.neg)
}

@Composable
private fun DebtForm(vm: BellaViewModel, edit: Boolean) {
    val f = vm.form
    Seg(listOf(true to "Мені винні", false to "Я винен"), f.kindOwedToMe) { vm.form = f.copy(kindOwedToMe = it) }
    Field("Особа / опис", f.who, hint = if (edit) vm.was { it.who } else "напр. Іван") { vm.form = f.copy(who = it) }
    Field(if (edit) "Залишок боргу (${vm.sym})" else "Сума (${vm.sym})", f.amt, hint = if (edit) vm.was { it.amt } else "0", numeric = true) { vm.form = f.copy(amt = it) }
    DateField("Термін повернення", f.due, hint = if (edit) vm.was { it.due }.ifEmpty { "РРРР-ММ-ДД" } else "РРРР-ММ-ДД") { vm.form = f.copy(due = it) }
    if (edit) {
        Field("Часткове повернення (${vm.sym})", f.dPay, hint = "сума платежу", numeric = true) { vm.form = f.copy(dPay = it) }
        val left = parseNum(f.amt) - parseNum(f.dPay)
        if (parseNum(f.dPay) > 0) T(if (left <= 0.005) "Платіж покриває весь борг: запис зникне зі списку." else "Залишок після платежу: ${fmt2(left)} ${vm.sym}.", 11.sp, C.n500)
        T(EDIT_NOTE + " Платіж зменшує залишок; повний платіж прибирає борг (вернути його можна кнопкою «Скасувати»). Термін можна прибрати, очистивши поле.", 11.sp, C.n500)
    }
    if (vm.formErr.isNotEmpty()) T(vm.formErr, 12.sp, C.neg)
    if (edit) T(if (vm.delAsk) "Натисніть ще раз, щоб видалити борг" else "Видалити борг", 13.sp, C.neg, modifier = Modifier.clickable { vm.deleteDebt() }.padding(vertical = 4.dp))
}

@Composable
private fun TxForm(vm: BellaViewModel, edit: Boolean) {
    val f = vm.form; val accs = vm.data.accs
    val acc = accs.firstOrNull { it.id == f.tAcc }
    Seg(listOf("expense" to "Витрата", "income" to "Дохід", "transfer" to "Переказ"), f.tType) { k ->
        var nf = f.copy(tType = k)
        if (k == "transfer") {
            val from = if (acc != null) acc.id else accs.firstOrNull()?.id ?: ""
            val to = if (f.tToAcc.isNotEmpty() && f.tToAcc != from) f.tToAcc else accs.firstOrNull { it.id != from }?.id ?: ""
            nf = nf.copy(tAcc = from, tToAcc = to)
        } else if (f.tType == "transfer" && f.tCat == "Перекази") nf = nf.copy(tCat = "Інше")
        vm.form = nf
    }
    if (f.tType == "transfer") {
        val names = accs.map { "${it.name} · ${it.cur}" }
        Dropdown("З рахунку", names, accs.indexOfFirst { it.id == f.tAcc }) { vm.form = f.copy(tAcc = accs[it].id) }
        Dropdown("На рахунок", names, accs.indexOfFirst { it.id == f.tToAcc }) { vm.form = f.copy(tToAcc = accs[it].id) }
        Field("Сума (${acc?.cur ?: ""})", f.tAmt, hint = vm.was { it.tAmt }.ifEmpty { "0" }, numeric = true) { vm.form = f.copy(tAmt = it) }
        val to = accs.firstOrNull { it.id == f.tToAcc }
        if (acc != null && to != null && acc.cur != to.cur) {
            val auto = convertAmount(parseNum(f.tAmt), acc.cur, to.cur, RATES)
            val wasTo = vm.was { it.tToAmt }
            Field("Надійде (${to.cur})", f.tToAmt, hint = "за курсом: ${fmt2(auto)}" + if (wasTo.isNotEmpty()) " (було $wasTo)" else "", numeric = true) { vm.form = f.copy(tToAmt = it) }
        }
    } else {
        val opts = listOf("Без рахунку") + accs.map { "${it.name} · ${it.cur}" }
        Dropdown("Рахунок", opts, accs.indexOfFirst { it.id == f.tAcc } + 1) { vm.form = f.copy(tAcc = if (it == 0) "" else accs[it - 1].id) }
        Field("Сума (${acc?.cur ?: vm.cur})", f.tAmt, hint = vm.was { it.tAmt }.ifEmpty { "0" }, numeric = true) { vm.form = f.copy(tAmt = it) }
        val cats = (vm.data.cats.filter { it != "Зарплата" || f.tType == "income" } + f.tCat).distinct()
        Dropdown("Категорія", cats, cats.indexOf(f.tCat)) { vm.form = f.copy(tCat = cats[it]) }
    }
    Field("Опис", f.tDesc, hint = vm.was { it.tDesc }.ifEmpty { if (f.tType == "transfer") "необов'язково" else "напр. Сільпо" }) { vm.form = f.copy(tDesc = it) }
    DateField("Дата", f.tDate, hint = vm.was { it.tDate }.ifEmpty { "РРРР-ММ-ДД" }) { vm.form = f.copy(tDate = it) }
    if (!edit && f.tType != "transfer") Row(Modifier.clickable { vm.form = f.copy(tRepeat = !f.tRepeat) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Ico(if (f.tRepeat) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, C.accent, 20.dp); T("Повторювати щомісяця (того ж числа)", 13.sp)
    }
    if (!edit && f.tRepeat && f.tType != "transfer") T("Така сама операція створюватиметься щомісяця в це число; пропущені місяці дорахуються під час запуску. Керувати — «Бюджет → Ліміти → Повторювані».", 11.sp, C.n500)
    val note = when {
        f.tType == "transfer" -> "Баланси обох рахунків зміняться автоматично. Переказ не потрапляє в доходи й витрати."
        acc != null -> "Баланс «${acc.name}» зміниться автоматично."
        else -> "Без рахунку: баланси рахунків не змінюються."
    }
    T(note, 11.sp, C.n500)
    if (vm.formErr.isNotEmpty()) T(vm.formErr, 12.sp, C.neg)
    if (edit) T(EDIT_NOTE, 11.sp, C.n500)
    if (edit) T(if (vm.txDelAsk) "Натисніть ще раз, щоб видалити (баланс рахунку повернеться)" else "Видалити операцію", 13.sp, C.neg, modifier = Modifier.clickable { vm.deleteTx() }.padding(vertical = 4.dp))
}

@Composable
private fun RecForm(vm: BellaViewModel) {
    val f = vm.form; val r = vm.data.recurring.getOrNull(f.rIdx) ?: return
    val acc = vm.data.accs.firstOrNull { it.id == r.acc }
    T((if (r.type == "income") "Дохід" else "Витрата") + " · ${r.cat}" + if (acc != null) " · ${acc.name}" else " · без рахунку", 12.sp, C.n500)
    Field("Опис", f.tDesc, hint = vm.was { it.tDesc }) { vm.form = f.copy(tDesc = it) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Field("Сума (${acc?.cur ?: vm.cur})", f.tAmt, Modifier.weight(1f), hint = vm.was { it.tAmt }, numeric = true) { vm.form = f.copy(tAmt = it) }
        Field("Число місяця", f.rDay, Modifier.weight(1f), hint = vm.was { it.rDay }, numeric = true) { vm.form = f.copy(rDay = it) }
    }
    Row(Modifier.clickable { vm.form = f.copy(rActive = !f.rActive) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Ico(if (f.rActive) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, C.accent, 20.dp); T("Активна (створювати щомісяця)", 13.sp)
    }
    T("Наступна: ${ddmmyyyy(r.next)}. Уже створені операції не змінюються. " + EDIT_NOTE, 11.sp, C.n500)
    if (vm.formErr.isNotEmpty()) T(vm.formErr, 12.sp, C.neg)
    T(if (vm.delAsk) "Натисніть ще раз, щоб видалити шаблон" else "Видалити шаблон (створені операції лишаться)", 13.sp, C.neg, modifier = Modifier.clickable { vm.deleteRec() }.padding(vertical = 4.dp))
}

@Composable
private fun IbkrForm(vm: BellaViewModel) {
    val f = vm.form
    T("Застосунок забирає з IBKR позиції ETF і акцій, гроші на рахунку й дивіденди через Flex Web Service. Токен дає лише читання звітів — торгувати чи виводити гроші ним не можна. Він шифрується на телефоні й не потрапляє в резервні копії.", 12.sp, C.n500)
    Field(if (vm.ibkr.on) "Новий токен (порожньо — залишити збережений)" else "Токен Flex Web Service", f.ibTok, hint = "напр. 1234567890123456789012") { vm.form = f.copy(ibTok = it) }
    Field("Номер Flex-запиту (Query ID)", f.ibQ, hint = "напр. 987654", numeric = true) { vm.form = f.copy(ibQ = it) }
    T("Як отримати (один раз, на комп'ютері в Client Portal IBKR):\n" +
        "1. Performance & Reports → Flex Queries → «+» у розділі Activity Flex Query.\n" +
        "2. Розділи: Open Positions (Options: Summary), Cash Report, Cash Transactions (Dividends, Withholding Tax, Payment In Lieu Of Dividends, Deposits & Withdrawals), Net Asset Value (NAV) in Base, Account Information, Prior Period Positions. Поля — усі. Останні чотири потрібні для графіків вартості рахунку й цін ETF.\n" +
        "3. Format: XML, Period: Last 365 Calendar Days. Збережіть і запишіть Query ID.\n" +
        "4. Там само: Flex Web Service Configuration → увімкнути → Generate New Token (термін — до 1 року).", 11.sp, C.n400)
    if (vm.formErr.isNotEmpty()) T(vm.formErr, 12.sp, C.neg)
    if (vm.ibkr.busy) T("Синхронізація з IBKR… це може тривати до 4 хвилин. Не закривайте вікно.", 12.sp, C.accent400)
    if (vm.ibkr.msg.isNotEmpty() && !vm.ibkr.busy) T(vm.ibkr.msg, 12.sp, if (vm.ibkr.msg.startsWith("IBKR") || vm.ibkr.msg.startsWith("Помилка") || vm.ibkr.msg.startsWith("Немає") || vm.ibkr.msg.startsWith("Токен")) C.neg else C.pos)
    if (vm.ibkr.on && !vm.ibkr.busy) Btn("Закрити", false, Modifier.fillMaxWidth()) { vm.closeSheet() }
    if (vm.ibkr.on) T("Відключити IBKR (позиції лишаться, токен буде видалено)", 13.sp, C.neg, modifier = Modifier.clickable { vm.disconnectIbkr() }.padding(vertical = 4.dp))
}

@Composable
private fun RuleForm(vm: BellaViewModel) {
    val f = vm.form; val cats = vm.data.cats
    Field("Текст в описі операції", f.ruMatch, hint = vm.was { it.ruMatch }.ifEmpty { "напр. Сільпо, АТБ" }) { vm.form = f.copy(ruMatch = it) }
    Dropdown("Категорія", cats, cats.indexOf(f.ruCat)) { vm.form = f.copy(ruCat = cats[it]) }
    T("Кілька варіантів — через кому. Регістр не важливий: «сільпо» знайде й «СІЛЬПО 123». Правила вище в списку перевіряються першими. Діють під час імпорту виписки; до вже наявних операцій — кнопкою «Застосувати до старих».", 11.sp, C.n500)
    if (vm.formErr.isNotEmpty()) T(vm.formErr, 12.sp, C.neg)
    if (f.ruIdx >= 0) T(if (vm.delAsk) "Натисніть ще раз, щоб видалити правило" else "Видалити правило", 13.sp, C.neg, modifier = Modifier.clickable { vm.deleteRule() }.padding(vertical = 4.dp))
}

@Composable
private fun GoalForm(vm: BellaViewModel, edit: Boolean) {
    val f = vm.form
    Seg(GOAL_ICONS, f.gIcon) { vm.form = f.copy(gIcon = it) }
    Field("Назва", f.gName, hint = vm.was { it.gName }.ifEmpty { "напр. Подушка безпеки" }) { vm.form = f.copy(gName = it) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Field("Сума цілі (${vm.sym})", f.gTarget, Modifier.weight(1f), hint = vm.was { it.gTarget }.ifEmpty { "0" }, numeric = true) { vm.form = f.copy(gTarget = it) }
        Field("Вже накопичено (${vm.sym})", f.gSaved, Modifier.weight(1f), hint = vm.was { it.gSaved }.ifEmpty { "0" }, numeric = true) { vm.form = f.copy(gSaved = it) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Field("Крок для «+» (${vm.sym})", f.gStep, Modifier.weight(1f), hint = vm.was { it.gStep }.ifEmpty { "1000" }, numeric = true) { vm.form = f.copy(gStep = it) }
        DateField("Дедлайн", f.gDeadline, Modifier.weight(1f), hint = vm.was { it.gDeadline }.ifEmpty { "РРРР-ММ-ДД" }) { vm.form = f.copy(gDeadline = it) }
    }
    Field("Планую відкладати на місяць (${vm.sym})", f.gMonthly, hint = vm.was { it.gMonthly }.ifEmpty { "для прогнозу, необов'язково" }, numeric = true) { vm.form = f.copy(gMonthly = it) }
    T("«Вже накопичено» — гроші, які вже відкладено; рахунків воно не змінює. Щоб перекласти гроші з рахунку, скористайтеся «Внести / зняти».", 11.sp, C.n500)
    if (edit) T(EDIT_NOTE + " Дедлайн і внесок на місяць можна прибрати, очистивши поле.", 11.sp, C.n500)
    if (vm.formErr.isNotEmpty()) T(vm.formErr, 12.sp, C.neg)
    if (edit) T(if (vm.delAsk) "Натисніть ще раз, щоб видалити ціль" else "Видалити ціль", 13.sp, C.neg, modifier = Modifier.clickable { vm.deleteGoal() }.padding(vertical = 4.dp))
}

@Composable
private fun GoalMoveForm(vm: BellaViewModel) {
    val f = vm.form; val g = vm.data.goals.getOrNull(f.gIdx) ?: return
    val accs = vm.data.accs; val acc = accs.firstOrNull { it.id == f.gAcc }
    T("${g.name}: ${vm.m(g.saved)} з ${vm.m(g.target)} ${vm.sym}", 13.sp, weight = FontWeight.Medium)
    Seg(listOf(true to "Внести", false to "Зняти"), f.gIn) { vm.form = f.copy(gIn = it) }
    val opts = listOf("Без рахунку") + accs.map { "${it.name} · ${nativeLabel(it, vm.hide)}" }
    Dropdown(if (f.gIn) "З рахунку" else "На рахунок", opts, accs.indexOfFirst { it.id == f.gAcc } + 1) { vm.form = f.copy(gAcc = if (it == 0) "" else accs[it - 1].id) }
    Field("Сума (${acc?.cur ?: vm.cur})", f.gAmt, hint = "0", numeric = true) { vm.form = f.copy(gAmt = it) }
    T(when {
        acc != null && f.gIn -> "Сума спишеться з «${acc.name}» і додасться до цілі. Капітал не зміниться."
        acc != null -> "Сума повернеться на «${acc.name}». Капітал не зміниться."
        else -> "Без рахунку змінюється лише накопичене в цілі (наприклад, якщо гроші лежать окремо)."
    }, 11.sp, C.n500)
    if (vm.formErr.isNotEmpty()) T(vm.formErr, 12.sp, C.neg)
}

@Composable
private fun CatsForm(vm: BellaViewModel) {
    T("Місячний ліміт витрат у ${vm.sym}. Порожньо — без ліміту.", 12.sp, C.n500)
    val lc = limitCats(vm.catsEdit)
    vm.catsEdit.forEach { c ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Ico(catIcon(c), C.accent, 18.dp)
            T(c, 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
            if (c in lc) Field("", vm.limEdit[c] ?: "", Modifier.width(120.dp), hint = "без ліміту", numeric = true) { vm.setLim(c, it) }
            else T("без ліміту", 11.sp, C.n500)
            if (c !in SYSTEM_CATS) IconBtn(Icons.Outlined.Delete, C.n500) { vm.removeCat(c) } else Spacer(Modifier.width(36.dp))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
        Field("Нова категорія", vm.newCat, Modifier.weight(1f), hint = "напр. Діти") { vm.newCat = it }
        Btn("Додати", false, icon = Icons.Outlined.Add) { vm.addCat() }
    }
    T("Службові категорії (Перекази, Надходження, Зарплата, Інше) не видаляються. Операції з видаленою категорією зберігають її назву.", 11.sp, C.n500)
    if (vm.formErr.isNotEmpty()) T(vm.formErr, 12.sp, C.neg)
}

@Composable
internal fun PosForm(vm: BellaViewModel, edit: Boolean) {
    val f = vm.form
    val dep = f.group == "Депозити"
    // обов'язкові поля, що ще порожні, — бурштинові (зникає, щойно заповнено)
    val miss = posMissing(f, vm.formOrig, redeemed = edit && vm.data.pos.getOrNull(f.editIdx)?.let { ovdpRedeemed(it, TODAY) } == true)
    Seg(GROUPS.map { it.first to it.first }, f.group) { vm.form = f.copy(group = it) }
    Field(if (dep) "Назва депозиту" else "Назва", f.pName, hint = if (edit) vm.was { it.pName } else if (dep) "напр. Депозит Моно, 12 міс." else "напр. VWCE", need = "name" in miss) { vm.form = f.copy(pName = it) }
    if (dep) {
        Field("Сума депозиту (${vm.sym})", f.pBuy, hint = vm.was { it.pBuy }.ifEmpty { "0" }, numeric = true, need = "buy" in miss) { vm.form = f.copy(pBuy = it) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (edit) Field("Поточна сума (${vm.sym})", f.pNow, Modifier.weight(1f), hint = vm.was { it.pNow }, numeric = true) { vm.form = f.copy(pNow = it) }
            Field("Ставка, % річних", f.pRate, Modifier.weight(1f), hint = vm.was { it.pRate }, numeric = true, need = "rate" in miss) { vm.form = f.copy(pRate = it) }
        }
    } else if (f.group == "ETF") {
        Seg(CURRENCIES.map { it to it }, f.pCur) { vm.form = f.copy(pCur = it) }
        val cs = if (f.pCur == "UAH") vm.sym else curSign(f.pCur)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("Кількість", f.pQty, Modifier.weight(1f), hint = vm.was { it.pQty }, numeric = true, need = "qty" in miss) { vm.form = f.copy(pQty = it) }
            Field("Ціна купівлі ($cs)", f.pBuy, Modifier.weight(1f), hint = vm.was { it.pBuy }, numeric = true, need = "buy" in miss) { vm.form = f.copy(pBuy = it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("Поточна ціна ($cs)", f.pNow, Modifier.weight(1f), hint = vm.was { it.pNow }.ifEmpty { if (edit) "" else "= ціна купівлі" }, numeric = true) { vm.form = f.copy(pNow = it) }
            if (f.pCur != "UAH") Field("Курс гривні на день купівлі, ₴ за 1 ${f.pCur} (не ціна фонду)", f.pFx, Modifier.weight(1f), hint = vm.was { it.pFx }.ifEmpty { "порожньо = НБУ " + fmt2(RATES[f.pCur] ?: 0.0) }, numeric = true) { vm.form = f.copy(pFx = it) }
        }
        Field("Дивідендна дохідність, % річних", f.pRate, hint = vm.was { it.pRate }.ifEmpty { "0 для накопичувальних" }, numeric = true) { vm.form = f.copy(pRate = it) }
        if (f.pCur != "UAH") {
            val q = parseNum(f.pQty); val px = parseNum(f.pNow).takeIf { it > 0 } ?: parseNum(f.pBuy)
            if (q > 0 && px > 0) T("Вартість: ${fxMoney(q * px, f.pCur)} ≈ ${vm.m(q * px * (RATES[f.pCur] ?: 1.0))} ${vm.sym}", 13.sp, weight = FontWeight.Medium)
        }
        if (f.pCur != "UAH") T("Ціни у ${f.pCur}; вартість у гривнях іде за курсом НБУ, тож прибуток показується окремо: від ціни фонду і від курсу. Курс купівлі порожній — береться поточний.", 11.sp, C.n500)
    } else {
        if (f.group == "ОВДП") PosNbuSection(vm, edit)
        // валюта цін: випуск з довідника або наявна валютна ОВДП; інакше — валюта перегляду (як у posFromForm)
        val bc = f.pBond?.cur ?: if (edit && f.pCur != "UAH") f.pCur else "UAH"
        val ps = if (bc != "UAH") curSign(bc) else vm.sym
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Field("Кількість", f.pQty, Modifier.weight(1f), hint = vm.was { it.pQty }, numeric = true, need = "qty" in miss) { vm.form = f.copy(pQty = it) }; Field("Ціна покупки ($ps)", f.pBuy, Modifier.weight(1f), hint = vm.was { it.pBuy }, numeric = true, need = "buy" in miss) { vm.form = f.copy(pBuy = it) } }
        if (edit) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Field("Поточна ціна ($ps)", f.pNow, Modifier.weight(1f), hint = vm.was { it.pNow }, numeric = true) { vm.form = f.copy(pNow = it) }; Field("Ставка, % річних", f.pRate, Modifier.weight(1f), hint = vm.was { it.pRate }, numeric = true) { vm.form = f.copy(pRate = it) } }
        else Field("Ставка, % річних", f.pRate, numeric = true) { vm.form = f.copy(pRate = it) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DateField(if (dep) "Дата виплати відсотків" else "Наступна виплата", f.pNext, Modifier.weight(1f), vm.was { it.pNext }.ifEmpty { "РРРР-ММ-ДД" }, need = "next" in miss) { vm.form = f.copy(pNext = it) }
        DateField(if (dep) "Дата повернення" else "Погашення", f.pMat, Modifier.weight(1f), vm.was { it.pMat }.ifEmpty { "РРРР-ММ-ДД" }) { vm.form = f.copy(pMat = it) }
    }
    if (dep) {
        Row(Modifier.clickable { vm.form = f.copy(pMonthly = !f.pMonthly) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Ico(if (f.pMonthly) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, C.accent, 20.dp); T("Повторювати щомісяця (відсотки в дохід)", 13.sp)
        }
        if (f.pMonthly) {
            val accs = vm.data.accs; val opts = listOf("Без рахунку") + accs.map { "${it.name} · ${it.cur}" }
            Dropdown("Зараховувати відсотки на", opts, accs.indexOfFirst { it.id == f.pPayAcc } + 1) { vm.form = f.copy(pPayAcc = if (it == 0) "" else accs[it - 1].id) }
            val sum = (parseNum(f.pNow).takeIf { edit && it > 0 } ?: parseNum(f.pBuy)) * vm.rate
            val qty = parseNum(f.pQty).takeIf { it > 0 } ?: 1.0
            val net = depositMonthlyNet(Pos("Депозити", "", qty, sum, sum, rate = parseNum(f.pRate)))
            T("≈ ${vm.m(net)} ${vm.sym} щомісяця після податку 23%. Операція «Відсотки» створюється в дату виплати, далі дата переходить на наступний місяць; пропущені місяці дораховуються. Після дати повернення виплати зупиняються.", 11.sp, C.n500)
        }
    }
    if (dep) T("Дата виплати — коли надійдуть відсотки, дата повернення — коли повернеться сума депозиту. Обидві необов'язкові.", 11.sp, C.n500)
    if (f.group == "ОВДП") OvdpAutoSection(vm, edit)
    if (miss.isNotEmpty()) T("Заповніть виділені поля: " + miss.mapNotNull { if (it == "buy" && dep) "сума депозиту" else MISSING_LABELS[it] }.joinToString(", "), 12.sp, C.warn)
    if (vm.formErr.isNotEmpty()) T(vm.formErr, 12.sp, C.neg)
    if (edit) {
        T(EDIT_NOTE, 11.sp, C.n500)
        if (f.group == "ОВДП") Row(Modifier.clickable { vm.posToCalc() }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Ico(Icons.Outlined.Calculate, C.accent400, 16.dp); T("Відкрити в калькуляторі ОВДП", 13.sp, C.accent400) }
        T(if (vm.delAsk) "Натисніть ще раз, щоб видалити позицію" else "Видалити позицію", 13.sp, C.neg, modifier = Modifier.clickable { vm.deletePos() }.padding(vertical = 4.dp))
    }
}

/** ОВДП: купони в дохід автоматично на вибраний рахунок, номінал при погашенні — переказом на нього ж. */
@Composable
private fun OvdpAutoSection(vm: BellaViewModel, edit: Boolean) {
    val f = vm.form
    val bc = f.pBond?.cur ?: if (edit && f.pCur != "UAH") f.pCur else "UAH"
    Row(Modifier.clickable { vm.form = f.copy(pAuto = !f.pAuto, pPayAcc = if (!f.pAuto && vm.data.accs.none { it.id == f.pPayAcc && it.cur == bc }) vm.defaultPayAcc(bc) else f.pPayAcc) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Ico(if (f.pAuto) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, C.accent, 20.dp); T("Купони в дохід автоматично", 13.sp)
    }
    if (!f.pAuto) return
    val accs = vm.data.accs
    if (accs.isEmpty()) { T("Спершу додайте рахунок у «Капітал та Рахунки»: купони зараховуються на рахунок.", 12.sp, C.warn); return }
    val sel = accs.indexOfFirst { it.id == f.pPayAcc }
    Dropdown("Зараховувати на рахунок", accs.map { "${it.name} · ${it.cur}" }, sel.coerceAtLeast(0)) { vm.form = f.copy(pPayAcc = accs[it].id) }
    val acc = accs.getOrNull(sel)
    if (acc != null && acc.cur != bc) T("Рахунок у ${acc.cur}, випуск у $bc: суми перераховуються за курсом НБУ на дату зарахування.", 11.sp, C.n500)
    val since = vm.data.pos.getOrNull(f.editIdx)?.takeIf { edit && it.autoCpn && isIsoDate(it.autoFrom) }?.autoFrom
    T("У день виплати за графіком НБУ (або за датами позиції) створюється операція «Купон» — дохід на цей рахунок. У день погашення номінал переходить на рахунок переказом (не дохід), а позиція позначається погашеною. " +
        (if (since != null) "Виплати обробляються з ${ddmmyyyy(since)}." else "Купони до сьогодні не дораховуються — лише наступні."), 11.sp, C.n500)
}

@Composable
private fun AccEditForm(vm: BellaViewModel) {
    val f = vm.form; val a = vm.data.accs.getOrNull(f.editIdx)
    Field("Назва", f.name, hint = vm.was { it.name }) { vm.form = f.copy(name = it) }
    Field("Поточний баланс (${a?.cur ?: ""})", f.bal, hint = vm.was { it.bal }, numeric = true) { vm.form = f.copy(bal = it) }
    T(EDIT_NOTE, 11.sp, C.n500)
    if (vm.formErr.isNotEmpty()) T(vm.formErr, 12.sp, C.neg)
    T("Виписка показує залишок на дату останньої операції. Введіть актуальний.", 11.sp, C.n500)
    Row(Modifier.clickable { vm.form = f.copy(sav = !f.sav) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Ico(if (f.sav) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, C.accent, 20.dp); T("Заощадження (не рахується як повсякденні гроші)", 13.sp)
    }
    Row(Modifier.clickable { vm.form = f.copy(cashF = !f.cashF) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Ico(if (f.cashF) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, C.accent, 20.dp); T("Готівка (показувати в портфелі)", 13.sp)
    }
    T(if (vm.delAsk) "Натисніть ще раз, щоб видалити рахунок" + vm.linkedTxCount().let { if (it > 0) " ($it прив'язаних операцій стануть «Без рахунку»)" else "" } else "Видалити рахунок", 13.sp, C.neg, modifier = Modifier.clickable { vm.deleteAcc() }.padding(vertical = 4.dp))
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, sub: String, color: androidx.compose.ui.graphics.Color = C.text, trailing: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Ico(icon, if (color == C.text) C.accent else color, 20.dp)
        Column(Modifier.weight(1f)) { T(title, 14.sp, color, FontWeight.Medium); if (sub.isNotEmpty()) T(sub, 11.sp, C.n500) }
        if (trailing) Ico(Icons.Outlined.ChevronRight, C.n600, 14.dp)
    }
}

@Composable
private fun SettingsList(vm: BellaViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Ico(Icons.Outlined.Lock, C.n500, 16.dp); T("Дані зберігаються автоматично на цьому пристрої" + fmtSavedAt(vm.savedAt).let { if (it.isEmpty()) "" else " · останнє збереження $it" }, 11.sp, C.n500) }
    val saveCopy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) vm.exportBackup(uri) }
    val openCopy = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.readBackup(uri) }
    val saveXlsx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri -> if (uri != null) vm.exportXlsx(uri) }
    SettingRow(Icons.Outlined.TableChart, "Експорт у Excel (.xlsx)", "Операції, по місяцях і по категоріях", trailing = true) { saveXlsx.launch(xlsxFileName(TODAY)) }
    SettingRow(Icons.Outlined.Backup, "Зберегти резервну копію", "Файл JSON з усіма даними (без PIN)" + if (vm.backupPwOn) " · зашифровано паролем" else " · не зашифровано", trailing = true) { saveCopy.launch(backupFileName(TODAY)) }
    SettingRow(Icons.Outlined.Restore, "Відновити з копії", "Замінює поточні дані даними з файлу", trailing = true) { openCopy.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
    RestorePrompts(vm)
    BackupPasswordRow(vm)
    AutoBackupRows(vm)
    if (vm.backupMsg.isNotEmpty()) T(vm.backupMsg, 12.sp, if (vm.backupMsg.startsWith("Не ") || vm.backupMsg.startsWith("Це не")) C.neg else C.n500)
    SettingRow(Icons.Outlined.FileUpload, "Імпорт виписки", "Excel або CSV з банку", trailing = true) { vm.openImport() }
    SettingRow(Icons.Outlined.VerifiedUser, "Безпека", if (vm.sec.hash.isNotEmpty()) "PIN-код" + (if (vm.sec.bio) " · відбиток пальця" else "") else "Захист вимкнено", trailing = true) { vm.openSheet(Sheet.Security) }
    UndoList(vm)
    SettingRow(Icons.Outlined.ContentCopy, "Прибрати повтори операцій", if (vm.dupInStore > 0) "Знайдено повторів: ${vm.dupInStore}" else "Повторів немає") { vm.dedupeStore() }
    SettingRow(Icons.AutoMirrored.Outlined.HelpOutline, "Довідка", "Як користуватися функціями", trailing = true) { vm.openHelp(-1) }
    SettingRow(if (vm.light) Icons.Outlined.DarkMode else Icons.Outlined.LightMode, "Тема", if (vm.light) "Світла" else "Темна") { vm.toggleTheme() }
    SettingRow(Icons.Outlined.Delete, if (vm.eraseAsk) "Натисніть ще раз, щоб стерти" else "Стерти дані", if (vm.eraseAsk) "Усі операції, рахунки, борги, інвестиції та цілі буде видалено" else "Видалити всі операції, рахунки, борги, інвестиції та цілі", C.neg) { vm.erase() }
    T("Bella Finance ${BuildConfig.VERSION_NAME} · збірка ${BuildConfig.VERSION_CODE} · ${BuildConfig.BUILD_DATE}", 11.sp, C.n500, modifier = Modifier.padding(top = 8.dp))
}

/** Підтвердження відновлення; для зашифрованої копії — спершу пароль. Також на екрані відновлення. */
@Composable
fun RestorePrompts(vm: BellaViewModel) {
    vm.pendingEnc?.let { env ->
        var pw by remember(env) { mutableStateOf("") }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            T("Копія зашифрована паролем" + (if (env.createdAt.isNotEmpty()) " · від ${env.createdAt.replace('T', ' ')}" else ""), 13.sp, weight = FontWeight.Medium)
            Field("Пароль копії", pw, password = true) { pw = it }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn("Скасувати", false, Modifier.weight(1f)) { vm.cancelRestore() }
                Btn(if (vm.backupBusy) "Перевірка…" else "Відкрити", true, Modifier.weight(1f)) { if (!vm.backupBusy) vm.unlockBackup(pw) }
            }
        }
    }
    vm.pendingRestore?.let { b ->
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            T("Відновити дані з копії" + (if (b.createdAt.isNotEmpty()) " від ${b.createdAt.replace('T', ' ')}" else "") + "?", 13.sp, weight = FontWeight.Medium)
            T("У копії: операцій ${b.state.data.txs.size}, рахунків ${b.state.data.accs.size}, позицій ${b.state.data.pos.size}. Поточні дані можна буде повернути кнопкою «Скасувати». PIN не зміниться.", 11.sp, C.n500)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Btn("Не відновлювати", false, Modifier.weight(1f)) { vm.cancelRestore() }; Btn("Відновити", true, Modifier.weight(1f)) { vm.confirmRestore() } }
        }
    }
}

/** Шифрування копій паролем: і ручної, і щотижневої автокопії. */
@Composable
private fun BackupPasswordRow(vm: BellaViewModel) {
    var edit by remember { mutableStateOf(false) }
    var p1 by remember { mutableStateOf("") }; var p2 by remember { mutableStateOf("") }
    SettingRow(Icons.Outlined.Password, "Шифрувати копію паролем", if (vm.backupPwOn) "Увімкнено: ручні копії й автокопії шифруються (AES-256)" else "Вимкнено: копії зберігаються відкритим JSON") {
        if (vm.backupPwOn) vm.clearBackupPassword() else edit = !edit
    }
    if (edit && !vm.backupPwOn) Column(Modifier.padding(start = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Field("Пароль (щонайменше 6 символів)", p1, password = true) { p1 = it }
        Field("Повторіть пароль", p2, password = true) { p2 = it }
        T("Без пароля зашифровану копію не відновити — запишіть його. Пароль зберігається на цьому телефоні зашифрованим, щоб робити автокопії.", 11.sp, C.n500)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("Скасувати", false, Modifier.weight(1f)) { edit = false; p1 = ""; p2 = "" }
            Btn("Увімкнути", true, Modifier.weight(1f)) { if (vm.setBackupPassword(p1, p2)) { edit = false; p1 = ""; p2 = "" } }
        }
    }
}

/** Автокопія раз на тиждень у вибрану папку (8 останніх) і нагадування про виплати ОВДП. */
@Composable
private fun AutoBackupRows(vm: BellaViewModel) {
    val pickDir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) vm.chooseAutoDir(uri) }
    val on = vm.autoDirName.isNotEmpty()
    val last = if (vm.autoAt > 0) fmtSavedAt(vm.autoAt).ifEmpty { "—" } else "ще не було"
    SettingRow(Icons.Outlined.Schedule, "Автокопія щотижня", if (on) "Папка «${vm.autoDirName}» · остання: $last · зберігаються $AUTO_KEEP останніх" else "Вимкнено. Виберіть папку на телефоні, наприклад Documents/Bella", trailing = true) { pickDir.launch(null) }
    if (on) Row(Modifier.padding(start = 32.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        T("Зберегти зараз", 13.sp, C.accent400, modifier = Modifier.clickable { vm.backupNow() }.padding(vertical = 4.dp))
        T("Змінити папку", 13.sp, C.accent400, modifier = Modifier.clickable { pickDir.launch(null) }.padding(vertical = 4.dp))
        T("Вимкнути", 13.sp, C.neg, modifier = Modifier.clickable { vm.disableAutoBackup() }.padding(vertical = 4.dp))
    }
    if (vm.autoMsg.isNotEmpty()) T(vm.autoMsg, 12.sp, if (vm.autoMsg.startsWith("Не") || vm.autoMsg.startsWith("Спершу")) C.neg else C.n500, modifier = Modifier.padding(start = 32.dp))
    val askPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> vm.setRemind(true, ok) }
    SettingRow(Icons.Outlined.NotificationsActive, "Нагадування про ОВДП", if (vm.remindOn) "Увімкнено: за 3 дні й у день виплати купона чи погашення" else "Вимкнено") {
        if (vm.remindOn) vm.setRemind(false)
        else if (android.os.Build.VERSION.SDK_INT >= 33) askPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        else vm.setRemind(true, true)
    }
    SettingRow(Icons.Outlined.AccountBalance, "Interactive Brokers", if (vm.ibkr.on) "Підключено · синхронізація при кожному відкритті (не частіше раз на годину)" + (if (vm.ibkr.at > 0) " · остання: ${fmtSavedAt(vm.ibkr.at)}" else "") else "Позиції ETF, гроші й дивіденди з IBKR", trailing = true) { vm.openIbkr() }
    val askPerm2 = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> vm.setMoney(true, ok) }
    SettingRow(Icons.Outlined.NotificationsActive, "Сповіщення про ліміти й борги", if (vm.moneyOn) "Увімкнено: витрати досягли 80% і 100% ліміту; за 3 дні й у день терміну боргу" else "Вимкнено") {
        if (vm.moneyOn) vm.setMoney(false)
        else if (android.os.Build.VERSION.SDK_INT >= 33) askPerm2.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        else vm.setMoney(true, true)
    }
    if (vm.remindMsg.isNotEmpty()) T(vm.remindMsg, 12.sp, C.neg, modifier = Modifier.padding(start = 32.dp))
}

/** «Відмінити останні дії» — список, що розгортається: до 10 останніх змін, дотик відміняє її й усі новіші. */
@Composable
private fun UndoList(vm: BellaViewModel) {
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val items = vm.recentActions()
    Column {
        SettingRow(Icons.AutoMirrored.Outlined.Undo, "Відмінити останні дії", if (items.isEmpty()) "Немає дій для відміни" else "Останніх дій: ${items.size}. Дотик розгортає список", trailing = false) { if (items.isNotEmpty()) open = !open }
        if (open && items.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(start = 32.dp)) {
            items.forEach { (k, label) ->
                Row(Modifier.fillMaxWidth().clickable { vm.undoLast(k); if (vm.recentActions().isEmpty()) open = false }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    T("$k.", 12.sp, C.n500, modifier = Modifier.width(22.dp)); T(label, 13.sp, modifier = Modifier.weight(1f), maxLines = 2)
                    T(if (k == 1) "Відмінити" else "Відмінити $k", 12.sp, C.accent400, maxLines = 1)
                }
            }
            T("Відміна дії скасовує і всі новіші: «Відмінити 3» повертає стан до третьої дії зверху.", 11.sp, C.n500, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun SecurityList(vm: BellaViewModel) {
    val on = vm.sec.hash.isNotEmpty()
    SettingRow(if (on) Icons.Outlined.Lock else Icons.Outlined.LockOpen, "PIN-код", if (on) "Увімкнено. Натисніть, щоб вимкнути" else "Вимкнено. Натисніть, щоб створити") { if (on) vm.startPin(KMode.Old, "disable") else vm.startPin(KMode.New, "enable") }
    if (on) {
        SettingRow(Icons.Outlined.Fingerprint, "Відбиток пальця", if (vm.sec.bio) "Увімкнено" else "Вимкнено") { vm.setBio(!vm.sec.bio) }
        SettingRow(Icons.Outlined.Password, "Змінити PIN-код", "Потрібен поточний PIN") { vm.startPin(KMode.Old, "change") }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { Ico(Icons.Outlined.Timer, C.accent, 20.dp); T("Автоблокування", 14.sp, weight = FontWeight.Medium) }
            Seg(listOf(0 to "Одразу", 1 to "1 хв", 5 to "5 хв"), vm.sec.autoMin) { vm.setAuto(it) }
        }
        SettingRow(Icons.Outlined.Lock, "Заблокувати зараз", "") { vm.lockNow() }
    }
    T("PIN зберігається лише як хеш PBKDF2 (120 000 ітерацій, випадкова сіль) на цьому пристрої. Після 5 хибних спроб — пауза 30 с, далі вдвічі довше (до 15 хв), і перезапуск її не знімає. Відбиток пальця працює через системний BiometricPrompt.", 11.sp, C.n500)
}

internal val HELP = listOf(
    Icons.Outlined.Home to ("Головна" to "Тут чистий капітал, зміна за місяць, графік капіталу, останні операції та розділи. На графіку виберіть період (1М, 3М, 1Р, Усе); дотик або ведення пальцем показує капітал за день. Знімок капіталу зберігається щодня. Око в шапці ховає всі суми. Стрілка назад скасовує останню зміну даних. Шестерня відкриває налаштування."),
    Icons.Outlined.CurrencyExchange to ("Валюта" to "Кнопка з символом валюти в шапці перемикає гривню, долар, євро та злотий. Усі суми на всіх екранах перераховуються за курсами, закладеними в застосунку."),
    Icons.Outlined.Savings to ("Капітал та Рахунки" to "Показує склад капіталу: гроші на картках, заощадження, інвестиції, цілі та борги. Натисніть на рахунок, щоб змінити назву чи баланс, позначити його як заощадження або видалити. Кнопка «Переказ» переносить гроші між рахунками з конвертацією валют."),
    Icons.Outlined.Paid to ("Борги та позики" to "У розділі «Борги та позики» додайте запис «Мені винні» або «Я винен» з терміном. Дотик до запису — редагування (особа, сума, термін, напрям), часткове повернення й видалення. Позначка біля запису означає «повернено повністю»: запис зникає зі списку, а чистий капітал перераховується. Помилково прибраний запис повертає «Скасувати» або «Відмінити останні дії»."),
    Icons.Outlined.AccountBalanceWallet to ("Бюджет" to "Вкладки: огляд за категоріями, операції, звіт по місяцях (зі стовпчиками доходів і витрат: дотик до місяця показує суми) та ліміти. Стрілки поруч з місяцем перемикають період. Плюс у шапці додає дохід або витрату. «Огляд» поточного місяця: картка «Вільно до кінця місяця» (доходи − витрати − заплановані повторювані − залишок лімітів) і стрілки біля категорій — зміна витрат порівняно з тими самими днями минулого місяця. «Операції → Вибрати» — масова зміна категорії чи видалення. Сповіщення про 80% і 100% ліміту та про терміни боргів вмикаються в «Налаштуваннях». «Ліміти → Правила категорій»: текст в описі (кілька через кому) → категорія; діють під час імпорту виписки, до старих операцій — кнопкою «Застосувати до старих». Коли вручну міняєте категорію операції, застосунок запропонує створити таке правило. «Звіт → Рік / 12 міс.»: доходи, витрати й норма заощаджень порівняно з минулим періодом, витрати за категоріями, зростання капіталу й інвестиційний дохід (купони, відсотки, дивіденди), експорт у Excel."),
    Icons.Outlined.Search to ("Пошук і фільтри" to "У вкладці «Операції» є пошук за описом чи категорією, кнопки сортування за датою та сумою, фільтри за діапазоном дат і сум, а також вибір типу: усі, витрати, доходи, перекази."),
    Icons.Outlined.FileUpload to ("Імпорт виписки" to "Налаштування, Імпорт виписки. Підтримує Excel (.xlsx) з банку та CSV. Перекази між власними картками не рахуються доходом чи витратою. Якщо у файлі є повтори, з'явиться кнопка «Прибрати повтори». Файл читається лише на вашому пристрої."),
    Icons.AutoMirrored.Outlined.TrendingUp to ("Інвестиції" to "Кільцева діаграма вгорі показує структуру портфеля. Групи (ОВДП, Депозити, ETF, Крипто, Готівка) розгортаються дотиком; порожні не показуються. «Позиція» додає нову. Депозит: сума, ставка, дата виплати й повернення, «Повторювати щомісяця» створює відсотки в дохід. ETF: оберіть валюту фонду, вкажіть кількість і ціни; ETF з Interactive Brokers оновлюються самі під час синхронізації. ОВДП: у формі позиції «Вибрати випуск» — список з довідника НБУ, «Звірити ОВДП з НБУ» під кнопками — розбіжності наявних позицій з НБУ (див. «Довідник ОВДП НБУ»). «Найближчі виплати ОВДП» — купони й погашення на 60 днів. Внизу — калькулятор складного відсотка. Interactive Brokers: «Налаштування → Interactive Brokers» — токен і номер Flex-запиту; далі при кожному відкритті застосунку, не частіше раз на годину (і кнопкою «Синхронізувати з IBKR» у групі ETF) оновлюються кількість і ціни ETF, гроші на рахунку («IBKR USD» тощо) і дивіденди."),
    Icons.Outlined.NotificationsActive to ("Нагадування про ОВДП" to "Налаштування, Нагадування про ОВДП. Сповіщення приходить за 3 дні до виплати купона чи погашення і в день виплати, близько 9:00. Якщо ISIN позиції є в довіднику НБУ (випуск вибрано з довідника або ISIN UA4000… є в назві), дати й суми беруться з офіційного графіка виплат: сума на 1 папір × кількість; так само будується календар ОВДП. Інакше дати беруться з позиції, як раніше: для позицій з калькулятора — від дати погашення з кроком виплат, для інших — від «наступної виплати» кожні 6 місяців; такі суми орієнтовні."),
    Icons.Outlined.Calculate to ("Калькулятор ОВДП" to "Введіть ISIN, номінал, ставку купона, дати, ціну, кількість та періодичність виплат. Для виплати «в кінці» вкажіть початок купона (дату випуску): купон рахується від неї за actual/360, як у довідці брокера. Побачите дохідність (проста річна), графік виплат і розрахунок продажу; продаж у день погашення рахується як погашення. ОВДП для фізичних осіб не оподатковуються. «Вибрати випуск з довідника НБУ» заповнює ISIN, валюту, номінал, купон, періодичність і погашення з довідника. Під кнопкою — випуски з останніх аукціонів НБУ з дохідністю аукціону (це дохідність розміщення, а не ринкова ціна); поки довідник не завантажено — приклади випусків."),
    Icons.Outlined.TaskAlt to ("Довідник ОВДП НБУ" to "Довідник цінних паперів Депозитарію НБУ (відкриті дані НБУ) завантажується одним запитом раз на 7 днів або кнопкою «Оновити»; у списку лише ще не погашені ОВДП (ISIN UA4000…). Довідник зберігається на телефоні зашифровано разом з іншими даними; без інтернету працює збережений, вгорі видно дату оновлення. У формі позиції ОВДП «Вибрати випуск»: пошук за ISIN, фільтри валюти (UAH, USD, EUR) і строку до погашення; вибір заповнює ISIN, валюту, номінал, купон, дату погашення й графік виплат, а кількість і ціну купівлі вводите самі. Ручне введення лишається. «Купони в дохід автоматично» у формі позиції: у день купона створюється дохід «Купон» на вибраний рахунок, у день погашення номінал переходить на рахунок переказом (не дохід), а позиція позначається погашеною; обробляються лише виплати від дня ввімкнення, тож внесені раніше вручну купони не дублюються. «Дохідність аукціону» — середньозважена дохідність останнього розміщення за результатами аукціонів НБУ; це не ринкова ціна і не ціна банку. «Звірити ОВДП з НБУ» в «Інвестиціях» або «Звірити з НБУ» у формі позиції показує розбіжності (погашення, купон, наступна виплата, номінал, валюта) і пропонує оновити; кількість, ціни й валюта не змінюються, зміну можна скасувати. Для інфляційних випусків і випусків з індексованою вартістю суми купонів у графіку НБУ можуть переглядатися."),
    Icons.AutoMirrored.Outlined.CompareArrows to ("Порівняти ОВДП" to "«ОВДП та Інвестиції → Порівняти»: 2–3 випуски з довідника НБУ («З довідника») або з ваших позицій («З моїх позицій») на одну суму. Для кожного введіть повну ціну за 1 шт — скільки списують разом із накопиченим купоном; підказка — ціна, що відповідає дохідності останнього аукціону, «підставити» вводить її. Розрахунок — за офіційним графіком виплат НБУ: ціла кількість облігацій і залишок грошей, усі виплати з датами, дохідність до погашення (YTM, як XIRR в Excel, рік — 365 днів), простий річний дохід, дохід до погашення в гривнях і на кожні 100 000 ₴, дата повернення грошей і днів до погашення. Валютні випуски — у валюті й у гривнях за поточним курсом НБУ; курс на дати виплат невідомий, це валютний ризик. Повзунок «Припущена ставка реінвестування» (за замовчуванням — дохідність коротшого) показує підсумок кожного варіанту на дату погашення найдовшого, а «ставка беззбитковості» — за якої ставки підсумки коротшого й довшого однакові. «Драбина» ділить суму порівну між вибраними випусками. Графік показує накопичену вартість у часі. «Додати в портфель» відкриває форму позиції з випуском, кількістю й ціною. Податок: зараз ОВДП для фізосіб не оподатковуються; поле «Податок на дохід ОВДП» — на випадок змін. Це розрахунок, а не порада."),
    Icons.Outlined.Flag to ("Цілі та пасивний дохід" to "«Цілі»: кнопка «+» створює ціль (назва, сума, дедлайн, внесок на місяць). Дотик до картки — редагування й видалення. «+ сума» додає крок, «Внести / зняти» — довільну суму, за бажанням з рахунку. Під смугою видно прогноз, коли досягнете цілі. «Пасивний дохід» показує місячний дохід з інвестицій (депозити після податку 23%) і «Прогноз фінансової незалежності»: коли дохід покриє потрібну суму з урахуванням інфляції."),
    Icons.Outlined.VerifiedUser to ("Безпека" to "Налаштування, Безпека: PIN-код із 4 цифр, відбиток пальця, автоблокування та ручне блокування. PIN зберігається лише як хеш PBKDF2 (120 000 ітерацій, випадкова сіль). Після 5 хибних спроб потрібно зачекати 30 секунд, кожна наступна хибна спроба подвоює паузу (до 15 хвилин); перезапуск застосунку паузу не знімає. Усі дані на телефоні зашифровані ключем із захищеного сховища Android (AES-256). Скриншоти й запис екрана в застосунку заборонені, а в «Недавніх програмах» замість вмісту — порожнє вікно."),
    Icons.Outlined.NewReleases to ("Що нового" to "1.29.0 — «Порівняти ОВДП»: 2–3 випуски на одну суму за графіком НБУ — дохідність до погашення, дохід, ставка беззбитковості, драбина, графік накопиченої вартості. 1.28.0 — купони ОВДП у дохід автоматично на вибраний рахунок (у день погашення номінал переходить на рахунок, позиція позначається погашеною), вибір випуску з довідника НБУ в калькуляторі. 1.27.0 — вибір випуску ОВДП з довідника НБУ (пошук за ISIN, фільтри валюти й строку), офіційний графік виплат для нагадувань і календаря, «Звірити з НБУ» для наявних позицій, дохідність останніх аукціонів у калькуляторі; у формі позиції поля, які ще треба заповнити, підсвічені бурштиновим. 1.26.0 — безпека: дані на телефоні зашифровані (AES-256, ключ у захищеному сховищі Android), PIN на PBKDF2 зі стійким блокуванням після хибних спроб, заборона скриншотів, виключення з хмарних копій Google, шифрування резервних копій паролем, екран відновлення, якщо дані не розшифровуються. Функції не змінились, наявні дані переносяться автоматично. 1.25 — календар, розподіл і реінвестування ОВДП. 1.24 — валюти GBP і CHF, графіки й картка IBKR без Yahoo, менший APK."),
    Icons.Outlined.Save to ("Збереження даних" to "Дані зберігаються автоматично на цьому пристрої і ніде більше. Налаштування, Автокопія щотижня: виберіть папку на телефоні (наприклад, Documents/Bella), і раз на тиждень туди зберігатиметься копія bella-auto-РРРР-ММ-ДД.json; лишаються 8 найновіших. Щоб копії були і в хмарі, синхронізуйте цю папку з Google Drive окремою програмою або час від часу копіюйте файл. Відновлення — «Відновити з копії». «Шифрувати копію паролем» шифрує і ручні копії, і автокопії (AES-256, ключ із пароля): без пароля такий файл не відкрити, тож запишіть його. Старі відкриті копії відновлюються як і раніше. Довідник ОВДП НБУ в копію не входить: після відновлення він завантажується заново. Дані застосунку не потрапляють у хмарну копію Google і не переносяться системою на новий телефон — для перенесення використовуйте резервну копію. Якщо після скидання захищеного сховища дані не розшифровуються, застосунок нічого не стирає, а пропонує відновити їх з автокопії чи файлу. Налаштування, Стерти дані видаляє все без можливості відновлення."),
)

@Composable
private fun HelpList(vm: BellaViewModel) {
    // розділ, що відповідає поточному екрану, показується першим
    val order = HELP.indices.sortedBy { if (it == vm.helpTop) -1 else it }
    order.forEach { i ->
        val (ic, tt) = HELP[i]
        Column {
            Row(Modifier.fillMaxWidth().clickable { vm.help = if (vm.help == i) -1 else i }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Ico(ic, C.accent, 20.dp); T(tt.first, 14.sp, weight = FontWeight.Medium, modifier = Modifier.weight(1f)); Ico(if (vm.help == i) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, C.n600, 16.dp)
            }
            if (vm.help == i) T(tt.second, 12.sp, C.n400, modifier = Modifier.padding(start = 32.dp, bottom = 8.dp))
            Div()
        }
    }
}

@Composable
private fun ImportForm(vm: BellaViewModel) {
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = try {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) c.getString(i).orEmpty() else ""
                } ?: ""
            } catch (_: Exception) { "" }
            vm.importUri(uri, name)
        }
    }

    T("Виписка банку в Excel (.xlsx) або CSV: дата; опис; сума (від'ємна сума це витрата).", 12.sp, C.n500)

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Btn("Обрати файл виписки", false, Modifier.weight(1f), Icons.Outlined.FileUpload) {
            picker.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "text/csv", "text/plain", "application/octet-stream", "*/*"))
        }
        Btn("Демо-приклад", false) { vm.importSample() }
    }

    if (vm.impBusy) {
        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(18.dp), color = C.accent, strokeWidth = 2.dp)
            T("Обробка виписки та перевірка дублікатів…", 13.sp, C.n500)
        }
    }

    val r = vm.impResult
    val items = vm.impItems

    if (r != null) {
        if (r.error.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.neg.copy(alpha = 0.15f)).padding(12.dp)) {
                T(r.error, 13.sp, C.neg)
            }
        } else {
            // Загальна інформація про діапазон дат та файлу
            T(r.info, 12.sp, C.text, FontWeight.Medium)
            if (r.info2.isNotEmpty()) T(r.info2, 11.sp, C.n500)

            val selectedCount = items.count { it.selected }
            val dupCount = items.count { it.isDuplicate }

            // Метрики підсумку виписки
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(C.surface).padding(8.dp)) {
                    Column {
                        T("У виписці", 10.sp, C.n500)
                        T("${items.size}", 16.sp, C.text, FontWeight.Bold)
                    }
                }
                Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(C.surface).padding(8.dp)) {
                    Column {
                        T("До імпорту", 10.sp, C.n500)
                        T("$selectedCount", 16.sp, C.pos, FontWeight.Bold)
                    }
                }
                if (dupCount > 0) {
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(C.surface).padding(8.dp)) {
                        Column {
                            T("Дублікатів", 10.sp, C.n500)
                            T("$dupCount", 16.sp, Color(0xFFFBBF24), FontWeight.Bold)
                        }
                    }
                }
            }

            // Перемикач пропуску дублікатів
            if (dupCount > 0) {
                Row(
                    Modifier.fillMaxWidth().clickable { vm.toggleSkipDuplicates(!vm.impSkipDuplicates) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Ico(if (vm.impSkipDuplicates) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, C.accent, 20.dp)
                    T("Автоматично пропускати виявлені дублікати ($dupCount)", 12.sp, C.text)
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                T("Попередній перегляд операцій:", 12.sp, C.n500, FontWeight.Medium)
                val allSel = items.isNotEmpty() && items.all { it.selected }
                T(if (allSel) "Зняти всі" else "Обрати всі", 12.sp, C.accent, modifier = Modifier.clickable { vm.selectAllImport(!allSel) })
            }

            // Картки залишків рахунків, якщо виписка містить картки
            if (r.accs.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().clickable { vm.impAcc = !vm.impAcc }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Ico(if (vm.impAcc) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, C.accent, 20.dp)
                    T("Оновити рахунки за залишками карток (${r.accs.size})", 12.sp)
                }
            }

            // Список операцій для перегляду з можливістю індивідуального вибору
            Column {
                items.take(40).forEachIndexed { i, item ->
                    Div()
                    Column(Modifier.fillMaxWidth().clickable { vm.toggleImportItem(i) }) {
                        TxRowView(
                            t = item.tx,
                            vm = vm,
                            selected = item.selected,
                            onClick = { vm.toggleImportItem(i) }
                        )
                        if (item.isDuplicate) {
                            Row(
                                Modifier.padding(start = 32.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(
                                    Modifier.clip(RoundedCornerShape(3.dp))
                                        .background(Color(0xFFFBBF24).copy(alpha = 0.2f))
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                ) {
                                    T("ДУБЛІКАТ", 9.sp, Color(0xFFFBBF24), FontWeight.Bold)
                                }
                                T(item.duplicateReason, 10.sp, C.n500, maxLines = 1)
                            }
                        }
                    }
                }
                if (items.size > 40) {
                    Div()
                    T("…ще ${items.size - 40} операцій буде імпортовано згідно з вибором.", 11.sp, C.n500, modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }
    }
}
