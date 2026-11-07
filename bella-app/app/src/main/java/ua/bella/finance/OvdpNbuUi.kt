package ua.bella.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/* Довідник ОВДП НБУ в інтерфейсі: вибір випуску у формі позиції і звірка наявних позицій (OvdpRegistry.kt). */

private const val PICK_MAX = 80

/** «ще 14 міс.» / «ще 2 р. 3 міс.» до погашення. */
fun termLeft(mat: String, today: LocalDate): String {
    val m = try { LocalDate.parse(mat) } catch (_: Exception) { return "" }
    val mo = ChronoUnit.MONTHS.between(today, m)
    return when { mo < 1 -> "ще ${ChronoUnit.DAYS.between(today, m)} дн."; mo < 24 -> "ще $mo міс."; mo % 12 == 0L -> "ще ${mo / 12} р."; else -> "ще ${mo / 12} р. ${mo % 12} міс." }
}

/** «1 купон», «3 купони», «5 купонів». */
fun couponsWord(n: Int): String { val a = n % 100; val b = n % 10
    return "$n " + if (a in 11..14) "купонів" else if (b == 1) "купон" else if (b in 2..4) "купони" else "купонів" }

private fun indexed(b: NbuBond) = b.kind.contains("інфляц", true) || b.kind.contains("індекс", true)

/** Стан довідника: дата оновлення, кнопка «Оновити», повідомлення. */
@Composable
fun NbuHeader(vm: BellaViewModel) {
    val r = vm.nbu
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            T("Довідник цінних паперів НБУ", 13.sp, weight = FontWeight.Medium)
            T(if (r.updatedAt > 0) "Оновлено ${fmtSavedAt(r.updatedAt)} · випусків ${r.bonds.size}" else "Ще не завантажено", 11.sp, C.n500)
        }
        Btn(if (vm.nbuBusy) "Оновлення…" else "Оновити", false, icon = Icons.Outlined.Refresh) { vm.refreshNbu() }
    }
    if (vm.nbuMsg.isNotEmpty()) T(vm.nbuMsg, 12.sp, if (vm.nbuMsg.startsWith("Довідник НБУ оновлено") || vm.nbuMsg.startsWith("Оновлено")) C.n500 else C.neg)
}

/** Вибір випуску: пошук за ISIN, фільтри валюти й строку до погашення. */
@Composable
fun OvdpPickView(vm: BellaViewModel) {
    val today = LocalDate.parse(TODAY)
    NbuHeader(vm)
    Field("Пошук за ISIN", vm.pickQ, hint = "напр. UA4000239149 або 2391") { vm.pickQ = it }
    Seg(listOf<Pair<String?, String>>(null to "Усі", "UAH" to "UAH", "USD" to "USD", "EUR" to "EUR"), vm.pickCur) { vm.pickCur = it }
    T("Строк до погашення", 12.sp, C.n500)
    Seg(listOf<Pair<Int?, String>>(null to "Усі", 0 to "до 6 міс", 1 to "6–12 міс", 2 to "1–2 роки", 3 to "2+ роки"), vm.pickTerm) { vm.pickTerm = it }
    val list = vm.pickList()
    if (vm.nbu.bonds.isEmpty()) {
        T(if (vm.nbuBusy) "Завантаження довідника НБУ…" else "Довідник ще не завантажено. Потрібен інтернет: натисніть «Оновити». Дані позиції можна ввести й вручну.", 13.sp, C.n500)
        return
    }
    T(if (list.isEmpty()) "Нічого не знайдено. Змініть пошук або фільтри." else "Знайдено ${list.size}" + if (list.size > PICK_MAX) " · показано перші $PICK_MAX, уточніть пошук" else "", 12.sp, C.n500)
    Column {
        list.take(PICK_MAX).forEach { b ->
            Div()
            val a = vm.nbu.auctionOf(b.isin)
            Column(Modifier.fillMaxWidth().clickable { vm.pickBond(b) }.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    T(b.isin, 14.sp, weight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1)
                    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(C.accent800).padding(horizontal = 6.dp, vertical = 2.dp)) { T(b.cur, 11.sp, C.accent300) }
                }
                T("купон ${fmt2(b.rate)}% · погашення ${ddmmyyyy(b.mat)} · ${termLeft(b.mat, today)}", 12.sp, C.n500, maxLines = 1)
                T(listOfNotNull(b.kind.ifEmpty { null }, "номінал ${fmt2(b.nom)} ${curSign(b.cur)}", nextCoupon(b, today).ifEmpty { null }?.let { "наступний купон ${ddmmyyyy(it)}" }).joinToString(" · "), 11.sp, C.n500, maxLines = 2)
                if (a != null) T("Дохідність аукціону ${fmt2(a.avg)}% · ${ddmmyyyy(a.date)}", 11.sp, C.accent400, maxLines = 1)
            }
        }
    }
    T("«Дохідність аукціону» — середньозважена дохідність останнього розміщення Мінфіну за даними НБУ, а не ринкова ціна чи котирування банку. Вибір заповнює ISIN, валюту, номінал, купон, погашення й графік виплат; кількість і ціну купівлі введіть самі.", 11.sp, C.n500)
}

