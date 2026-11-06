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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate

enum class Screen { Home, Acc, Bud, Inv, Goals, Pass, Calc }
enum class Sheet { None, IbkrCharts, Transfer, Acc, Debt, DebtE, Tx, TxE, Rec, Rule, Ibkr, Cats, Goal, GoalE, GoalMove, Pos, PosE, AccE, Settings, Security, Help, Import }
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
    val pMonthly: Boolean = false, val pPayAcc: String = "", val cashF: Boolean = false,
    // ETF: валюта ціни, курс купівлі (₴ за 1 одиницю валюти)
    val pCur: String = "UAH", val pFx: String = "",
    // ціль: поля форми, індекс цілі; внесок/зняття — напрям, рахунок, сума
    val gName: String = "", val gTarget: String = "", val gSaved: String = "", val gStep: String = "", val gDeadline: String = "", val gIcon: String = "flag", val gIdx: Int = -1, val gMonthly: String = "",
    val gIn: Boolean = true, val gAcc: String = "", val gAmt: String = "",
    // повторювана операція: галочка в новій операції; редагування шаблону (індекс, число місяця, активність)
    val dIdx: Int = -1, val dPay: String = "",
    val tRepeat: Boolean = false, val rIdx: Int = -1, val rDay: String = "", val rActive: Boolean = true,
    // правило категорії: текст (фрагменти через кому), категорія, індекс (-1 — нове)
    val ruMatch: String = "", val ruCat: String = "Харчування", val ruIdx: Int = -1,
    // IBKR: токен і номер Flex-запиту (у формі лише під час введення; зберігаються зашифровано в IbkrStore)
    val ibTok: String = "", val ibQ: String = "",
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
    /** Розділ довідки, що показується першим (-1 — звичайний порядок). */
    var helpTop by mutableStateOf(-1)
    var eraseAsk by mutableStateOf(false)
    var calcIn by mutableStateOf(CalcInput())
    var impResult by mutableStateOf<ImportResult?>(null); private set
    var impBusy by mutableStateOf(false); private set
    var impAcc by mutableStateOf(true)

    // undo
    private val stack = ArrayDeque<AppData>()
    private val labels = ArrayDeque<String>()
    var undoN by mutableStateOf(0); private set
    var toastAt by mutableStateOf(0L); private set

    // lock
    var kmode by mutableStateOf(if (loaded.sec.hash.isNotEmpty()) KMode.Unlock else KMode.None); private set
    var kact by mutableStateOf("")
    var entry by mutableStateOf("")
    var tmp = ""
    var kerr by mutableStateOf("")
    // лічильник помилок PIN і час блокування зберігаються, тож перезапуск застосунку блокування не знімає
    private var fails = prefs.getInt("pinFails", 0)
    private var until = prefs.getLong("pinUntil", 0L)
    private fun saveLock() { prefs.edit().putInt("pinFails", fails).putLong("pinUntil", until).apply() }
    var hiddenAt = 0L

    /** Читає ratesTick, щоб екрани перемальовувались після оновлення курсів. */
    private fun r(c: String): Double { if (ratesTick < 0) return 1.0; return rateOf(c) }
    val rate get() = r(cur)
    val sym get() = (SYM[cur] ?: cur).trim()
    fun cv(v: Double) = v / rate
    fun m(v: Double) = if (hide) "••••" else fmtInt(cv(v))
    fun base(a: Account) = a.bal * r(a.cur)

    /** Місяці з операціями плюс поточний (щоб бюджет поточного місяця був доступний і без операцій). */
    val months: List<String> get() = (data.txs.map { it.date.take(7) } + CUR_MONTH).distinct().sorted()

    /** Час останнього успішного збереження на пристрої (мс), 0 — ще не зберігалось. */
    var savedAt by mutableStateOf(prefs.getLong("savedAt", 0L)); private set
    /** Щоденні знімки чистого капіталу (оновлюються під час кожного збереження). */
    var hist by mutableStateOf(loaded.hist); private set

    /** Історія рахунку IBKR для графіків (поза журналом «Скасувати», як і hist). */
    var ibNav by mutableStateOf(loaded.ibNav); private set
    var ibFlows by mutableStateOf(loaded.ibFlows); private set
    var ibBase by mutableStateOf(loaded.ibBase); private set
    /** Історія цін ETF з IBKR за символом (з Flex-звітів), до 5 років. */
    var ibPx by mutableStateOf(loaded.ibPx); private set

    private fun snapshot() = Persisted(data, cur, month, form.from, form.to, ov, light, sec, RATES.toMap(), ratesDate, ratesAt, hist, ibNav, ibFlows, ibBase, ibPx)

    // ---------- резервна копія ----------
    var backupMsg by mutableStateOf(""); private set
    /** Копія, прочитана з файлу й готова до відновлення (чекає підтвердження). */
    var pendingRestore by mutableStateOf<Backup?>(null); private set

    fun exportBackup(uri: Uri) {
        val text = makeBackup(snapshot(), java.time.LocalDateTime.now().withNano(0).toString())
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try { getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null } catch (_: Exception) { false }
            }
            backupMsg = if (ok) "Копію збережено: операцій ${data.txs.size}, рахунків ${data.accs.size}" else "Не вдалося записати файл копії"
        }
    }
    /** «Бюджет → Звіт»: 0 — по місяцях, 1 — календарний рік, 2 — останні 12 місяців; repYear — вибраний рік. */
    var repMode by mutableStateOf(0)
    var repYear by mutableStateOf(LocalDate.now().year)
    fun yearReportNow(): YearReport {
        val today = LocalDate.parse(TODAY)
        return yearReport(data.txs, hist, if (repMode == 2) last12Period(today) else yearPeriod(repYear, today))
    }
    fun exportYearXlsx(uri: Uri) {
        val r = yearReportNow()
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try { val bytes = buildYearXlsx(r); getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } != null } catch (_: Exception) { false }
            }
            backupMsg = if (ok) "Звіт експортовано в Excel" else "Не вдалося записати файл Excel"
        }
    }
    fun exportXlsx(uri: Uri) {
        val d = data
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try { val bytes = buildXlsx(d); getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } != null } catch (_: Exception) { false }
            }
            backupMsg = if (ok) "Експортовано в Excel: операцій ${d.txs.size}" else "Не вдалося записати файл Excel"
        }
    }
    fun readBackup(uri: Uri) {
        pendingRestore = null
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                try { getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } } catch (_: Exception) { null }
            }
            val b = text?.let { parseBackup(it) }
            if (b == null) backupMsg = "Це не копія Bella Finance або файл пошкоджено"
            else { pendingRestore = b; backupMsg = "" }
        }
    }
    fun cancelRestore() { pendingRestore = null }
    /** Замінює дані копією. Поточні дані лишаються в «Скасувати», PIN цього телефону не змінюється. */
    fun confirmRestore() {
        val b = pendingRestore ?: return
        val p = b.state
        p.rates.forEach { (c, v) -> if (c != "UAH" && v > 0 && RATES.containsKey(c)) RATES[c] = v }
        ratesDate = p.ratesDate; ratesAt = p.ratesAt; ratesTick++
        cur = p.cur; ov = p.ov; light = p.light; hist = p.hist; ibNav = p.ibNav; ibFlows = p.ibFlows; ibBase = p.ibBase; ibPx = p.ibPx
        month = p.month.coerceAtLeast(0)
        commit(p.data)
        pendingRestore = null
        backupMsg = "Дані відновлено з копії" + if (b.createdAt.isNotEmpty()) " від ${b.createdAt.replace('T', ' ')}" else ""
        persist()
    }

    /**
     * Збереження: знімок стану береться в головному потоці (це лише посилання на незмінні дані), а кодування в JSON
     * і запис — в одному фоновому потоці, по черзі. Якщо поки писався один стан, надійшли новіші, пишеться лише
     * найновіший. after виконується після запису (напр. перевірка лімітів читає вже збережений стан).
     */
    private fun persist(after: (() -> Unit)? = null) {
        val t = System.currentTimeMillis()
        try { hist = upsertHist(hist, TODAY, Totals(this).net) } catch (_: Exception) {}
        val snap = snapshot(); val seq = ++saveSeq
        saveScope.launch {
            if (seq == saveSeq) try {
                prefs.edit().putString("state", json.encodeToString(snap)).putLong("savedAt", t).commit()
                withContext(Dispatchers.Main) { savedAt = t }
            } catch (_: Exception) {}
            after?.invoke()
        }
    }
    @Volatile private var saveSeq = 0L
    /** Поза viewModelScope, щоб останній запис не обривався, коли екран закривається. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val saveScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    init {
        loaded.rates.forEach { (c, v) -> if (c != "UAH" && v > 0 && RATES.containsKey(c)) RATES[c] = v }
        if (System.currentTimeMillis() - loaded.ratesAt > 6 * 3600_000L) refreshRates()
        // щомісячні виплати депозитів, що настали (зокрема пропущені, поки застосунок не відкривали)
        processDepositPayouts(data, todayIso(), RATES).let { if (it !== data) { data = it; persist() } }
        processRecurring(data, TODAY, RATES).let { if (it !== data) { data = it; persist() } }
        // знімок капіталу за сьогодні, навіть якщо змін не було
        if (hist.lastOrNull()?.d != TODAY) persist()
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
        stack.addLast(data); labels.addLast(describeChange(data, nd)); if (stack.size > 20) { stack.removeFirst(); labels.removeFirst() }
        data = nd; undoN = stack.size; clearSel(); persist { checkMoneyAlerts() }
        val t = System.currentTimeMillis(); toastAt = t
        viewModelScope.launch { delay(6000); if (toastAt == t) toastAt = 0 }
    }
    fun undo() {
        if (stack.isEmpty()) return
        data = stack.removeLast(); labels.removeLastOrNull(); undoN = stack.size; toastAt = 0; clearSel(); persist()
    }

    /** Останні дії (найновіша перша), до 10: текст і скільки дій треба відмінити, щоб скасувати саме її разом з новішими. */
    fun recentActions(): List<Pair<Int, String>> = labels.toList().asReversed().take(10).mapIndexed { i, l -> (i + 1) to l }
    /** Відміняє k останніх дій одразу (повертає стан до k-ї дії). */
    fun undoLast(k: Int) {
        val (restored, rest) = undoSteps(stack.toList(), k) ?: return
        stack.clear(); stack.addAll(rest); repeat(k) { labels.removeLastOrNull() }
        data = restored; undoN = stack.size; toastAt = 0; clearSel(); persist()
    }

    @JvmName("selectCurrency") fun setCur(c: String) { cur = c; curOpen = false; persist() }
    @JvmName("selectMonth") fun setMonth(i: Int) { month = i.coerceIn(0, months.size - 1); persist() }
    fun toggleTheme() { light = !light; persist() }
    fun go(s: Screen) { screen = s; curOpen = false }
    fun openSheet(s: Sheet) { sheet = s }
    fun closeSheet() { sheet = Sheet.None; formOrig = null; formErr = ""; delAsk = false }

    /** Значення форми на момент відкриття редагування; null для нових записів. */
    var formOrig by mutableStateOf<Form?>(null); private set
    /** Старе значення поля для підказки (порожньо, якщо це не редагування). */
    fun was(sel: (Form) -> String): String = formOrig?.let(sel) ?: ""

    // ---------- відкриття форм ----------
    fun openTransfer() { form = form.copy(amount = ""); sheet = Sheet.Transfer }
    fun openAcc() { form = form.copy(name = "", bal = "", cur = "UAH", cashF = false); sheet = Sheet.Acc }
    /** Новий готівковий рахунок (з портфеля). */
    fun openCash() { form = form.copy(name = "Готівка USD", bal = "", cur = "USD", cashF = true); sheet = Sheet.Acc }
    fun editDebt(i: Int) {
        val d = data.debts.getOrNull(i) ?: return
        fun s2(v: Double) = (Math.round(v / rate * 100) / 100.0).let { if (it == Math.floor(it)) it.toLong().toString() else it.toString() }
        formErr = ""; form = form.copy(dIdx = i, who = d.who, amt = s2(d.amt), due = d.due, kindOwedToMe = d.owedToMe, dPay = ""); formOrig = form; sheet = Sheet.DebtE
    }
    fun deleteDebt() {
        if (!delAsk) { delAsk = true; return }; delAsk = false
        val i = form.dIdx; if (data.debts.getOrNull(i) == null) { closeSheet(); return }
        commit(data.copy(debts = data.debts.filterIndexed { j, _ -> j != i })); closeSheet()
    }
    fun openDebt() { form = form.copy(who = "", amt = "", due = "", kindOwedToMe = true); sheet = Sheet.Debt }
    var formErr by mutableStateOf("")
    var txDelAsk by mutableStateOf(false); private set

    fun openTx() {
        formErr = ""; txDelAsk = false
        form = form.copy(tAmt = "", tDesc = "", tType = "expense", tCat = "Харчування", tRepeat = false, tDate = todayIso(), tAcc = data.accs.firstOrNull()?.id ?: "", tToAcc = data.accs.getOrNull(1)?.id ?: "", tToAmt = "", txIdx = -1)
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
        formOrig = form; sheet = Sheet.TxE
    }
    fun deleteTx() {
        if (!txDelAsk) { txDelAsk = true; return }
        txDelAsk = false
        val i = form.txIdx; if (data.txs.getOrNull(i) == null) { closeSheet(); return }
        commit(ua.bella.finance.deleteTx(data, i)); closeSheet()
    }
    /** Зберігає цільовий розподіл (%, від'ємні відкидаються; 0 — групу прибрано з цілей). */
    fun setTargets(t: Map<String, Double>) { commit(data.copy(targets = t.filterValues { it > 0 }.mapValues { Math.min(100.0, it.value) })) }

    // ---------- Interactive Brokers (Flex Web Service, див. Ibkr.kt) ----------
    var ibkrOn by mutableStateOf(try { IbkrStore.configured(getApplication()) } catch (_: Exception) { false }); private set
    var ibkrAt by mutableStateOf(try { IbkrStore.lastAt(getApplication()) } catch (_: Exception) { 0L }); private set
    var ibkrBusy by mutableStateOf(false); private set
    var ibkrMsg by mutableStateOf(""); private set
    /** ETF з IBKR, яких більше немає в рахунку: чекають рішення «прибрати / залишити». */
    var ibkrGone by mutableStateOf(listOf<String>()); private set
    fun openIbkr() { form = form.copy(ibTok = "", ibQ = try { IbkrStore.query(ctx) } catch (_: Exception) { "" }); formErr = ""; sheet = Sheet.Ibkr }
    fun syncIbkr() {
        if (ibkrBusy || !ibkrOn) return
        ibkrBusy = true; ibkrMsg = ""
        viewModelScope.launch {
            try {
                val (f, err) = withContext(Dispatchers.IO) { try { fetchFlex(IbkrStore.token(ctx), IbkrStore.query(ctx)) } catch (e: Throwable) { null to "Помилка IBKR: ${e.javaClass.simpleName}: ${e.message?.take(120) ?: ""}" } }
                if (f == null) ibkrMsg = err
                else {
                    val (nd, sum) = applyFlex(data, f, RATES, System.currentTimeMillis())
                    val base = f.base.ifEmpty { ibBase }.takeIf { RATES.containsKey(it) } ?: "USD"
                    if (base != ibBase) { ibNav = emptyList(); ibFlows = emptyList() }
                    ibBase = base
                    mergeIbHist(ibNav, ibFlows, f, ibkrSnapshot(nd, RATES, base), TODAY).let { (n, fl) -> ibNav = n; ibFlows = fl }
                    ibPx = mergeIbPrices(ibPx, f)
                    commit(nd); ibkrGone = sum.gone
                    val t = System.currentTimeMillis(); IbkrStore.setLastAt(ctx, t); ibkrAt = t
                    ibkrMsg = (if (f.asOf.isNotEmpty()) "Дані IBKR на ${ddmmyyyy(f.asOf)}. " else "") + sum.text
                }
            } catch (e: Throwable) {
                ibkrMsg = "Помилка застосунку під час синхронізації: ${e.javaClass.simpleName}: ${e.message?.take(160) ?: ""}"
            } finally { ibkrBusy = false }
        }
    }
    private var ibkrTryAt = 0L
    /** Синхронізація IBKR при кожному відкритті чи поверненні до застосунку, але не частіше ніж раз на годину (і після невдалої спроби теж). */
    fun autoSyncIbkr() {
        val now = System.currentTimeMillis()
        if (!ibkrOn || !ibkrDue(ibkrAt, ibkrTryAt, now)) return
        ibkrTryAt = now; syncIbkr()
    }
    // ---------- графіки IBKR ----------
    var ibPeriod by mutableStateOf("1Y")
    fun openIbkrCharts() { sheet = Sheet.IbkrCharts }
    fun setIbkrPeriod(p: String) { ibPeriod = p }
    /** Лише для знімків екранів у тестах: підставляє історію рахунку IBKR без синхронізації. */
    @androidx.annotation.VisibleForTesting
    internal fun seedIbkrHistory(nav: List<HistPoint>, flows: List<IbFlow>, base: String = "USD", px: Map<String, List<HistPoint>> = emptyMap()) { ibNav = nav; ibFlows = flows; ibBase = base; ibPx = px }
    fun removeIbkrGone() { if (ibkrGone.isNotEmpty()) commit(removeGone(data, ibkrGone)); ibkrGone = emptyList() }
    fun keepIbkrGone() { ibkrGone = emptyList() }
    fun disconnectIbkr() { try { IbkrStore.clear(ctx) } catch (_: Exception) {}; ibkrOn = false; ibkrAt = 0L; ibNav = emptyList(); ibFlows = emptyList(); ibPx = emptyMap(); persist(); ibkrMsg = "IBKR відключено. Позиції лишились як є."; closeSheet() }

    // ---------- правила категорій ----------
    /** Пропозиція «Завжди ставити …» після ручної зміни категорії операції (null — немає). */
    var ruleAsk by mutableStateOf<CatRule?>(null); private set
    var rulesMsg by mutableStateOf(""); private set
    fun acceptRule() { val r = ruleAsk ?: return; ruleAsk = null; commit(data.copy(rules = addRule(data.rules, r))); rulesMsg = "Правило додано: «${r.match}» → ${r.cat}" }
    fun dismissRule() { ruleAsk = null }
    fun openRule(i: Int) {
        val r = data.rules.getOrNull(i); formErr = ""; delAsk = false
        form = form.copy(ruIdx = if (r != null) i else -1, ruMatch = r?.match ?: "", ruCat = r?.cat ?: data.cats.firstOrNull { it == "Харчування" } ?: data.cats.firstOrNull() ?: "Інше")
        formOrig = if (r != null) form else null; sheet = Sheet.Rule
    }
    fun deleteRule() {
        if (!delAsk) { delAsk = true; return }; delAsk = false
        val i = form.ruIdx; if (i in data.rules.indices) commit(data.copy(rules = data.rules.filterIndexed { j, _ -> j != i })); closeSheet()
    }
    /** Застосовує правила до вже наявних операцій (одна дія «Скасувати»). */
    fun applyRulesToOld() {
        val (txs, n) = applyRules(data.txs, data.rules)
        if (n == 0) { rulesMsg = "Усі операції вже відповідають правилам"; return }
        commit(data.copy(txs = txs)); rulesMsg = "Змінено категорію в операціях: $n"
    }

    // ---------- масові дії з операціями («Вибрати» на вкладці «Операції») ----------
    /** Режим вибору і вибрані операції (індекси в data.txs; скидаються після будь-якої зміни даних). */
    var selMode by mutableStateOf(false); private set
    var sel by mutableStateOf(setOf<Int>()); private set
    var bulkDelAsk by mutableStateOf(false); private set
    fun clearSel() { sel = emptySet(); bulkDelAsk = false }
    fun toggleSelMode() { selMode = !selMode; clearSel() }
    fun toggleSel(i: Int) { sel = if (i in sel) sel - i else sel + i; bulkDelAsk = false }
    /** Вибрати всі знайдені або, якщо вони вже всі вибрані, зняти вибір. */
    fun selectAll(idx: Collection<Int>) { sel = if (idx.isNotEmpty() && sel.containsAll(idx)) sel - idx.toSet() else sel + idx; bulkDelAsk = false }
    fun bulkCat(cat: String) { if (sel.isNotEmpty()) commit(bulkSetCat(data, sel, cat)) }
    fun bulkDel() {
        if (sel.isEmpty()) return
        if (!bulkDelAsk) { bulkDelAsk = true; return }
        commit(bulkDelete(data, sel))
    }

    /** Підпис рахунку для рядка операції: «Monobank» або «Monobank → Готівка USD». */
    fun accLabel(t: Tx): String {
        val a = data.accs.firstOrNull { it.id == t.acc }?.name ?: ""
        if (t.type == "transfer") { val b = data.accs.firstOrNull { it.id == t.toAcc }?.name ?: ""; return if (a.isEmpty() && b.isEmpty()) "" else "$a → $b" }
        return a
    }
    /** Будує позицію з форми. ETF у валюті: ціни у валюті фонду, курс купівлі — введений або поточний курс НБУ. */
    private fun posFromForm(f: Form, q: Double, monthly: Boolean, old: Pos?): Pos {
        val fx = f.group == "ETF" && f.pCur != "UAH" && RATES.containsKey(f.pCur)
        val ticker = old?.ticker ?: ""  // лише зі старих даних, для зіставлення з IBKR
        if (fx) {
            val rateNow = rateOf(f.pCur); val pxB = parseNum(f.pBuy); val pxN = parseNum(f.pNow).takeIf { it > 0 } ?: pxB
            val bfx = parseNum(f.pFx).takeIf { it > 0 } ?: rateNow
            return Pos(f.group, f.pName.trim(), q, pxB * bfx, pxN * rateNow, f.pMat.trim(), parseNum(f.pRate), f.pNext.trim(), old?.calc,
                cur = f.pCur, pxBuy = pxB, pxNow = pxN, buyFx = bfx, ticker = ticker, pxAt = if (old != null && old.pxNow == pxN) old.pxAt else 0L,
                src = old?.src ?: "", ibSym = old?.ibSym ?: "")
        }
        val b = parseNum(f.pBuy) * rate; val nw = (parseNum(f.pNow).takeIf { it != 0.0 } ?: parseNum(f.pBuy)) * rate
        return Pos(f.group, f.pName.trim(), q, b, if (old == null) b else nw, f.pMat.trim(), parseNum(f.pRate), f.pNext.trim(), old?.calc, monthly, if (monthly) f.pPayAcc else "", ticker = ticker,
            src = old?.src ?: "", ibSym = old?.ibSym ?: "")
    }

    /** Для депозиту кількість не вводиться: 1, якщо поле порожнє. */
    private fun posQty(f: Form): Double = parseNum(f.pQty).let { if (it <= 0 && f.group == "Депозити" && f.pQty.isBlank()) 1.0 else it }
    private fun txFromForm(f: Form, key: String): Tx? {
        val (t, err) = buildTx(TxInput(f.tType, parseNum(f.tAmt), if (f.tType == "transfer") "Перекази" else f.tCat, f.tDesc.trim(), f.tDate.trim(), f.tAcc, f.tToAcc, parseNum(f.tToAmt), key), data.accs, RATES, rate)
        formErr = err; return t
    }
    fun openPos() { formErr = ""; formOrig = null; form = form.copy(pName = "", pQty = "", pBuy = "", pRate = "", pMat = "", pNext = "", group = "ОВДП", pMonthly = false, pPayAcc = data.accs.firstOrNull()?.id ?: "", pCur = "UAH", pFx = ""); sheet = Sheet.Pos }
    fun editAcc(i: Int) { val a = data.accs[i]; form = form.copy(editIdx = i, sav = a.sav, cashF = a.cash, name = a.name, bal = (Math.round(a.bal * 100) / 100.0).toString()); formOrig = form; sheet = Sheet.AccE }
    fun editPos(i: Int) {
        val p = data.pos[i]; fun c(v: Double) = ((v / rate) * 100).let { Math.round(it) / 100.0 }.toString()
        fun r2(v: Double) = (Math.round(v * 10000) / 10000.0).toString()
        val fx = isFxPos(p)
        form = form.copy(editIdx = i, group = p.group, pName = p.name, pQty = p.qty.toString(),
            pBuy = if (fx) r2(p.pxBuy) else c(p.buy), pNow = if (fx) r2(p.pxNow) else c(p.now), pFx = if (fx) r2(p.buyFx) else "", pCur = if (fx) p.cur else "UAH",
            pRate = p.rate.toString(), pMat = p.mat, pNext = p.next, pMonthly = p.monthly, pPayAcc = p.payAcc); formOrig = form; formErr = ""; sheet = Sheet.PosE
    }
    /** Довідка з будь-якого екрана: відкривається на розділі, що відповідає поточному екрану. */
    fun openHelp(section: Int = helpSectionFor(screen)) { help = section; helpTop = section; sheet = Sheet.Help }
    fun openSettings() { eraseAsk = false; sheet = Sheet.Settings }
    fun openImport() { impResult = null; sheet = Sheet.Import }

    // ---------- сабміти ----------
    fun submit() {
        val o = formOrig; val edit = sheet == Sheet.TxE || sheet == Sheet.AccE || sheet == Sheet.PosE || sheet == Sheet.GoalE || sheet == Sheet.Rec || sheet == Sheet.DebtE
        val f = if (edit && o != null) fillBlanks(form, o) else form; val s = data
        when (sheet) {
            Sheet.Transfer -> {
                val fa = s.accs.getOrNull(f.from); val ta = s.accs.getOrNull(f.to); val amt = parseNum(f.amount)
                formErr = when { fa == null || ta == null -> "Оберіть обидва рахунки"; f.from == f.to -> "Оберіть різні рахунки"; amt <= 0 -> "Вкажіть суму більше нуля"; amt > fa.bal -> "Недостатньо коштів на рахунку"; else -> "" }
                if (formErr.isEmpty() && fa != null && ta != null) {
                    val conv = amt * rateOf(fa.cur) / rateOf(ta.cur)
                    val ids = ensureAccIds(s); val fid = ids.accs[f.from].id; val tid = ids.accs[f.to].id
                    commit(addTx(ids, Tx("Переказ: ${fa.name} → ${ta.name}", "Перекази", "transfer", amt * rateOf(fa.cur), todayIso(), "", fid, amt, tid, conv))); closeSheet()
                }
            }
            Sheet.Acc -> if (f.name.isBlank()) formErr = "Вкажіть назву рахунку" else { commit(s.copy(accs = s.accs + Account(f.name.trim(), f.cur, parseNum(f.bal), cash = f.cashF))); closeSheet() }
            Sheet.Debt -> { val v = parseNum(f.amt) * rate; formErr = when { f.who.isBlank() -> "Вкажіть особу або опис"; v <= 0 -> "Вкажіть суму більше нуля"; f.due.isNotBlank() && !isIsoDate(f.due.trim()) -> "Термін у форматі РРРР-ММ-ДД"; else -> "" }; if (formErr.isEmpty()) { commit(s.copy(debts = s.debts + Debt(f.who.trim(), f.kindOwedToMe, v, false, f.due.trim()))); closeSheet() } }
            Sheet.DebtE -> {
                val old = s.debts.getOrNull(f.dIdx) ?: run { closeSheet(); return }
                val amt = parseNum(f.amt) * rate; val pay = parseNum(f.dPay) * rate
                formErr = when { f.who.isBlank() -> "Вкажіть особу або опис"; amt <= 0 -> "Вкажіть суму більше нуля"; pay < 0 -> "Платіж не може бути від'ємним"
                    pay > amt + 1e-6 -> "Платіж більший за суму боргу"; f.due.isNotBlank() && !isIsoDate(f.due.trim()) -> "Термін у форматі РРРР-ММ-ДД"; else -> "" }
                if (formErr.isNotEmpty()) return
                // платіж зменшує залишок; повний платіж означає «повернено» — борг зникає
                val left = amt - pay
                val list = if (left <= 1e-6) s.debts.filterIndexed { j, _ -> j != f.dIdx }
                    else s.debts.mapIndexed { j, d -> if (j == f.dIdx) old.copy(who = f.who.trim(), owedToMe = f.kindOwedToMe, amt = left, due = f.due.trim()) else d }
                commit(s.copy(debts = list)); closeSheet()
            }
            Sheet.AccE -> if (f.name.isBlank()) formErr = "Вкажіть назву рахунку" else if (s.accs.getOrNull(f.editIdx) != null) {
                val old = s.accs[f.editIdx]
                ov = ov + (old.name.takeLast(4) to Override(f.name.trim(), old.cur, parseNum(f.bal), f.sav))
                commit(s.copy(accs = s.accs.mapIndexed { i, a -> if (i == f.editIdx) a.copy(name = f.name.trim(), bal = parseNum(f.bal), sav = f.sav, cash = f.cashF) else a })); closeSheet()
            }
            Sheet.Import -> importCommit()
            Sheet.PosE -> {
                val o = s.pos.getOrNull(f.editIdx) ?: run { closeSheet(); return }
                val q = posQty(f); val monthly = f.group == "Депозити" && f.pMonthly
                val np = posFromForm(f, q, monthly, o)
                formErr = posFormError(f.group, f.pName, q, np.buy, monthly, parseNum(f.pRate), f.pNext, f.pMat).ifEmpty { fxFormError(np, RATES) }; if (formErr.isNotEmpty()) return
                open = open + f.group
                commit(processDepositPayouts(s.copy(pos = s.pos.mapIndexed { i, x -> if (i == f.editIdx) np else x }), todayIso(), RATES)); closeSheet()
            }
            Sheet.Pos -> {
                val q = posQty(f); val monthly = f.group == "Депозити" && f.pMonthly
                val np = posFromForm(f, q, monthly, null)
                formErr = posFormError(f.group, f.pName, q, np.buy, monthly, parseNum(f.pRate), f.pNext, f.pMat).ifEmpty { fxFormError(np, RATES) }; if (formErr.isNotEmpty()) return
                open = open + f.group
                commit(processDepositPayouts(s.copy(pos = s.pos + np), todayIso(), RATES)); closeSheet()
            }
            Sheet.Tx -> {
                val t = txFromForm(f, "") ?: return
                var nd = addTx(s, t)
                if (f.tRepeat && t.type != "transfer") nd = nd.copy(recurring = nd.recurring + recurringFrom(t, t.date.substring(8, 10).toInt()))
                commit(nd); closeSheet()
                setMonth(months.indexOf(t.date.take(7)).coerceAtLeast(0))
            }
            Sheet.Rec -> {
                val r = s.recurring.getOrNull(f.rIdx) ?: run { closeSheet(); return }
                val amt = parseNum(f.tAmt); val day = f.rDay.trim().toIntOrNull()?.takeIf { it in 1..31 }
                formErr = when { amt <= 0 -> "Вкажіть суму більше нуля"; day == null -> "Число місяця від 1 до 31"; else -> "" }
                if (formErr.isNotEmpty() || day == null) return
                // сума у валюті рахунку, якщо він є й існує; інакше у валюті перегляду
                val acc = s.accs.firstOrNull { it.id == r.acc }
                val uah = amt * (if (acc != null) RATES[acc.cur] ?: 1.0 else rate)
                val nr = r.copy(desc = f.tDesc.trim().ifEmpty { r.cat }, amt = uah, accAmt = if (acc != null) amt else 0.0, day = day, active = f.rActive,
                    next = if (day != r.day) clampDay(java.time.YearMonth.from(LocalDate.parse(r.next)), day).let { d -> if (d.isBefore(LocalDate.parse(TODAY))) nextMonthly(d, day) else d }.toString() else r.next)
                commit(s.copy(recurring = s.recurring.mapIndexed { i, x -> if (i == f.rIdx) nr else x })); closeSheet()
            }
            Sheet.Goal -> { val g = goalFromForm(f) ?: return; commit(s.copy(goals = s.goals + g)); closeSheet() }
            Sheet.GoalE -> {
                if (s.goals.getOrNull(f.gIdx) == null) { closeSheet(); return }
                val g = goalFromForm(f) ?: return
                commit(s.copy(goals = s.goals.mapIndexed { i, x -> if (i == f.gIdx) g else x })); closeSheet()
            }
            Sheet.GoalMove -> {
                val acc = s.accs.firstOrNull { it.id == f.gAcc }
                val amt = parseNum(f.gAmt) * (if (acc != null) RATES[acc.cur] ?: 1.0 else rate)
                if (amt <= 0) { formErr = "Вкажіть суму більше нуля"; return }
                val (nd, e) = moveGoal(s, f.gIdx, if (f.gIn) amt else -amt, f.gAcc, RATES); formErr = e
                if (nd != null) { commit(nd); closeSheet() }
            }
            Sheet.Cats -> {
                val (lim, e) = buildLimits(catsEdit, limEdit, rate); formErr = e
                if (lim != null) { commit(s.copy(cats = catsEdit, limits = lim)); closeSheet() }
            }
            Sheet.Ibkr -> {
                val t = f.ibTok.trim(); val q = f.ibQ.trim()
                if (q.isEmpty() || (t.isEmpty() && !ibkrOn)) { formErr = "Вкажіть токен і номер Flex-запиту (Query ID)"; return }
                flexQueryError(q).let { if (it.isNotEmpty()) { formErr = it; return } }
                try { IbkrStore.save(ctx, t.ifEmpty { IbkrStore.token(ctx) }, q) } catch (e: Throwable) { formErr = "Не вдалося зберегти токен на пристрої: ${e.javaClass.simpleName}"; return }
                form = form.copy(ibTok = "", ibQ = ""); formErr = ""; ibkrOn = true; syncIbkr()  // вікно лишається відкритим, поки триває синхронізація і видно результат
            }
            Sheet.Rule -> {
                val m = f.ruMatch.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
                if (m.isEmpty()) { formErr = "Вкажіть текст, який шукати в описі (можна кілька через кому)"; return }
                val r = CatRule(m, f.ruCat)
                val rules = if (f.ruIdx in s.rules.indices) s.rules.mapIndexed { i, x -> if (i == f.ruIdx) r else x } else addRule(s.rules, r)
                commit(s.copy(rules = rules)); closeSheet()
            }
            Sheet.TxE -> {
                val old = s.txs.getOrNull(f.txIdx) ?: run { closeSheet(); return }
                val t = txFromForm(f, old.key) ?: return
                commit(replaceTx(s, f.txIdx, t)); closeSheet()
                ruleAsk = suggestRule(old, t, data.rules)
            }
            else -> {}
        }
    }
    // ---------- калькулятори (складний відсоток, фінансова незалежність) ----------
    // Значення, введені вручну, зберігаються між запусками; решта підставляється автоматично.
    private fun loadMap(k: String): Map<String, String> = try { prefs.getString(k, null)?.let { json.decodeFromString<Map<String, String>>(it) } ?: emptyMap() } catch (_: Exception) { emptyMap() }
    var compIn by mutableStateOf(loadMap("compIn")); private set
    var fireIn by mutableStateOf(loadMap("fireIn")); private set
    fun setComp(k: String, v: String) { compIn = compIn + (k to v); prefs.edit().putString("compIn", json.encodeToString(compIn)).apply() }
    fun setFire(k: String, v: String) { fireIn = fireIn + (k to v); prefs.edit().putString("fireIn", json.encodeToString(fireIn)).apply() }
    /** Як у вебверсії: капітал, дохідність і ціль знову рахуються автоматично; внесок, інфляція й ставка знімання лишаються. */
    fun resetFire() { fireIn = fireIn - setOf("cap", "yield", "target"); prefs.edit().putString("fireIn", json.encodeToString(fireIn)).apply() }
    /** Середні витрати на місяць за історією операцій (₴). */
    fun avgMonthlyExpenseUah(): Double = data.txs.filter { it.type == "expense" }.groupBy { it.date.take(7) }.values.map { l -> l.sumOf { it.amt } }.let { if (it.isEmpty()) 0.0 else it.average() }

    // ---------- цілі ----------
    private fun v2(uah: Double) = (Math.round(uah / rate * 100) / 100.0).let { if (it == Math.floor(it)) it.toLong().toString() else it.toString() }
    fun openGoal() { formErr = ""; formOrig = null; form = form.copy(gName = "", gTarget = "", gSaved = "", gStep = "", gDeadline = "", gIcon = "flag", gIdx = -1, gMonthly = ""); sheet = Sheet.Goal }
    fun editGoal(i: Int) {
        val g = data.goals.getOrNull(i) ?: return
        formErr = ""; form = form.copy(gName = g.name, gTarget = v2(g.target), gSaved = v2(g.saved), gStep = v2(g.step), gDeadline = g.deadline, gIcon = g.icon, gIdx = i, gMonthly = if (g.monthly > 0) v2(g.monthly) else ""); formOrig = form; sheet = Sheet.GoalE
    }
    fun openGoalMove(i: Int) { if (data.goals.getOrNull(i) == null) return; formErr = ""; formOrig = null; form = form.copy(gIdx = i, gIn = true, gAcc = data.accs.firstOrNull { !it.sav }?.id ?: "", gAmt = ""); sheet = Sheet.GoalMove }
    // ---------- повторювані операції ----------
    fun openRec(i: Int) {
        val r = data.recurring.getOrNull(i) ?: return
        fun s2(v: Double) = (Math.round(v / rate * 100) / 100.0).let { if (it == Math.floor(it)) it.toLong().toString() else it.toString() }
        formErr = ""; form = form.copy(rIdx = i, tDesc = r.desc, tAmt = s2(r.amt), rDay = r.day.toString(), rActive = r.active, tCat = r.cat); formOrig = form; sheet = Sheet.Rec
    }
    fun deleteRec() {
        if (!delAsk) { delAsk = true; return }; delAsk = false
        val i = form.rIdx; if (data.recurring.getOrNull(i) == null) { closeSheet(); return }
        commit(data.copy(recurring = data.recurring.filterIndexed { j, _ -> j != i })); closeSheet()
    }
    /** Створює настали повторювані операції; викликається під час запуску й повернення в застосунок. */
    fun runRecurring() { processRecurring(data, TODAY, RATES).let { if (it !== data) commit(it) } }
    fun deleteGoal() {
        if (!delAsk) { delAsk = true; return }; delAsk = false
        val i = form.gIdx; if (data.goals.getOrNull(i) == null) { closeSheet(); return }
        commit(data.copy(goals = data.goals.filterIndexed { j, _ -> j != i })); closeSheet()
    }
    private fun goalFromForm(f: Form): Goal? {
        val target = parseNum(f.gTarget); val saved = parseNum(f.gSaved); val step = if (f.gStep.isBlank()) 1000.0 else parseNum(f.gStep)
        formErr = goalFormError(f.gName, target, saved, step, f.gDeadline); if (formErr.isNotEmpty()) return null
        val monthly = parseNum(f.gMonthly); if (monthly < 0) { formErr = "Внесок на місяць не може бути від'ємним"; return null }
        return Goal(f.gName.trim(), saved * rate, target * rate, f.gIcon, step * rate, f.gDeadline.trim(), monthly * rate)
    }

    // ---------- ліміти й категорії ----------
    var catsEdit by mutableStateOf(listOf<String>()); private set
    var limEdit by mutableStateOf(mapOf<String, String>()); private set
    var newCat by mutableStateOf("")
    fun openCats() {
        fun s2(v: Double) = (Math.round(v / rate * 100) / 100.0).let { if (it == Math.floor(it)) it.toLong().toString() else it.toString() }
        catsEdit = data.cats; limEdit = data.limits.mapValues { s2(it.value) }; newCat = ""; formErr = ""; sheet = Sheet.Cats
    }
    fun setLim(c: String, v: String) { limEdit = limEdit + (c to v) }
    fun addCat() { val (l, e) = addCategory(catsEdit, newCat); formErr = e; if (l != null) { catsEdit = l; newCat = "" } }
    fun removeCat(c: String) { catsEdit = removeCategory(catsEdit, c); limEdit = limEdit - c }

    /** Видалення рахунку чи позиції: перший дотик просить підтвердження, другий видаляє. */
    var delAsk by mutableStateOf(false); private set
    /** Скільки операцій прив'язано до рахунку, що редагується. */
    fun linkedTxCount(): Int = data.accs.getOrNull(form.editIdx)?.id?.let { id -> data.txs.count { it.acc == id || it.toAcc == id } } ?: 0
    fun deleteAcc() { if (!delAsk) { delAsk = true; return }; delAsk = false; val s = data; commit(s.copy(accs = s.accs.filterIndexed { i, _ -> i != form.editIdx })); form = form.copy(from = 0, to = 0); closeSheet() }
    fun deletePos() { if (!delAsk) { delAsk = true; return }; delAsk = false; val s = data; commit(s.copy(pos = s.pos.filterIndexed { i, _ -> i != form.editIdx })); closeSheet() }
    /** Галочка «повернено»: борг зникає зі списку (повернути його можна кнопкою «Скасувати» чи в «Відмінити останні дії»). */
    fun toggleDebt(i: Int) { commit(data.copy(debts = settleDebt(data.debts, i))) }
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
        val rules = data.rules
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                try {
                    val ins = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?: return@withContext ImportResult(emptyList(), emptyList(), 0, "Не вдалося відкрити файл «$name» (немає доступу)")
                    val bytes = ins.use { it.readBytes() }
                    if (bytes.isEmpty()) ImportResult(emptyList(), emptyList(), 0, "Файл порожній")
                    else Importer.parseBytes(bytes, rate, rules)
                } catch (e: Throwable) { ImportResult(emptyList(), emptyList(), 0, "Не вдалося прочитати файл (${e.javaClass.simpleName}: ${e.message?.take(80) ?: "—"})") }
            }
            impResult = r; impBusy = false
        }
    }
    fun importSample() { impResult = Importer.parseCsv("01.11.2026;Сільпо;-1250,40\n01.11.2026;Uber;-310\n02.11.2026;Аптека Доба;-480\n02.11.2026;Зарплата;45000\n02.11.2026;Spotify;-169", rate, data.rules) }
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
        if (impAcc && r.accs.isNotEmpty()) { nd = nd.copy(accs = if (rep) applyOv(r.accs) else mergeImportedAccs(s.accs, applyOv(r.accs))); form = form.copy(from = 0, to = Math.min(1, r.accs.size - 1)) }
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
    fun onStarted() { runRecurring(); refreshNotifyState(); autoSyncIbkr(); if (sec.hash.isNotEmpty() && hiddenAt > 0 && kmode == KMode.None && System.currentTimeMillis() - hiddenAt >= sec.autoMin * 60000L) { kmode = KMode.Unlock; entry = ""; kerr = ""; sheet = Sheet.None } }
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
                fails = 0; until = 0L; saveLock()
                if (m == KMode.Unlock) { kmode = KMode.None; entry = ""; kerr = "" }
                else if (kact == "disable") { saveSec(Security("", false, sec.autoMin)); kmode = KMode.None; entry = ""; kerr = "" }
                else { kmode = KMode.New; entry = ""; kerr = "" }
            } else {
                fails++; entry = ""
                if (fails >= 5) { kerr = "Забагато спроб. Зачекайте 30 с"; until = now + 30000; fails = 0 } else kerr = "Невірний PIN. Спроба $fails з 5"
                saveLock()
            }
            KMode.New -> { tmp = v; kmode = KMode.Confirm; entry = ""; kerr = "" }
            KMode.Confirm -> if (v == tmp) { saveSec(sec.copy(hash = h)); kmode = KMode.None; entry = ""; kerr = ""; tmp = "" } else { kmode = KMode.New; entry = ""; tmp = ""; kerr = "PIN не збігається. Створіть заново" }
            else -> {}
        }
    }

    // ---------- автокопія й нагадування (див. Jobs.kt) ----------
    private val ctx get() = getApplication<Application>()
    var autoDirName by mutableStateOf(dirLabel(Jobs.autoDir(ctx))); private set
    var autoAt by mutableStateOf(Jobs.autoAt(ctx)); private set
    var autoMsg by mutableStateOf(""); private set
    var remindMsg by mutableStateOf(""); private set
    var remindOn by mutableStateOf(Jobs.remindOn(ctx) && Jobs.canNotify(ctx)); private set
    private fun dirLabel(u: Uri?): String = u?.let { Uri.decode(it.lastPathSegment ?: "").substringAfter(':').ifEmpty { "Внутрішня пам'ять" } } ?: ""

    fun chooseAutoDir(uri: Uri) {
        try { Jobs.setAutoDir(ctx, uri) } catch (_: Exception) { autoMsg = "Не вдалося отримати доступ до папки"; return }
        autoDirName = dirLabel(uri); backupNow()
    }
    fun disableAutoBackup() { Jobs.setAutoDir(ctx, null); autoDirName = ""; autoAt = 0L; autoMsg = "Автокопію вимкнено" }
    fun backupNow(force: Boolean = true) {
        viewModelScope.launch {
            val m = withContext(Dispatchers.IO) { try { Jobs.runAutoBackup(ctx, force) } catch (_: Exception) { "Не вдалося зберегти автокопію" } }
            if (m != null) autoMsg = m
            autoAt = Jobs.autoAt(ctx)
        }
    }
    /** Вмикає/вимикає нагадування; granted — чи є дозвіл на сповіщення (Android 13+). */
    fun setRemind(on: Boolean, granted: Boolean = Jobs.canNotify(ctx)) {
        Jobs.setRemind(ctx, on); remindOn = on && granted
        remindMsg = if (on && !granted) "Сповіщення заборонені. Дозвольте їх у налаштуваннях Android: Програми → Bella Finance → Сповіщення" else ""
        if (on && granted) viewModelScope.launch(Dispatchers.IO) { try { Jobs.runReminders(ctx) } catch (_: Exception) {} }
    }
    fun refreshNotifyState() { remindOn = Jobs.remindOn(ctx) && Jobs.canNotify(ctx); moneyOn = Jobs.moneyOn(ctx) && Jobs.canNotify(ctx) }

    /** Сповіщення про ліміти (80% / 100%) і терміни боргів. */
    var moneyOn by mutableStateOf(Jobs.moneyOn(ctx) && Jobs.canNotify(ctx)); private set
    fun setMoney(on: Boolean, granted: Boolean = Jobs.canNotify(ctx)) {
        Jobs.setMoney(ctx, on); moneyOn = on && granted
        remindMsg = if (on && !granted) "Сповіщення заборонені. Дозвольте їх у налаштуваннях Android: Програми → Bella Finance → Сповіщення" else ""
        if (on && granted) checkMoneyAlerts()
    }
    /** Перевіряє ліміти й борги у фоні (після кожної зміни даних, щоб сповіщення про ліміт прийшло одразу). */
    private fun checkMoneyAlerts() { viewModelScope.launch(Dispatchers.IO) { try { Jobs.runMoneyAlerts(ctx) } catch (_: Exception) {} } }
    /** Виплати ОВДП на найближчі 60 днів (для екрана «Інвестиції»). */
    fun upcomingOvdp(): List<OvdpEvent> = LocalDate.parse(TODAY).let { ovdpEvents(data.pos, it, it.plusDays(60)) }

    // Наприкінці класу: на цей момент усі поля вже ініціалізовані.
    init {
        try { Jobs.schedule(ctx) } catch (_: Exception) {}
        viewModelScope.launch {
            val m = withContext(Dispatchers.IO) { try { Jobs.runAutoBackup(ctx) } catch (_: Exception) { null } }
            if (m != null) autoMsg = m; autoAt = Jobs.autoAt(ctx)
            withContext(Dispatchers.IO) { try { Jobs.runReminders(ctx) } catch (_: Exception) {} }
        }
    }
}
