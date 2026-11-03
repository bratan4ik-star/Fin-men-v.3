package ua.bella.finance

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val TITLES = mapOf(
    Sheet.Help to "Довідка", Sheet.PosE to "Редагувати позицію", Sheet.AccE to "Редагувати рахунок", Sheet.Security to "Безпека", Sheet.Settings to "Налаштування",
    Sheet.Import to "Імпорт виписки", Sheet.Pos to "Нова позиція", Sheet.Transfer to "Переказ між рахунками", Sheet.Acc to "Новий рахунок", Sheet.Debt to "Новий запис боргу", Sheet.Tx to "Нова операція", Sheet.TxE to "Редагувати операцію",
)
private const val EDIT_NOTE = "Щоб побачити, що було, очистіть поле: старе значення з'явиться сірим. Порожнє поле під час збереження лишається як було."
private val SUBMIT = mapOf(Sheet.PosE to "Зберегти", Sheet.AccE to "Зберегти", Sheet.Import to "Імпортувати", Sheet.Pos to "Додати", Sheet.Transfer to "Виконати", Sheet.Acc to "Створити", Sheet.Debt to "Зберегти", Sheet.Tx to "Додати", Sheet.TxE to "Зберегти")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetHost(vm: BellaViewModel) {
    val sh = vm.sheet; if (sh == Sheet.None) return
    ModalBottomSheet(onDismissRequest = { vm.closeSheet() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = C.bg, contentColor = C.text) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                T(TITLES[sh] ?: "", 16.sp, weight = FontWeight.Medium, modifier = Modifier.weight(1f)); IconBtn(Icons.Outlined.Close, C.n500) { vm.closeSheet() }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (sh) {
                    Sheet.Transfer -> TransferForm(vm); Sheet.Acc -> AccForm(vm); Sheet.Debt -> DebtForm(vm); Sheet.Tx -> TxForm(vm, false); Sheet.TxE -> TxForm(vm, true)
                    Sheet.Pos, Sheet.PosE -> PosForm(vm, sh == Sheet.PosE); Sheet.AccE -> AccEditForm(vm)
                    Sheet.Settings -> SettingsList(vm); Sheet.Security -> SecurityList(vm); Sheet.Help -> HelpList(vm); Sheet.Import -> ImportForm(vm)
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

@Composable
private fun TransferForm(vm: BellaViewModel) {
    val f = vm.form; val accs = vm.data.accs; val fa = accs.getOrNull(f.from); val ta = accs.getOrNull(f.to); val amt = parseNum(f.amount)
    val names = accs.map { "${it.name} · ${nativeLabel(it, false)}" }
    Dropdown("З рахунку", names, f.from) { vm.form = f.copy(from = it) }
    Dropdown("На рахунок", names, f.to) { vm.form = f.copy(to = it) }
    Field("Сума (${fa?.cur ?: ""})", f.amount, hint = "0", numeric = true) { vm.form = f.copy(amount = it) }
    val hint = when {
        f.from == f.to -> "Оберіть різні рахунки"; fa != null && amt > fa.bal -> "Недостатньо коштів"
        amt > 0 && fa != null && ta != null -> "Надійде ${Math.round(amt * RATES[fa.cur]!! / RATES[ta.cur]!! * 100) / 100.0} ${ta.cur}"; else -> ""
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
    if (vm.formErr.isNotEmpty()) T(vm.formErr, 12.sp, C.neg)
}

@Composable
private fun DebtForm(vm: BellaViewModel) {
    val f = vm.form
    Seg(listOf(true to "Мені винні", false to "Я винен"), f.kindOwedToMe) { vm.form = f.copy(kindOwedToMe = it) }
    Field("Особа / опис", f.who, hint = "напр. Іван") { vm.form = f.copy(who = it) }
    Field("Сума (${vm.sym})", f.amt, hint = "0", numeric = true) { vm.form = f.copy(amt = it) }
    Field("Термін повернення", f.due, hint = "РРРР-ММ-ДД") { vm.form = f.copy(due = it) }
    if (vm.formErr.isNotEmpty()) T(vm.formErr, 12.sp, C.neg)
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
        val cats = (CATEGORIES.filter { it != "Зарплата" || f.tType == "income" } + f.tCat).distinct()
        Dropdown("Категорія", cats, cats.indexOf(f.tCat)) { vm.form = f.copy(tCat = cats[it]) }
    }
    Field("Опис", f.tDesc, hint = vm.was { it.tDesc }.ifEmpty { if (f.tType == "transfer") "необов'язково" else "напр. Сільпо" }) { vm.form = f.copy(tDesc = it) }
    Field("Дата", f.tDate, hint = vm.was { it.tDate }.ifEmpty { "РРРР-ММ-ДД" }) { vm.form = f.copy(tDate = it) }
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
private fun PosForm(vm: BellaViewModel, edit: Boolean) {
    val f = vm.form
    val dep = f.group == "Депозити"
    Seg(GROUPS.map { it.first to it.first }, f.group) { vm.form = f.copy(group = it) }
    Field(if (dep) "Назва депозиту" else "Назва / тікер", f.pName, hint = if (edit) vm.was { it.pName } else if (dep) "напр. Депозит Моно, 12 міс." else "напр. VWCE") { vm.form = f.copy(pName = it) }
    if (dep) {
        Field("Сума депозиту (${vm.sym})", f.pBuy, hint = vm.was { it.pBuy }.ifEmpty { "0" }, numeric = true) { vm.form = f.copy(pBuy = it) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (edit) Field("Поточна сума (${vm.sym})", f.pNow, Modifier.weight(1f), hint = vm.was { it.pNow }, numeric = true) { vm.form = f.copy(pNow = it) }
            Field("Ставка, % річних", f.pRate, Modifier.weight(1f), hint = vm.was { it.pRate }, numeric = true) { vm.form = f.copy(pRate = it) }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Field("Кількість", f.pQty, Modifier.weight(1f), hint = vm.was { it.pQty }, numeric = true) { vm.form = f.copy(pQty = it) }; Field("Ціна покупки (${vm.sym})", f.pBuy, Modifier.weight(1f), hint = vm.was { it.pBuy }, numeric = true) { vm.form = f.copy(pBuy = it) } }
        if (edit) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Field("Поточна ціна (${vm.sym})", f.pNow, Modifier.weight(1f), hint = vm.was { it.pNow }, numeric = true) { vm.form = f.copy(pNow = it) }; Field("Ставка, % річних", f.pRate, Modifier.weight(1f), hint = vm.was { it.pRate }, numeric = true) { vm.form = f.copy(pRate = it) } }
        else Field("Ставка, % річних", f.pRate, numeric = true) { vm.form = f.copy(pRate = it) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Field(if (dep) "Дата виплати відсотків" else "Наступна виплата", f.pNext, Modifier.weight(1f), vm.was { it.pNext }.ifEmpty { "РРРР-ММ-ДД" }) { vm.form = f.copy(pNext = it) }
        Field(if (dep) "Дата повернення" else "Погашення", f.pMat, Modifier.weight(1f), vm.was { it.pMat }.ifEmpty { "РРРР-ММ-ДД" }) { vm.form = f.copy(pMat = it) }
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
    if (vm.formErr.isNotEmpty()) T(vm.formErr, 12.sp, C.neg)
    if (edit) {
        T(EDIT_NOTE, 11.sp, C.n500)
        if (f.group == "ОВДП") Row(Modifier.clickable { vm.posToCalc() }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Ico(Icons.Outlined.Calculate, C.accent400, 16.dp); T("Відкрити в калькуляторі ОВДП", 13.sp, C.accent400) }
        T("Видалити позицію", 13.sp, C.neg, modifier = Modifier.clickable { vm.deletePos() }.padding(vertical = 4.dp))
    }
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
    T("Видалити рахунок", 13.sp, C.neg, modifier = Modifier.clickable { vm.deleteAcc() }.padding(vertical = 4.dp))
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
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Ico(Icons.Outlined.Lock, C.n500, 16.dp); T("Дані зберігаються автоматично на цьому пристрої", 11.sp, C.n500) }
    SettingRow(Icons.Outlined.FileUpload, "Імпорт виписки", "Excel або CSV з банку", trailing = true) { vm.openImport() }
    SettingRow(Icons.Outlined.VerifiedUser, "Безпека", if (vm.sec.hash.isNotEmpty()) "PIN-код" + (if (vm.sec.bio) " · відбиток пальця" else "") else "Захист вимкнено", trailing = true) { vm.openSheet(Sheet.Security) }
    SettingRow(Icons.Outlined.ContentCopy, "Прибрати повтори операцій", if (vm.dupInStore > 0) "Знайдено повторів: ${vm.dupInStore}" else "Повторів немає") { vm.dedupeStore() }
    SettingRow(Icons.Outlined.HelpOutline, "Довідка", "Як користуватися функціями", trailing = true) { vm.help = -1; vm.openSheet(Sheet.Help) }
    SettingRow(if (vm.light) Icons.Outlined.DarkMode else Icons.Outlined.LightMode, "Тема", if (vm.light) "Світла" else "Темна") { vm.toggleTheme() }
    SettingRow(Icons.Outlined.Delete, if (vm.eraseAsk) "Натисніть ще раз, щоб стерти" else "Стерти дані", if (vm.eraseAsk) "Усі операції, рахунки, борги, інвестиції та цілі буде видалено" else "Видалити всі операції, рахунки, борги, інвестиції та цілі", C.neg) { vm.erase() }
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
    T("PIN зберігається лише як хеш на цьому пристрої. Відбиток пальця працює через системний BiometricPrompt.", 11.sp, C.n500)
}

private val HELP = listOf(
    Icons.Outlined.Home to ("Головна" to "Тут чистий капітал, зміна за місяць, останні операції та розділи. Око в шапці ховає всі суми. Стрілка назад скасовує останню зміну даних. Шестерня відкриває налаштування."),
    Icons.Outlined.CurrencyExchange to ("Валюта" to "Кнопка з символом валюти в шапці перемикає гривню, долар, євро та злотий. Усі суми на всіх екранах перераховуються за курсами, закладеними в застосунку."),
    Icons.Outlined.Savings to ("Капітал та Рахунки" to "Показує склад капіталу: гроші на картках, заощадження, інвестиції, цілі та борги. Натисніть на рахунок, щоб змінити назву чи баланс, позначити його як заощадження або видалити. Кнопка «Переказ» переносить гроші між рахунками з конвертацією валют."),
    Icons.Outlined.Paid to ("Борги та позики" to "У розділі «Борги та позики» додайте запис «Мені винні» або «Я винен» з терміном. Позначка біля запису закреслює його, і чистий капітал перераховується."),
    Icons.Outlined.AccountBalanceWallet to ("Бюджет" to "Вкладки: огляд за категоріями, операції, звіт по місяцях та ліміти. Стрілки поруч з місяцем перемикають період. Плюс у шапці додає дохід або витрату."),
    Icons.Outlined.Search to ("Пошук і фільтри" to "У вкладці «Операції» є пошук за описом чи категорією, кнопки сортування за датою та сумою, фільтри за діапазоном дат і сум, а також вибір типу: усі, витрати, доходи, перекази."),
    Icons.Outlined.FileUpload to ("Імпорт виписки" to "Налаштування, Імпорт виписки. Підтримує Excel (.xlsx) з банку та CSV. Перекази між власними картками не рахуються доходом чи витратою. Якщо у файлі є повтори, з'явиться кнопка «Прибрати повтори». Файл читається лише на вашому пристрої."),
    Icons.Outlined.TrendingUp to ("Інвестиції" to "Групи ОВДП, Депозити, ETF та Крипто розгортаються по натисканню. Кнопка «Позиція» додає нову. Натисніть на позицію, щоб змінити кількість, ціну, ставку, дати чи видалити її."),
    Icons.Outlined.Calculate to ("Калькулятор ОВДП" to "Введіть ISIN, номінал, ставку купона, дати, ціну, кількість та періодичність виплат. Побачите дохідність до погашення, графік виплат зі статусом і розрахунок продажу. «Додати в інвестиції» створює позицію. ОВДП для фізичних осіб не оподатковуються."),
    Icons.Outlined.Flag to ("Цілі та пасивний дохід" to "У «Цілях» кнопка з сумою додає внесок до накопичення. «Пасивний дохід» показує місячний дохід з інвестицій, для депозитів після податку 23%."),
    Icons.Outlined.VerifiedUser to ("Безпека" to "Налаштування, Безпека: PIN-код із 4 цифр, відбиток пальця, автоблокування та ручне блокування. Після 5 хибних спроб потрібно зачекати 30 секунд."),
    Icons.Outlined.Save to ("Збереження даних" to "Дані зберігаються автоматично на цьому пристрої і ніде більше. Налаштування, Стерти дані видаляє все без можливості відновлення."),
)

@Composable
private fun HelpList(vm: BellaViewModel) {
    HELP.forEachIndexed { i, (ic, tt) ->
        Column {
            Row(Modifier.fillMaxWidth().clickable { vm.help = if (vm.help == i) -1 else i }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Ico(ic, C.accent, 20.dp); T(tt.first, 14.sp, weight = FontWeight.Medium, modifier = Modifier.weight(1f)); Ico(if (vm.help == i) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, C.n500, 16.dp)
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
    T("Виписка Excel (.xlsx) або CSV: дата; опис; сума (від'ємна сума це витрата).", 12.sp, C.n500)
    Btn("Обрати файл", false, Modifier.fillMaxWidth(), Icons.Outlined.FileUpload) { picker.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "text/csv", "text/plain", "application/octet-stream", "*/*")) }
    T("Спробувати на прикладі", 13.sp, C.accent400, modifier = Modifier.clickable { vm.importSample() })
    val r = vm.impResult
    if (vm.impBusy) T("Читаю файл…", 12.sp)
    if (r != null) {
        T(r.info, 12.sp); if (r.info2.isNotEmpty()) T(r.info2, 12.sp, C.n500)
        if (vm.dupN > 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Ico(Icons.Outlined.ContentCopy, C.n500, 16.dp); T("Повторів: ${vm.dupN}", 12.sp, modifier = Modifier.weight(1f)); Btn("Прибрати повтори", false) { vm.removeImportDups() }
        }
        if (r.accs.isNotEmpty()) Row(Modifier.clickable { vm.impAcc = !vm.impAcc }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Ico(if (vm.impAcc) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, C.accent, 20.dp); T("Оновити рахунки за залишками карток", 13.sp)
        }
        Column { r.txs.take(8).forEach { Div(); TxRowView(it, vm) } }
    }
}
