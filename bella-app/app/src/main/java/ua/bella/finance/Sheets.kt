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
    Sheet.Import to "Імпорт виписки", Sheet.Pos to "Нова позиція", Sheet.Transfer to "Переказ між рахунками", Sheet.Acc to "Новий рахунок", Sheet.Debt to "Новий запис боргу", Sheet.Tx to "Нова операція",
)
private val SUBMIT = mapOf(Sheet.PosE to "Зберегти", Sheet.AccE to "Зберегти", Sheet.Import to "Імпортувати", Sheet.Pos to "Додати", Sheet.Transfer to "Виконати", Sheet.Acc to "Створити", Sheet.Debt to "Зберегти", Sheet.Tx to "Додати")

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
                    Sheet.Transfer -> TransferForm(vm); Sheet.Acc -> AccForm(vm); Sheet.Debt -> DebtForm(vm); Sheet.Tx -> TxForm(vm)
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
}

@Composable
private fun AccForm(vm: BellaViewModel) {
    val f = vm.form
    Field("Назва", f.name, hint = "напр. Monobank Чорна") { vm.form = f.copy(name = it) }
    Field("Початковий баланс", f.bal, hint = "0", numeric = true) { vm.form = f.copy(bal = it) }
    Seg(CURRENCIES.map { it to it }, f.cur) { vm.form = f.copy(cur = it) }
}

@Composable
private fun DebtForm(vm: BellaViewModel) {
    val f = vm.form
    Seg(listOf(true to "Мені винні", false to "Я винен"), f.kindOwedToMe) { vm.form = f.copy(kindOwedToMe = it) }
    Field("Особа / опис", f.who, hint = "напр. Іван") { vm.form = f.copy(who = it) }
    Field("Сума (${vm.sym})", f.amt, hint = "0", numeric = true) { vm.form = f.copy(amt = it) }
    Field("Термін повернення", f.due, hint = "РРРР-ММ-ДД") { vm.form = f.copy(due = it) }
}

@Composable
private fun TxForm(vm: BellaViewModel) {
    val f = vm.form
    Seg(listOf("expense" to "Витрата", "income" to "Дохід"), f.tType) { vm.form = f.copy(tType = it) }
    Field("Сума (${vm.sym})", f.tAmt, hint = "0", numeric = true) { vm.form = f.copy(tAmt = it) }
    val cats = CATEGORIES.filter { it != "Зарплата" || f.tType == "income" }
    Dropdown("Категорія", cats, cats.indexOf(f.tCat)) { vm.form = f.copy(tCat = cats[it]) }
    Field("Опис", f.tDesc, hint = "напр. Сільпо") { vm.form = f.copy(tDesc = it) }
}

@Composable
private fun PosForm(vm: BellaViewModel, edit: Boolean) {
    val f = vm.form
    Seg(GROUPS.map { it.first to it.first }, f.group) { vm.form = f.copy(group = it) }
    Field("Назва / тікер", f.pName, hint = if (edit) "" else "напр. VWCE") { vm.form = f.copy(pName = it) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Field("Кількість", f.pQty, Modifier.weight(1f), numeric = true) { vm.form = f.copy(pQty = it) }; Field("Ціна покупки (${vm.sym})", f.pBuy, Modifier.weight(1f), numeric = true) { vm.form = f.copy(pBuy = it) } }
    if (edit) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Field("Поточна ціна (${vm.sym})", f.pNow, Modifier.weight(1f), numeric = true) { vm.form = f.copy(pNow = it) }; Field("Ставка, % річних", f.pRate, Modifier.weight(1f), numeric = true) { vm.form = f.copy(pRate = it) } }
    else Field("Ставка, % річних", f.pRate, numeric = true) { vm.form = f.copy(pRate = it) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Field("Погашення", f.pMat, Modifier.weight(1f), "РРРР-ММ-ДД") { vm.form = f.copy(pMat = it) }
        if (edit) Field("Наступна виплата", f.pNext, Modifier.weight(1f), "РРРР-ММ-ДД") { vm.form = f.copy(pNext = it) }
    }
    if (edit) {
        if (f.group == "ОВДП") Row(Modifier.clickable { vm.posToCalc() }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Ico(Icons.Outlined.Calculate, C.accent400, 16.dp); T("Відкрити в калькуляторі ОВДП", 13.sp, C.accent400) }
        T("Видалити позицію", 13.sp, C.neg, modifier = Modifier.clickable { vm.deletePos() }.padding(vertical = 4.dp))
    }
}

@Composable
private fun AccEditForm(vm: BellaViewModel) {
    val f = vm.form; val a = vm.data.accs.getOrNull(f.editIdx)
    Field("Назва", f.name) { vm.form = f.copy(name = it) }
    Field("Поточний баланс (${a?.cur ?: ""})", f.bal, numeric = true) { vm.form = f.copy(bal = it) }
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
            val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else "" } ?: ""
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