/** Блок у формі ОВДП: «Вибрати випуск», вибраний випуск, звірка з НБУ (у редагуванні). */
@Composable
fun PosNbuSection(vm: BellaViewModel, edit: Boolean) {
    val f = vm.form; val today = LocalDate.parse(TODAY)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Btn("Вибрати випуск", false, Modifier.weight(1f), Icons.Outlined.Search) { vm.openOvdpPick() }
        if (edit) Btn("Звірити з НБУ", false, Modifier.weight(1f), Icons.Outlined.TaskAlt) { vm.togglePosCheck() }
    }
    val b = f.pBond
    if (b != null) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, C.divider, RoundedCornerShape(8.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("З довідника НБУ: ${b.isin} · ${b.cur}", 13.sp, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
            T("скинути", 12.sp, C.accent400, modifier = Modifier.clickable { vm.clearPickedBond() }.padding(4.dp))
        }
        val pays = b.pays.filter { it.d >= today.toString() }
        T("Номінал ${fmt2(b.nom)} ${curSign(b.cur)} · купон ${fmt2(b.rate)}% · погашення ${ddmmyyyy(b.mat)}", 12.sp, C.n500)
        T("Графік: ${couponsWord(pays.count { it.type == 1 })}" + (pays.firstOrNull()?.let { " · найближча виплата ${ddmmyyyy(it.d)} — ${fmt2(it.v)} ${curSign(b.cur)} на 1 папір" } ?: ""), 12.sp, C.n500)
        if (indexed(b)) T("${b.kind}: суми купонів у графіку НБУ можуть переглядатися.", 11.sp, C.n500)
        if (b.cur != "UAH") T("Ціни купівлі й поточну вводьте в ${b.cur}.", 11.sp, C.n500)
    }
    if (vm.sheet == Sheet.PosE && vm.posCheck) PosCheckInline(vm)
}

