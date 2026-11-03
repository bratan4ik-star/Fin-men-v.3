package ua.bella.finance

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class Screen { Home, Acc, Bud, Inv, Goals, Pass, Calc }
enum class Sheet { None, Transfer, Acc, Debt, Tx, TxE, Pos, PosE, AccE, Settings, Security, Help, Import }
enum class KMode { None, Unlock, Old, New, Confirm }

/** Усі поля форм у шитах (відповідають полям state у прототипі). */
data class Form(
    val from: Int = 0, val to: Int = 1, val amount: String = "",
    val name: String = "", val bal: String = "", val cur: String = "UAH", val sav: Boolean = false,
    val who: String = "", val kindOwedToMe: Boolean = true, val amt: String = "", val due: String = "",
    val tType: String = "expense", val tAmt: String = "", val tCat: String = "Харчування", val tDesc: String = "",
    val group: String = "ОВДП", val pName: String = "", val pQty: String = "", val pBuy: String = "", val pNow: String = "",
    val pRate: String = "", val pMat: String = "", val pNext: String = "",
    val editIdx: Int = 0,
    // операція: дата, рахунок, для переказу — рахунок і сума зарахування; txIdx — індекс операції, що редагується
    val tDate: String = "", val tAcc: String = "", val tToAcc: String = "", val tToAmt: String = "", val txIdx: Int = -1,
)

data class Filters(val q: String = "", val tf: String = "all", val sort: String = "dateD", val dateFrom: String = "", val dateTo: String = "", val min: String = "", val max: String = "", val show: Boolean = false, val acc: String = "*")

class BellaViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("bella", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val loaded: Persisted = try { prefs.getString("state", null)?.let { json.decodeFromString<Persisted>(it) } ?: Persisted() } catch (e: Exception) { Persisted() }

    var data by mutableStateOf(ensureAccIds(loaded.data)); private set
    var cur by mutableStateOf(loaded.cur); private set
    var month by mutableStateOf(loaded.month); private set
    var ov by mutableStateOf(loaded.ov); private set
    var light by mutableStateOf(loaded.light); private set
    var sec by mutableStateOf(loaded.sec); private set

    // курси НБУ
    var ratesDate by mutableStateOf(loaded.ratesDate); private set
    var ratesAt by mutableStateOf(loaded.ratesAt); private set
    var ratesTick by mutableStateOf(0); private set
    var ratesBusy by mutableStateOf(false); private set
    var ratesMsg by mutableStateOf(""); private set

    var screen by mutableStateOf(Screen.Home)
    var sheet by mutableStateOf(Sheet.None)
    var hide by mutableStateOf(false)
    var curOpen by mutableStateOf(false)
    var tab by mutableStateOf(0)
    var form by mutableStateOf(Form(from = loaded.from, to = loaded.to))
    var filters by mutableStateOf(Filters())
    var open by mutableStateOf(setOf<String>())
    var help by mutableStateOf(-1)
    var eraseAsk by mutableStateOf(false)
    var calcIn by mutableStateOf(CalcInput())
    var impResult by mutableStateOf<ImportResult?>(null); private set
    var impBusy by mutableStateOf(false); private set
    var impAcc by mutableStateOf(true)

    // undo
    private val stack = ArrayDeque<AppData>()
    var undoN by mutableStateOf(0); private set
    var toastAt by mutableStateOf(0L); private set

    // lock
    var kmode by mutableStateOf(if (loaded.sec.hash.isNotEmpty()) KMode.Unlock else KMode.None); private set
    var kact by mutableStateOf("")
    var entry by mutableStateOf("")
    var tmp = ""
    var kerr by mutableStateOf("")
    private var fails = 0
    private var until = 0L
    var hiddenAt = 0L

    /** Читає ratesTick, щоб екрани перемальовувались після оновлення курсів. */
    private fun r(c: String): Double { if (ratesTick < 0) return 1.0; return RATES[c]!! }
    val rate get() = r(cur)
    val sym get() = SYM[cur]!!.trim()
    fun cv(v: Double) = v / rate
    fun m(v: Double) = if (hide) "••••" else fmtInt(cv(v))
    fun base(a: Account) = a.bal * r(a.cur)

    val months: List<String> get() = data.txs.map { it.date.take(7) }.distinct().sorted().ifEmpty { listOf(CUR_MONTH) }

    private fun persist() {
        try { prefs.edit().putString("state", json.encodeToString(Persisted(data, cur, month, form.from, form.to, ov, light, sec, RATES.toMap(), ratesDate, ratesAt))).apply() } catch (_: Exception) {}
    }

    init {
        loaded.rates.forEach { (c, v) -> if (c != "UAH" && v > 0 && RATES.containsKey(c)) RATES[c] = v }
        if (System.currentTimeMillis() - loaded.ratesAt > 6 * 3600_000L) refreshRates()
    }

    /** Завантажує курси USD, EUR, PLN з НБУ. Без інтернету лишає попередні курси й показує повідомлення. */
    fun refreshRates() {
        if (ratesBusy) return
        ratesBusy = true; ratesMsg = ""
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) { try { fetchNbuRates() } catch (_: Exception) { null } }
            if (res != null) {
                RATES.putAll(res.rates); ratesDate = res.date; ratesAt = System.currentTimeMillis(); ratesTick++
                ratesMsg = "Курси НБУ оновлено"; persist()
            } else ratesMsg = "Не вдалося оновити курси. Перевірте інтернет."
            ratesBusy = false
        }
    }

    /** Усі зміни даних проходять тут: зберігається знімок для «Скасувати» і показується тост. */
    fun commit(raw: AppData) {
        val nd = ensureAccIds(raw)
        if (nd == data) return
        stack.addLast(data); if (stack.size > 20) stack.removeFirst()
        data = nd; undoN = stack.size; persist()
        val t = System.currentTimeMillis(); toastAt = t
        viewModelScope.launch { delay(6000); if (toastAt == t) toastAt = 0 }
    }
    fun undo() {
        if (stack.isEmpty()) return
        data = stack.removeLast(); undoN = stack.size; toastAt = 0; persist()
    }

    @JvmName("selectCurrency") fun setCur(c: String) { cur = c; curOpen = false; persist() }
    @JvmName("selectMonth") fun setMonth(i: Int) { month = i.coerceIn(0, months.size - 1); persist() }
    fun toggleTheme() { light = !light; persist() }
    fun go(s: Screen) { screen = s; curOpen = false }
    fun openSheet(s: Sheet) { sheet = s }
    fun closeSheet() { sheet = Sheet.None }

    // ---------- відкриття форм ----------
    fun openTransfer() { form = form.copy(amount = ""); sheet = Sheet.Transfer }
    fun openAcc() { form = form.copy(name = "", bal = "", cur = "UAH"); sheet = Sheet.Acc }
    fun openDebt() { form = form.copy(who = "", amt = "", due = "", kindOwedToMe = true); sheet = Sheet.Debt }
    var formErr by mutableStateOf("")
    var txDelAsk by mutableStateOf(false); private set

    fun openTx() {
        formErr = ""; txDelAsk = false
        form = form.copy(tAmt = "", tDesc = "", tType = "expense", tCat = "Харчування", tDate = todayIso(), tAcc = data.accs.firstOrNull()?.id ?: "", tToAcc = data.accs.getOrNull(1)?.id ?: "", tToAmt = "", txIdx = -1)
        sheet = Sheet.Tx
    }
    /** Відкриває операцію на редагування. Рахунок, якого вже немає, показується як «Без рахунку». */
    fun editTx(t: Tx) {
        val i = data.txs.indexOf(t); if (i < 0) return
        val acc = data.accs.firstOrNull { it.id == t.acc }; val to = data.accs.firstOrNull { it.id == t.toAcc }
        fun s2(v: Double) = (Math.round(v * 100) / 100.0).toString()
        formErr = ""; txDelAsk = false
        form = form.copy(
            tType = t.type, tCat = t.cat, tDesc = t.desc, tDate = t.date, txIdx = i,
            tAmt = s2(if (acc != null && t.accAmt > 0) t.accAmt else t.amt / rate),
            tAcc = acc?.id ?: "", tToAcc = to?.id ?: "", tToAmt = if (t.type == "transfer" && to != null) s2(t.toAmt) else "",
        )
        sheet = Sheet.TxE
    }
    fun deleteTx() {
        if (!txDelAsk) { txDelAsk = true; return }
        txDelAsk = false
        val i = form.txIdx; if (data.txs.getOrNull(i) == null) { closeSheet(); return }
        commit(ua.bella.finance.deleteTx(data, i)); closeSheet()
    }
    /** Підпис рахунку для рядка операції: «Monobank» або «Monobank → Готівка USD». */
    fun accLabel(t: Tx): String {
        val a = data.accs.firstOrNull { it.id == t.acc }?.name ?: ""
        if (t.type == "transfer") { val b = data.accs.firstOrNull { it.id == t.toAcc }?.name ?: ""; return if (a.isEmpty() && b.isEmpty()) "" else "$a → $b" }
        return a
    }
    private fun txFromForm(f: Form, key: String): Tx? {
        val (t, err) = buildTx(TxInput(f.tType, parseNum(f.tAmt), if (f.tType == "transfer") "Перекази" else f.tCat, f.tDesc.trim(), f.tDate.trim(), f.tAcc, f.tToAcc, parseNum(f.tToAmt), key), data.accs, RATES, rate)
        formErr = err; return t
    }
    fun openPos() { form = form.copy(pName = "", pQty = "", pBuy = "", pRate = "", pMat = "", group = "ОВДП"); sheet = Sheet.Pos }
    fun editAcc(i: Int) { val a = data.accs[i]; form = form.copy(editIdx = i, sav = a.sav, name = a.name, bal = (Math.round(a.bal * 100) / 100.0).toString()); sheet = Sheet.AccE }
    fun editPos(i: Int) {
        val p = data.pos[i]; fun c(v: Double) = ((v / rate) * 100).let { Math.round(it) / 100.0 }.toString()
        form = form.copy(editIdx = i, group = p.group, pName = p.name, pQty = p.qty.toString(), pBuy = c(p.buy), pNow = c(p.now), pRate = p.rate.toString(), pMat = p.mat, pNext = p.next); sheet = Sheet.PosE
    }
    fun openSettings() { eraseAsk = false; sheet = Sheet.Settings }
    fun openImport() { impResult = null; sheet = Sheet.Import }

    // ---------- сабміти ----------
    fun submit() {
        val f = form; val s = data
        when (sheet) {
            Sheet.Transfer -> {
                val fa = s.accs.getOrNull(f.from); val ta = s.accs.getOrNull(f.to); val amt = parseNum(f.amount)
                if (fa != null && ta != null && amt > 0 && f.from != f.to && amt <= fa.bal) {
                    val conv = amt * RATES[fa.cur]!! / RATES[ta.cur]!!
                    val ids = ensureAccIds(s); val fid = ids.accs[f.from].id; val tid = ids.accs[f.to].id
                    commit(addTx(ids, Tx("Переказ: ${fa.name} → ${ta.name}", "Перекази", "transfer", amt * RATES[fa.cur]!!, todayIso(), "", fid, amt, tid, conv))); closeSheet()
                }
            }
            Sheet.Acc -> if (f.name.isNotBlank()) { commit(s.copy(accs = s.accs + Account(f.name.trim(), f.cur, parseNum(f.bal), cash = f.name.contains("Готівка")))); closeSheet() }
            Sheet.Debt -> { val v = parseNum(f.amt) * rate; if (f.who.isNotBlank() && v > 0) { commit(s.copy(debts = s.debts + Debt(f.who.trim(), f.kindOwedToMe, v, false, f.due.trim()))); closeSheet() } }
            Sheet.AccE -> if (f.name.isNotBlank() && s.accs.getOrNull(f.editIdx) != null) {
                val old = s.accs[f.editIdx]
                ov = ov + (old.name.takeLast(4) to Override(f.name.trim(), old.cur, parseNum(f.bal), f.sav))
                commit(s.copy(accs = s.accs.mapIndexed { i, a -> if (i == f.editIdx) a.copy(name = f.name.trim(), bal = parseNum(f.bal), sav = f.sav) else a })); closeSheet()
            }
            Sheet.Import -> importCommit()
            Sheet.PosE -> {
                val q = parseNum(f.pQty); val b = parseNum(f.pBuy) * rate; val nw = (parseNum(f.pNow).takeIf { it != 0.0 } ?: parseNum(f.pBuy)) * rate
                val o = s.pos.getOrNull(f.editIdx)
                if (f.pName.isNotBlank() && q > 0 && b > 0 && o != null) {
                    open = open + f.group
                    commit(s.copy(pos = s.pos.mapIndexed { i, x -> if (i == f.editIdx) Pos(f.group, f.pName.trim(), q, b, nw, f.pMat.trim(), parseNum(f.pRate), f.pNext.trim(), o.calc) else x })); closeSheet()
                }
            }
            Sheet.Pos -> {
                val q = parseNum(f.pQty); val b = parseNum(f.pBuy) * rate
                if (f.pName.isNotBlank() && q > 0 && b > 0) { open = open + f.group; commit(s.copy(pos = s.pos + Pos(f.group, f.pName.trim(), q, b, b, f.pMat.trim(), parseNum(f.pRate)))); closeSheet() }
            }
            Sheet.Tx -> {
                val t = txFromForm(f, "") ?: return
                commit(addTx(s, t)); closeSheet()
                setMonth(months.indexOf(t.date.take(7)).coerceAtLeast(0))
            }
            Sheet.TxE -> {
                val old = s.txs.getOrNull(f.txIdx) ?: run { closeSheet(); return }
                val t = txFromForm(f, old.key) ?: return
                commit(replaceTx(s, f.txIdx, t)); closeSheet()
            }
            else -> {}
        }
    }
    fun deleteAcc() { val s = data; commit(s.copy(accs = s.accs.filterIndexed { i, _ -> i != form.editIdx })); form = form.copy(from = 0, to = 0); closeSheet() }
    fun deletePos() { val s = data; commit(s.copy(pos = s.pos.filterIndexed { i, _ -> i != form.editIdx })); closeSheet() }
    fun toggleDebt(i: Int) { commit(data.copy(debts = data.debts.mapIndexed { j, d -> if (j == i) d.copy(done = !d.done) else d })) }
    fun addToGoal(i: Int) { commit(data.copy(goals = data.goals.mapIndexed { j, g -> if (j == i) g.copy(saved = Math.min(g.target, g.saved + g.step)) else g })) }
    fun erase() {
        if (!eraseAsk) { eraseAsk = true; return }
        eraseAsk = false; sheet = Sheet.None; open = emptySet(); month = 0
        commit(AppData(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), demo = false)); form = form.copy(from = 0, to = 0)
    }

    // ---------- калькулятор ----------
    fun posToCalc() {
        val f = form; sheet = Sheet.None; screen = Screen.Calc
        calcIn = calcIn.copy(isin = f.pName.removePrefix("ОВДП ").trim(), price = f.pBuy, qty = f.pQty, mat = f.pMat, coupon = f.pRate, sell = f.pNow.ifEmpty { f.pBuy }, sellD = f.pMat)
    }
    fun openPosInCalc(p: Pos) {
        val x = p.calc
        calcIn = CalcInput(x?.isin ?: p.name.removePrefix("ОВДП "), (x?.nom ?: 1000.0).toString().removeSuffix(".0"), x?.buy ?: TODAY, p.mat, p.buy.toString(), p.qty.toString(), p.rate.toString(), x?.freq ?: 6, x?.sell ?: p.now.toString(), x?.sellD ?: p.mat)
        screen = Screen.Calc
    }
    fun addCalcToInv() {
        val c = calcOvdp(calcIn); val pr = parseNum(calcIn.price)
        if (c.qty > 0) {
            open = open + "ОВДП"; screen = Screen.Inv
            commit(data.copy(pos = data.pos + Pos("ОВДП", "ОВДП " + calcIn.isin.ifEmpty { "без ISIN" }, c.qty.toDouble(), pr, pr, calcIn.mat, parseNum(calcIn.coupon), "",
                CalcExtra(calcIn.isin, parseNum(calcIn.nom).takeIf { it != 0.0 } ?: 1000.0, calcIn.freq, calcIn.sell, calcIn.sellD, calcIn.buy))))
        }
    }

    // ---------- імпорт ----------
    fun importUri(uri: Uri, name: String) {
        impBusy = true; impResult = null
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                try {
                    val ins = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?: return@withContext ImportResult(emptyList(), emptyList(), 0, "Не вдалося відкрити файл «$name» (немає доступу)")
                    val bytes = ins.use { it.readBytes() }
                    if (bytes.isEmpty()) ImportResult(emptyList(), emptyList(), 0, "Файл порожній")
                    else Importer.parseBytes(bytes, rate)
                } catch (e: Throwable) { ImportResult(emptyList(), emptyList(), 0, "Не вдалося прочитати файл (${e.javaClass.simpleName}: ${e.message?.take(80) ?: "—"})") }
            }
            impResult = r; impBusy = false
        }
    }
    fun importSample() { impResult = Importer.parseCsv("01.11.2026;Сільпо;-1250,40\n01.11.2026;Uber;-310\n02.11.2026;Аптека Доба;-480\n02.11.2026;Зарплата;45000\n02.11.2026;Spotify;-169", rate) }
    private fun existingKeys(): Set<String> = if (data.demo && impResult?.accs?.isNotEmpty() == true) emptySet() else data.txs.mapNotNull { it.key.ifEmpty { null } }.toSet()
    val dupN: Int get() { val h = existingKeys(); return impResult?.txs?.count { it.key.isNotEmpty() && it.key in h } ?: 0 }
    fun removeImportDups() { val r = impResult ?: return; val h = existingKeys(); impResult = r.copy(txs = r.txs.filter { it.key.isEmpty() || it.key !in h }) }
    private fun dedupe(txs: List<Tx>): List<Tx> { val seen = HashSet<String>(); return txs.filter { it.key.isEmpty() || seen.add(it.key) } }
    val dupInStore: Int get() = data.txs.size - dedupe(data.txs).size
    fun dedupeStore() { sheet = Sheet.None; commit(data.copy(txs = dedupe(data.txs))) }
    private fun applyOv(accs: List<Account>): List<Account> {
        val have = HashSet<String>()
        val out = accs.map { a -> val k = a.name.takeLast(4); val x = ov[k]; if (x == null) a else { have.add(k); Account(x.name, x.cur, x.bal, a.cash, x.sav) } }.toMutableList()
        ov.forEach { (k, x) -> if (k !in have) out.add(Account(x.name, x.cur, x.bal, false, x.sav)) }
        return out
    }
    private fun importCommit() {
        val r = impResult ?: return; if (r.txs.isEmpty()) return; val s = data
        val rep = s.demo && r.accs.isNotEmpty(); val keep = if (rep) emptyList() else s.txs
        val txs = r.txs + keep
        var nd = s.copy(txs = txs, demo = s.demo && !rep)
        if (rep) nd = nd.copy(pos = emptyList(), goals = emptyList(), debts = emptyList())
        if (impAcc && r.accs.isNotEmpty()) { nd = nd.copy(accs = carryAccIds(s.accs, applyOv(r.accs))); form = form.copy(from = 0, to = Math.min(1, r.accs.size - 1)) }
        commit(nd); tab = 1; sheet = Sheet.None
        month = data.txs.map { it.date.take(7) }.distinct().sorted().size - 1
        persist()
    }

    // ---------- безпека ----------
    private fun saveSec(s: Security) { sec = s; persist() }
    fun setBio(b: Boolean) = saveSec(sec.copy(bio = b))
    fun setAuto(m: Int) = saveSec(sec.copy(autoMin = m))
    fun startPin(mode: KMode, act: String) { sheet = Sheet.None; kmode = mode; kact = act; entry = ""; kerr = "" }
    fun lockNow() { sheet = Sheet.None; kmode = KMode.Unlock; entry = ""; kerr = "" }
    fun cancelPin() { kmode = KMode.None; entry = ""; kerr = ""; tmp = "" }
    fun unlockByBio() { kmode = KMode.None; entry = ""; kerr = "" }
    fun onStopped() { hiddenAt = System.currentTimeMillis() }
    fun onStarted() { if (sec.hash.isNotEmpty() && hiddenAt > 0 && kmode == KMode.None && System.currentTimeMillis() - hiddenAt >= sec.autoMin * 60000L) { kmode = KMode.Unlock; entry = ""; kerr = ""; sheet = Sheet.None } }
    fun backspace() { entry = entry.dropLast(1) }
    fun press(d: String) {
        if (kmode == KMode.None) return
        val now = System.currentTimeMillis()
        if (now < until) { kerr = "Забагато спроб. Зачекайте ${(until - now + 999) / 1000} с"; return }
        val v = entry + d
        if (v.length < 4) { entry = v; kerr = ""; return }
        entry = v; val h = hashPin(v); val m = kmode
        when (m) {
            KMode.Unlock, KMode.Old -> if (h == sec.hash) {
                fails = 0
                if (m == KMode.Unlock) { kmode = KMode.None; entry = ""; kerr = "" }
                else if (kact == "disable") { saveSec(Security("", false, sec.autoMin)); kmode = KMode.None; entry = ""; kerr = "" }
                else { kmode = KMode.New; entry = ""; kerr = "" }
            } else {
                fails++; entry = ""
                if (fails >= 5) { kerr = "Забагато спроб. Зачекайте 30 с"; until = now + 30000; fails = 0 } else kerr = "Невірний PIN. Спроба $fails з 5"
            }
            KMode.New -> { tmp = v; kmode = KMode.Confirm; entry = ""; kerr = "" }
            KMode.Confirm -> if (v == tmp) { saveSec(sec.copy(hash = h)); kmode = KMode.None; entry = ""; kerr = ""; tmp = "" } else { kmode = KMode.New; entry = ""; tmp = ""; kerr = "PIN не збігається. Створіть заново" }
            else -> {}
        }
    }
}