@Composable
private fun PosCheckInline(vm: BellaViewModel) {
    val o = vm.data.pos.getOrNull(vm.form.editIdx) ?: return
    val isin = posIsin(o)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.surface).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        NbuHeader(vm)
        val b = vm.nbuIndex[isin]
        when {
            isin.isEmpty() -> T("У позиції немає ISIN (UA4000…). Виберіть випуск з довідника, щоб звіряти дані.", 12.sp, C.n500)
            vm.nbu.bonds.isEmpty() -> T(if (vm.nbuBusy) "Завантаження довідника…" else "Довідник ще не завантажено.", 12.sp, C.n500)
            b == null -> T("Випуску $isin немає серед непогашених ОВДП у довіднику НБУ: можливо, його вже погашено або ISIN введено з помилкою.", 12.sp, C.n500)
            else -> {
                // порівнюються значення з полів форми (порожнє поле — як було), тож після «Підставити» розбіжності зникають
                val f = vm.form
                val cur = o.copy(mat = f.pMat.trim().ifEmpty { o.mat }, rate = if (f.pRate.isBlank()) o.rate else parseNum(f.pRate), next = f.pNext.trim().ifEmpty { o.next })
                val d = reconcile(cur, b, LocalDate.parse(TODAY))
                if (d.isEmpty()) T(if (cur != o) "✓ Поля збігаються з довідником НБУ — натисніть «Зберегти»" else "✓ Дані позиції збігаються з довідником НБУ", 12.sp, C.pos)
                else {
                    T("Розбіжності з НБУ ($isin):", 12.sp, weight = FontWeight.Medium)
                    d.forEach { DiffRow(it) }
                    if (d.any { it.field == "Валюта" }) T("Валюта не змінюється автоматично: ціни позиції записані в ${o.cur}.", 11.sp, C.n500)
                    Btn("Підставити дані НБУ у форму", true, Modifier.fillMaxWidth()) { vm.fillFormFromNbu(b) }
                    T("Поля форми оновляться; щоб записати, натисніть «Зберегти».", 11.sp, C.n500)
                }
            }
        }
    }
}

@Composable
private fun DiffRow(d: OvdpDiff) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        T(d.field, 12.sp, C.n500, modifier = Modifier.weight(1f))
        T(fmtDiffVal(d.mine), 12.sp, C.neg); T("→", 12.sp, C.n500); T(fmtDiffVal(d.nbu), 12.sp, C.pos, FontWeight.Medium)
    }
}
private fun fmtDiffVal(v: String) = if (isIsoDate(v)) ddmmyyyy(v) else v

/** «Звірити з НБУ» для всіх позицій ОВДП. */
@Composable
fun OvdpCheckView(vm: BellaViewModel) {
    NbuHeader(vm)
    val rows = vm.ovdpCheck()
    if (rows.isEmpty()) { T("У «Інвестиціях» немає позицій ОВДП.", 13.sp, C.n500); return }
    if (vm.nbu.bonds.isEmpty()) { T(if (vm.nbuBusy) "Завантаження довідника НБУ…" else "Довідник ще не завантажено. Потрібен інтернет: натисніть «Оновити».", 13.sp, C.n500); return }
    val withDiff = rows.filter { it.diffs.isNotEmpty() }
    T(if (withDiff.isEmpty()) "Розбіжностей не знайдено" else "Розбіжності: ${withDiff.size} з ${rows.size}", 13.sp, weight = FontWeight.Medium)
    Column {
        rows.forEach { r ->
            Div()
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                T(r.pos.name, 13.sp, weight = FontWeight.Medium, maxLines = 1)
                when {
                    r.isin.isEmpty() -> T("Немає ISIN — відкрийте позицію й виберіть випуск з довідника.", 11.sp, C.n500)
                    r.bond == null -> T("Немає серед непогашених ОВДП у довіднику НБУ (погашено або помилка в ISIN).", 11.sp, C.n500)
                    r.diffs.isEmpty() -> T("✓ Збігається з НБУ", 11.sp, C.pos)
                    else -> {
                        r.diffs.forEach { DiffRow(it) }
                        if (r.diffs.any { it.field == "Валюта" }) T("Валюта не змінюється автоматично: для іншої валюти додайте позицію заново.", 11.sp, C.n500)
                        if (r.diffs.any { it.field != "Валюта" }) T("Оновити з НБУ", 13.sp, C.accent400, modifier = Modifier.clickable { vm.applyNbuTo(listOf(r.idx)) }.padding(vertical = 4.dp))
                    }
                }
            }
        }
    }
    val fixable = withDiff.filter { r -> r.diffs.any { it.field != "Валюта" } }
    if (fixable.size > 1) Btn("Оновити всі (${fixable.size})", true, Modifier.fillMaxWidth()) { vm.applyNbuTo(fixable.map { it.idx }) }
    T("Оновлюються дата погашення, ставка купона, наступна виплата, номінал і ISIN; кількість і ціни не змінюються. Зміну можна скасувати кнопкою «Скасувати».", 11.sp, C.n500)
}
