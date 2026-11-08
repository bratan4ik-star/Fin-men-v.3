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


class BellaViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("bella", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val kv = PrefsKV(prefs)
    private fun validState(t: String) = try { json.decodeFromString<Persisted>(t); true } catch (_: Exception) { false }
    /** Стан зашифрований ключем з AndroidKeyStore (StateStore.kt); відкритий «state» зі старих версій мігрує при першому запуску. */
    private val stateLoad: StateLoad = try { StateStore.load(kv, KeystoreKeys.stateCipher, ::validState) } catch (e: Exception) { StateLoad.Failed("Сховище даних недоступне: ${e.javaClass.simpleName}") }
    private val loaded: Persisted = (stateLoad as? StateLoad.Ok)?.let { try { json.decodeFromString<Persisted>(it.json) } catch (_: Exception) { null } } ?: Persisted()
    /**
     * Не null — збережені дані є, але прочитати їх не вдалося (ключ у Keystore втрачено або дані пошкоджено).
     * Тоді показується екран відновлення і нічого не записується, щоб не затерти дані порожнім станом.
     */
    var storeBroken by mutableStateOf((stateLoad as? StateLoad.Failed)?.reason); private set

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

    /** Податок на дохід ОВДП для «Порівняти ОВДП», % — зберігається (на випадок змін у законі). Оголошено до init: його читає snapshot(). */
    var ovdpTax by mutableStateOf(loaded.ovdpTax); private set

    // довідник ОВДП НБУ (OvdpRegistry.kt)
    var nbu by mutableStateOf(loaded.nbu); private set
    var nbuBusy by mutableStateOf(false); private set
    var nbuMsg by mutableStateOf(""); private set
    private var nbuIdxOf: OvdpRegistry? = null
    private var nbuIdx: Map<String, NbuBond> = emptyMap()
    /** Довідник за ISIN (для графіка виплат і звірки); перебудовується лише після оновлення довідника. */
    val nbuIndex: Map<String, NbuBond> get() = nbu.let { r -> if (r !== nbuIdxOf) { nbuIdx = r.byIsin(); nbuIdxOf = r }; nbuIdx }

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
    var calcTab by mutableStateOf(0)
    var impResult by mutableStateOf<ImportResult?>(null); private set
    var impBusy by mutableStateOf(false); private set
    var impAcc by mutableStateOf(true)
    var impItems by mutableStateOf<List<ImportItem>>(emptyList()); private set
    var impSkipDuplicates by mutableStateOf(true)

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
    // лічильник помилок PIN і час блокування зберігаються в SharedPreferences, тож перезапуск блокування не знімає
    private var lock = PinLockStore.load(kv)
    /** PBKDF2 рахується у фоні: поки триває перевірка, натискання ігноруються. */
    var pinBusy by mutableStateOf(false); private set
    private fun lockMsg(): String = pinLockLeft(lock, System.currentTimeMillis()).let { if (it > 0) pinLockText(it) else "" }
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

    /** Interactive Brokers: синхронізація й історія для графіків (IbkrController.kt). */
    val ibkr = IbkrController(app, viewModelScope, loaded, { data }, { commit(it) }, { persist() })

    private fun snapshot() = Persisted(data, cur, month, form.from, form.to, ov, light, sec, RATES.toMap(), ratesDate, ratesAt, hist, ibkr.nav, ibkr.flows, ibkr.base, ibkr.px, nbu, ovdpTax)

    // ---------- резервна копія ----------
    var backupMsg by mutableStateOf(""); private set
    /** Копія, прочитана з файлу й готова до відновлення (чекає підтвердження). */
    var pendingRestore by mutableStateOf<Backup?>(null); private set

    /** Копія, зашифрована паролем: чекає введення пароля. */
    var pendingEnc by mutableStateOf<EncryptedBackup?>(null); private set
    var backupBusy by mutableStateOf(false); private set
    /** Копії (ручна й автокопія) шифруються паролем, збереженим зашифровано на цьому телефоні. */
    var backupPwOn by mutableStateOf(BackupPassword.isSet(app)); private set

    fun exportBackup(uri: Uri) {
        val snap = snapshot(); val at = java.time.LocalDateTime.now().withNano(0).toString()
        val pw = if (backupPwOn) BackupPassword.get(getApplication()) ?: run { backupMsg = "Не вдалося прочитати пароль копії. Задайте його ще раз."; return } else null
        backupBusy = true
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try { val text = makeBackup(snap, at, pw); getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null } catch (_: Exception) { false }
            }
            backupBusy = false
            backupMsg = if (ok) "Копію збережено" + (if (pw != null) " (зашифровано паролем)" else "") + ": операцій ${snap.data.txs.size}, рахунків ${snap.data.accs.size}" else "Не вдалося записати файл копії"
        }
    }
    /** Пароль копій: не коротший за 6 символів, двічі однаковий. */
    fun setBackupPassword(p1: String, p2: String): Boolean {
        if (p1.length < 6) { backupMsg = "Пароль копії — щонайменше 6 символів"; return false }
        if (p1 != p2) { backupMsg = "Паролі не збігаються"; return false }
        if (!BackupPassword.set(getApplication(), p1)) { backupMsg = "Не вдалося зберегти пароль на пристрої"; return false }
        backupPwOn = true; backupMsg = "Копії шифруватимуться паролем. Запам'ятайте його: без пароля копію не відкрити."; return true
    }
    fun clearBackupPassword() { BackupPassword.clear(getApplication()); backupPwOn = false; backupMsg = "Копії більше не шифруються паролем" }
    /** Розпізнає файл копії: відкритий — одразу до підтвердження, зашифрований — спершу пароль. */
    fun readBackupText(text: String?) {
        pendingRestore = null; pendingEnc = null
        when (val f = text?.let { readBackupFile(it) } ?: BackupFile.Invalid) {
            is BackupFile.Plain -> { pendingRestore = f.backup; backupMsg = "" }
            is BackupFile.Encrypted -> { pendingEnc = f.env; backupMsg = "" }
            BackupFile.Invalid -> backupMsg = "Це не копія Bella Finance або файл пошкоджено"
        }
    }
    fun unlockBackup(password: String) {
        val env = pendingEnc ?: return
        if (password.isEmpty()) { backupMsg = "Введіть пароль копії"; return }
        backupBusy = true
        viewModelScope.launch {
            val b = withContext(Dispatchers.Default) { decryptBackup(env, password) }
            backupBusy = false
            if (b == null) backupMsg = "Невірний пароль або файл пошкоджено"
            else { pendingEnc = null; pendingRestore = b; backupMsg = "" }
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
            readBackupText(text)
        }
    }
    fun cancelRestore() { pendingRestore = null; pendingEnc = null }
    /** Екран відновлення: найновіша автокопія з вибраної папки. */
    fun restoreFromAuto() {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { Jobs.latestAutoBackupText(getApplication()) }
            if (text == null) backupMsg = "Автокопію не знайдено. Виберіть файл копії вручну." else readBackupText(text)
        }
    }
    /** Екран відновлення: ще раз спробувати прочитати збережені дані (напр. після оновлення системи). */
    fun retryLoad() {
        val r = try { StateStore.load(kv, KeystoreKeys.stateCipher, ::validState) } catch (e: Exception) { StateLoad.Failed(e.javaClass.simpleName) }
        val p = (r as? StateLoad.Ok)?.let { try { json.decodeFromString<Persisted>(it.json) } catch (_: Exception) { null } }
        if (p == null) { backupMsg = "Дані все ще не читаються"; return }
        applyPersisted(p, withSec = true); storeBroken = null; backupMsg = ""
    }
    var freshAsk by mutableStateOf(false); private set
    /** Екран відновлення: почати з порожніми даними (з підтвердженням). Нерозшифрований запис не видаляється, а відкладається. */
    fun startFresh() {
        if (!freshAsk) { freshAsk = true; return }
        freshAsk = false
        prepareStoreAfterFailure()
        storeBroken = null
        data = AppData(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), demo = false)
        persist()
    }
    /**
     * Перед першим записом після збою: нерозшифрований запис відкладається (не перезаписується), а ключ, що не працює
     * для шифрування, замінюється новим — інакше відновлені дані не збереглися б.
     */
    private fun prepareStoreAfterFailure() {
        StateStore.setAsideBroken(kv)
        val works = try { KeystoreKeys.stateCipher.let { c -> c.decrypt(c.encrypt("check")) == "check" } } catch (_: Exception) { false }
        if (!works) KeystoreKeys.delete(KeystoreKeys.STATE)
    }
    private fun applyPersisted(p: Persisted, withSec: Boolean) {
        p.rates.forEach { (c, v) -> if (c != "UAH" && v > 0 && RATES.containsKey(c)) RATES[c] = v }
        ratesDate = p.ratesDate; ratesAt = p.ratesAt; ratesTick++
        cur = p.cur; ov = p.ov; light = p.light; hist = p.hist; ibkr.restore(p)
        month = p.month.coerceAtLeast(0)
        if (p.nbu.updatedAt > nbu.updatedAt) nbu = p.nbu
        ovdpTax = p.ovdpTax
        if (withSec) sec = p.sec
        data = ensureAccIds(p.data)
    }
    /** Замінює дані копією. Поточні дані лишаються в «Скасувати», PIN цього телефону не змінюється. */
    fun confirmRestore() {
        val b = pendingRestore ?: return
        val p = b.state
        p.rates.forEach { (c, v) -> if (c != "UAH" && v > 0 && RATES.containsKey(c)) RATES[c] = v }
        ratesDate = p.ratesDate; ratesAt = p.ratesAt; ratesTick++
        cur = p.cur; ov = p.ov; light = p.light; hist = p.hist; ibkr.restore(p)
        month = p.month.coerceAtLeast(0)
        // довідник НБУ — кеш, а не дані користувача: лишається новіший з двох
        if (p.nbu.updatedAt > nbu.updatedAt) nbu = p.nbu
        if (storeBroken != null) prepareStoreAfterFailure()
        storeBroken = null  // відновлення з копії знімає екран відновлення
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
        if (storeBroken != null) return  // дані не прочиталися: нічого не записуємо, доки користувач не відновить їх
        val t = System.currentTimeMillis()
        try { hist = upsertHist(hist, TODAY, Totals(this).net) } catch (_: Exception) {}
        val snap = snapshot(); val seq = ++saveSeq
        saveScope.launch {
            if (seq == saveSeq) try {
                // лише зашифровано; якщо Keystore недоступний — попередній запис лишається, відкритий текст не пишеться
                if (StateStore.save(kv, KeystoreKeys.stateCipher, json.encodeToString(snap))) {
                    prefs.edit().putLong("savedAt", t).commit()
                    withContext(Dispatchers.Main) { savedAt = t }
                }
            } catch (_: Exception) {}
            after?.invoke()
        }
    }
    @Volatile private var saveSeq = 0L
    /** Поза viewModelScope, щоб останній запис не обривався, коли екран закривається. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val saveScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    init {
        if (kmode == KMode.Unlock) kerr = lockMsg()
        loaded.rates.forEach { (c, v) -> if (c != "UAH" && v > 0 && RATES.containsKey(c)) RATES[c] = v }
        if (System.currentTimeMillis() - loaded.ratesAt > 6 * 3600_000L) refreshRates()
        // довідник ОВДП НБУ — раз на 7 днів (у тестах Robolectric мережа не чіпається)
        if (storeBroken == null && android.os.Build.FINGERPRINT != "robolectric" && registryDue(nbu, System.currentTimeMillis())) refreshNbu(auto = true)
        // щомісячні виплати депозитів, що настали (зокрема пропущені, поки застосунок не відкривали)
        processDepositPayouts(data, todayIso(), RATES).let { if (it !== data) { data = it; persist() } }
        // купони й погашення ОВДП з автозарахуванням, що настали (зокрема поки застосунок не відкривали)
        processOvdpPayouts(data, todayIso(), RATES, nbuIndex).let { if (it !== data) { data = it; persist() } }
        processRecurring(data, TODAY, RATES).let { if (it !== data) { data = it; persist() } }
        // знімок капіталу за сьогодні, навіть якщо змін не було
        if (hist.lastOrNull()?.d != TODAY) persist()
    }

    /**
     * Оновлює довідник ОВДП НБУ (один запит за всім довідником + результати аукціонів). Без мережі лишає кеш.
     * auto — автоматичне оновлення при запуску (раз на 7 днів): повідомлення про помилку не показується.
     */
    fun refreshNbu(auto: Boolean = false) {
        if (nbuBusy) return
        nbuBusy = true; if (!auto) nbuMsg = ""
        val old = nbu; val today = LocalDate.parse(TODAY)
        viewModelScope.launch {
            // етап 1: довідник одним запитом — список для вибору з'являється одразу
            val step = withContext(Dispatchers.IO) {
                try { refreshDirectory(old, today, System.currentTimeMillis()) { httpGetText(it) } } catch (e: Exception) { DirectoryStep(null, "Не вдалося оновити довідник НБУ: ${e.javaClass.simpleName}") }
            }
            val r1 = step.reg
            // поки йшов запит, довідник могли замінити (відновлення з копії) — тоді новіший не перезаписуємо
            if (r1 == null || nbu !== old) { nbuBusy = false; if (!auto) nbuMsg = step.msg; return@launch }
            nbu = r1; nbuMsg = step.msg + " Завантажуються результати аукціонів…"
            // з'явився офіційний графік — купони з автозарахуванням рахуються за ним
            processOvdpPayouts(data, todayIso(), RATES, nbuIndex).let { if (it !== data) data = it }
            persist()
            // етап 2: аукціони за вівторки, що ще не переглядались (вперше — за рік)
            val (r2, err) = withContext(Dispatchers.IO) { try { scanAuctions(r1, today) { httpGetText(it) } } catch (_: Exception) { r1 to true } }
            nbuBusy = false
            nbuMsg = step.msg + if (err) AUCTION_PARTIAL else ""
            if (nbu === r1 && r2 != r1) { nbu = r2; persist() }
        }
    }
    /** Для тестів і знімків екрана: підставити довідник без мережі. */
    internal fun seedNbu(r: OvdpRegistry) { nbu = r; nbuMsg = "" }

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
    fun closeSheet() {
        if (sheet == Sheet.OvdpPick) { sheet = pickBack; return }  // з вибору випуску — назад до форми позиції
        sheet = Sheet.None; formOrig = null; formErr = ""; delAsk = false
    }

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

    // ---------- Interactive Brokers (логіка й стан — IbkrController.kt) ----------
    fun openIbkr() { form = form.copy(ibTok = "", ibQ = try { IbkrStore.query(ctx) } catch (_: Exception) { "" }); formErr = ""; sheet = Sheet.Ibkr }
    fun openIbkrCharts() { sheet = Sheet.IbkrCharts }
    fun disconnectIbkr() { ibkr.disconnect(); closeSheet() }

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
        if (t.type == "transfer") { val b = data.accs.firstOrNull { it.id == t.toAcc }?.name ?: ""
            // погашення ОВДП: номінал надходить з інвестицій, рахунку списання немає
            return if (a.isEmpty() && b.isEmpty()) "" else if (a.isEmpty()) "Інвестиції → $b" else "$a → $b" }
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
        // ОВДП, вибрана з довідника НБУ: валюта, ISIN, номінал і періодичність — з довідника
        val nb = if (f.group == "ОВДП") f.pBond else null
        // валютна ОВДП (з калькулятора або довідника): ціни лишаються у валюті облігації, без перерахунку за курсом перегляду
        val bondCur = when { nb != null -> nb.cur; f.group != "ETF" && old != null && isBondCurPos(old) -> old.cur; else -> "UAH" }
        val k = if (bondCur != "UAH") 1.0 else rate
        val b = parseNum(f.pBuy) * k; val nw = (parseNum(f.pNow).takeIf { it != 0.0 } ?: parseNum(f.pBuy)) * k
        val calc = if (nb == null) old?.calc else (old?.calc ?: CalcExtra(nb.isin, nb.nom, bondFreqMonths(nb), "", "", todayIso(), nb.cur))
            .copy(isin = nb.isin, nom = nb.nom, freq = bondFreqMonths(nb), cur = nb.cur)
        // ОВДП з купонами в дохід: при ввімкненні виплати обробляються з сьогодні (раніші не дораховуються)
        val auto = f.group == "ОВДП" && f.pAuto
        val autoFrom = if (!auto) "" else old?.takeIf { it.autoCpn && isIsoDate(it.autoFrom) }?.autoFrom ?: todayIso()
        return Pos(f.group, f.pName.trim(), q, b, if (old == null) b else nw, f.pMat.trim(), parseNum(f.pRate), f.pNext.trim(), calc, monthly, if (monthly || auto) f.pPayAcc else "", ticker = ticker,
            cur = bondCur, src = old?.src ?: "", ibSym = old?.ibSym ?: "", autoCpn = auto, autoFrom = autoFrom)
    }

    /** Щомісячні відсотки депозитів і купони/погашення ОВДП з автозарахуванням, що вже настали. */
    private fun processPayouts(d: AppData): AppData = processOvdpPayouts(processDepositPayouts(d, todayIso(), RATES), todayIso(), RATES, nbuIndex)
    /** ОВДП з купонами в дохід потребує рахунку зарахування. */
    private fun autoAccError(p: Pos): String = if (p.autoCpn && data.accs.none { it.id == p.payAcc && it.id.isNotEmpty() }) "Виберіть рахунок для купонів" else ""
    /** Рахунок за замовчуванням для купонів: перший у валюті облігації, інакше перший. */
    fun defaultPayAcc(cur: String): String = (data.accs.firstOrNull { it.cur == cur && !it.cash } ?: data.accs.firstOrNull { it.cur == cur } ?: data.accs.firstOrNull())?.id ?: ""

    /** Для депозиту кількість не вводиться: 1, якщо поле порожнє. */
    private fun posQty(f: Form): Double = parseNum(f.pQty).let { if (it <= 0 && f.group == "Депозити" && f.pQty.isBlank()) 1.0 else it }
    private fun txFromForm(f: Form, key: String): Tx? {
        val (t, err) = buildTx(TxInput(f.tType, parseNum(f.tAmt), if (f.tType == "transfer") "Перекази" else f.tCat, f.tDesc.trim(), f.tDate.trim(), f.tAcc, f.tToAcc, parseNum(f.tToAmt), key), data.accs, RATES, rate)
        formErr = err; return t
    }
    fun openPos() { formErr = ""; formOrig = null; form = form.copy(pName = "", pQty = "", pBuy = "", pRate = "", pMat = "", pNext = "", group = "ОВДП", pMonthly = false, pPayAcc = data.accs.firstOrNull()?.id ?: "", pCur = "UAH", pFx = "", pBond = null, pAuto = false); posCheck = false; nbuMsg = ""; sheet = Sheet.Pos }
    fun editAcc(i: Int) { val a = data.accs[i]; form = form.copy(editIdx = i, sav = a.sav, cashF = a.cash, name = a.name, bal = (Math.round(a.bal * 100) / 100.0).toString()); formOrig = form; sheet = Sheet.AccE }
    fun editPos(i: Int) {
        val p = data.pos[i]; fun c(v: Double) = ((v / rate) * 100).let { Math.round(it) / 100.0 }.toString()
        fun r2(v: Double) = (Math.round(v * 10000) / 10000.0).toString()
        val fx = isFxPos(p); val bond = isBondCurPos(p)  // валютна ОВДП: ціни у валюті облігації
        form = form.copy(editIdx = i, group = p.group, pName = p.name, pQty = p.qty.toString(),
            pBuy = if (fx) r2(p.pxBuy) else if (bond) r2(p.buy) else c(p.buy), pNow = if (fx) r2(p.pxNow) else if (bond) r2(p.now) else c(p.now),
            pFx = if (fx) r2(p.buyFx) else "", pCur = if (fx || bond) p.cur else "UAH",
            pRate = p.rate.toString(), pMat = p.mat, pNext = p.next, pMonthly = p.monthly, pPayAcc = p.payAcc.ifEmpty { defaultPayAcc(if (bond) p.cur else "UAH") }, pBond = null, pAuto = p.autoCpn); formOrig = form; formErr = ""; posCheck = false; nbuMsg = ""; sheet = Sheet.PosE
    }
    /** Довідка з будь-якого екрана: відкривається на розділі, що відповідає поточному екрану. */
    fun openHelp(section: Int = helpSectionFor(screen)) { help = section; helpTop = section; sheet = Sheet.Help }
    fun openSettings() { eraseAsk = false; sheet = Sheet.Settings }
    fun openImport() { impResult = null; impItems = emptyList(); sheet = Sheet.Import }

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
                // погашена автоматично ОВДП лишається з кількістю 0 — її можна перейменувати чи змінити рахунок
                val redeemed = ovdpRedeemed(o, TODAY) && q == 0.0
                formErr = posFormError(f.group, f.pName, if (redeemed) 1.0 else q, if (redeemed) Math.max(np.buy, 1.0) else np.buy, monthly, parseNum(f.pRate), f.pNext, f.pMat).ifEmpty { fxFormError(np, RATES) }.ifEmpty { autoAccError(np) }; if (formErr.isNotEmpty()) return
                open = open + f.group
                commit(processPayouts(s.copy(pos = s.pos.mapIndexed { i, x -> if (i == f.editIdx) np else x }))); closeSheet()
            }
            Sheet.Pos -> {
                val q = posQty(f); val monthly = f.group == "Депозити" && f.pMonthly
                val np = posFromForm(f, q, monthly, null)
                formErr = posFormError(f.group, f.pName, q, np.buy, monthly, parseNum(f.pRate), f.pNext, f.pMat).ifEmpty { fxFormError(np, RATES) }.ifEmpty { autoAccError(np) }; if (formErr.isNotEmpty()) return
                open = open + f.group
                commit(processPayouts(s.copy(pos = s.pos + np))); closeSheet()
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
                if (q.isEmpty() || (t.isEmpty() && !ibkr.on)) { formErr = "Вкажіть токен і номер Flex-запиту (Query ID)"; return }
                flexQueryError(q).let { if (it.isNotEmpty()) { formErr = it; return } }
                try { IbkrStore.save(ctx, t.ifEmpty { IbkrStore.token(ctx) }, q) } catch (e: Throwable) { formErr = "Не вдалося зберегти токен на пристрої: ${e.javaClass.simpleName}"; return }
                form = form.copy(ibTok = "", ibQ = ""); formErr = ""; ibkr.connected(); ibkr.sync()  // вікно лишається відкритим, поки триває синхронізація і видно результат
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

    // ---------- довідник ОВДП НБУ: вибір випуску і звірка ----------
    var pickQ by mutableStateOf("")
    var pickCur by mutableStateOf<String?>(null)
    var pickTerm by mutableStateOf<Int?>(null)
    private var pickBack = Sheet.Pos
    /** Звірка в формі редагування позиції: показати розбіжності з НБУ. */
    var posCheck by mutableStateOf(false)
    fun togglePosCheck() { posCheck = !posCheck; if (posCheck && registryDue(nbu, System.currentTimeMillis())) refreshNbu() }

    /** Кнопка «Вибрати випуск» у формі ОВДП. Довідник завантажується, якщо його ще немає або він застарів. */
    /** Куди йде вибраний випуск: форма позиції, калькулятор (forCalc) або «Порівняти» (forCompare). */
    private var pickForCalc = false
    private var pickForCmp = false
    fun openOvdpPick(forCalc: Boolean = false, forCompare: Boolean = false) {
        pickForCalc = forCalc; pickForCmp = forCompare
        pickBack = if (forCalc || forCompare) Sheet.None else if (sheet == Sheet.PosE) Sheet.PosE else Sheet.Pos
        pickQ = ""; pickTerm = null; pickCur = null; nbuMsg = ""
        sheet = Sheet.OvdpPick
        if (registryDue(nbu, System.currentTimeMillis())) refreshNbu()
    }

    /** Вибір випуску заповнює ISIN, валюту, номінал, купон, погашення й графік; кількість і ціну вводить користувач. */
    fun pickBond(b: NbuBond) {
        val today = LocalDate.parse(TODAY)
        if (pickForCmp) { cmpAdd(b); sheet = Sheet.None; return }
        if (pickForCalc) {
            // калькулятор: ISIN, валюта, номінал, купон, періодичність, погашення й дата розміщення; ціну й кількість — вводить користувач
            calcIn = calcIn.copy(isin = b.isin, cur = b.cur, nom = if (b.nom % 1.0 == 0.0) b.nom.toLong().toString() else b.nom.toString(), coupon = b.rate.toString(),
                freq = bondFreqMonths(b), mat = b.mat, issue = b.issue, sellD = if (calcIn.sellD.isBlank() || calcIn.sellD > b.mat) b.mat else calcIn.sellD)
            sheet = Sheet.None; return
        }
        if (pickBack == Sheet.PosE) {
            val o = data.pos.getOrNull(form.editIdx)
            val oc = o?.let { if (isBondCurPos(it)) it.cur else "UAH" } ?: "UAH"
            if (o != null && oc != b.cur) { nbuMsg = "Валюта випуску ${b.cur} відрізняється від валюти позиції $oc: ціни позиції записані в $oc. Для випуску в іншій валюті додайте нову позицію."; return }
        }
        val f = form
        form = f.copy(group = "ОВДП", pName = if (f.pName.uppercase().contains(b.isin)) f.pName else "ОВДП ${b.isin}", pCur = b.cur,
            pRate = b.rate.toString(), pMat = b.mat, pNext = nextCoupon(b, today), pBond = b)
        formErr = ""; sheet = pickBack
    }
    /** Скасувати вибір з довідника (дані в полях лишаються, далі — як ручне введення). */
    fun clearPickedBond() {
        val o = formOrig
        form = form.copy(pBond = null, pCur = if (o != null) o.pCur else "UAH")
    }

    /** Список для вибору: пошук за ISIN і фільтри валюти й строку. */
    fun pickList(): List<NbuBond> = filterBonds(nbu.bonds, pickQ, pickCur, pickTerm, LocalDate.parse(TODAY))

    /** Рядок звірки: позиція (індекс у data.pos), її ISIN, випуск з довідника (null — не знайдено) і розбіжності. */
    data class OvdpCheckRow(val idx: Int, val pos: Pos, val isin: String, val bond: NbuBond?, val diffs: List<OvdpDiff>)
    fun ovdpCheck(): List<OvdpCheckRow> {
        val today = LocalDate.parse(TODAY); val ix = nbuIndex
        return data.pos.mapIndexedNotNull { i, p ->
            if (p.group != "ОВДП") return@mapIndexedNotNull null
            val isin = posIsin(p); val b = ix[isin]
            OvdpCheckRow(i, p, isin, b, if (b != null) reconcile(p, b, today) else emptyList())
        }
    }
    /** «Звірити з НБУ» для всіх позицій ОВДП (з екрана «Інвестиції»). */
    fun openOvdpCheck() {
        nbuMsg = ""; sheet = Sheet.OvdpCheck
        if (registryDue(nbu, System.currentTimeMillis())) refreshNbu()
    }
    /** Оновлює позиції [idx] даними НБУ (погашення, купон, наступна виплата, номінал); валюта не змінюється. */
    fun applyNbuTo(idx: List<Int>) {
        val today = LocalDate.parse(TODAY); val ix = nbuIndex; val set = idx.toSet(); var n = 0
        val pos = data.pos.mapIndexed { i, p -> val b = if (i in set) ix[posIsin(p)] else null; if (b != null) { n++; applyNbu(p, b, today) } else p }
        if (n == 0) return
        commit(data.copy(pos = pos)); nbuMsg = "Оновлено з НБУ: ${n} ${if (n == 1) "позиція" else if (n in 2..4) "позиції" else "позицій"}"
    }
    /** Звірка у формі редагування: підставляє дані НБУ в поля форми (зберігається кнопкою «Зберегти»). */
    fun fillFormFromNbu(b: NbuBond) {
        val f = form
        form = f.copy(pRate = b.rate.toString(), pMat = b.mat, pNext = nextCoupon(b, LocalDate.parse(TODAY)).ifEmpty { f.pNext },
            pBond = if (f.pCur == b.cur) b else null)
    }

    // ---------- порівняти ОВДП (OvdpCompare.kt, OvdpCompareUi.kt) ----------
    /** Випуск у порівнянні: повна ціна за 1 шт (у валюті випуску, як введено) і звідки взято. */
    data class CmpEntry(val bond: NbuBond, val price: String = "", val from: String = "")
    var cmp by mutableStateOf(listOf<CmpEntry>()); private set
    var cmpAmount by mutableStateOf("100000")
    var cmpDate by mutableStateOf(TODAY)
    /** Припущена ставка реінвестування (частка); null — дохідність найкоротшого варіанту. */
    var cmpRate by mutableStateOf<Double?>(null)
    var cmpMsg by mutableStateOf(""); private set
    fun setOvdpTax(v: String) { val t = parseNum(v).coerceIn(0.0, 100.0); if (t != ovdpTax) { ovdpTax = t; persist() } }

    fun cmpAdd(b: NbuBond, price: String = "", from: String = "") {
        cmpMsg = when {
            cmp.any { it.bond.isin == b.isin } -> "${b.isin} уже в порівнянні"
            cmp.size >= 3 -> "Порівнювати можна до 3 випусків"
            else -> { cmp = cmp + CmpEntry(b, price, from); cmpRate = null; "" }
        }
    }
    fun cmpRemove(i: Int) { cmp = cmp.filterIndexed { j, _ -> j != i }; cmpRate = null; cmpMsg = "" }
    fun cmpSetPrice(i: Int, v: String) { cmp = cmp.mapIndexed { j, e -> if (j == i) e.copy(price = v) else e } }
    /** Позиції ОВДП, чий випуск є в довіднику НБУ (їх можна додати в порівняння). */
    fun cmpPositions(): List<Pos> = data.pos.filter { it.group == "ОВДП" && it.qty > 0 && nbuIndex.containsKey(posIsin(it)) }
    /** Додати з моїх позицій: ціна — поточна ціна позиції за 1 шт у валюті випуску. */
    fun cmpAddPosition(p: Pos) {
        val b = nbuIndex[posIsin(p)] ?: return
        val px = if (isBondCurPos(p) || p.cur == b.cur) p.now else p.now / rateOf(b.cur)
        cmpAdd(b, if (px > 0) fmtPlain(px) else "", p.name)
    }
    fun cmpSettle(): LocalDate = cmpDate.takeIf { isIsoDate(it) }?.let { LocalDate.parse(it) } ?: LocalDate.parse(TODAY)
    fun cmpResults(): List<CmpResult> =
        compareOvdp(cmp.map { CmpPick(it.bond, parseNum(it.price)) }, parseNum(cmpAmount), cmpSettle(), RATES.toMap(), ovdpTax)
    /** «Додати в портфель»: форма нової позиції з випуском, кількістю й ціною з порівняння. */
    fun cmpToPortfolio(r: CmpResult) {
        val leg = r.legs.singleOrNull() ?: return
        val b = nbuIndex[leg.isin] ?: cmp.firstOrNull { it.bond.isin == leg.isin }?.bond ?: return
        openPos()
        // ціна у формі: валютна — у валюті випуску, гривнева — у валюті перегляду (як у формі)
        val px = if (b.cur == "UAH") leg.price / rate else leg.price
        form = form.copy(group = "ОВДП", pName = "ОВДП ${b.isin}", pCur = b.cur, pRate = b.rate.toString(), pMat = b.mat,
            pNext = nextCoupon(b, LocalDate.parse(TODAY)), pBond = b, pQty = leg.qty.toString(), pBuy = fmtPlain(px))
    }

    // ---------- калькулятор ----------
    fun posToCalc() {
        val f = form; sheet = Sheet.None; screen = Screen.Calc
        calcIn = calcIn.copy(isin = f.pName.removePrefix("ОВДП ").trim(), price = f.pBuy, qty = f.pQty, mat = f.pMat, coupon = f.pRate, sell = f.pNow.ifEmpty { f.pBuy }, sellD = f.pMat)
    }
    fun openPosInCalc(p: Pos) {
        val x = p.calc
        calcIn = CalcInput(x?.isin ?: p.name.removePrefix("ОВДП "), (x?.nom ?: 1000.0).toString().removeSuffix(".0"), x?.buy ?: TODAY, p.mat, p.buy.toString(), p.qty.toString(), p.rate.toString(), x?.freq ?: 6, x?.sell ?: p.now.toString(), x?.sellD ?: p.mat, cur = x?.cur ?: p.cur)
        screen = Screen.Calc
    }
    fun addCalcToInv() {
        val c = calcOvdp(calcIn); val pr = parseNum(calcIn.price)
        if (c.qty > 0) {
            open = open + "ОВДП"; screen = Screen.Inv
            commit(data.copy(pos = data.pos + Pos("ОВДП", "ОВДП " + calcIn.isin.ifEmpty { "без ISIN" }, c.qty.toDouble(), pr, pr, calcIn.mat, parseNum(calcIn.coupon), "",
                CalcExtra(calcIn.isin, parseNum(calcIn.nom).takeIf { it != 0.0 } ?: 1000.0, calcIn.freq, calcIn.sell, calcIn.sellD, calcIn.buy, calcIn.cur),
                cur = calcIn.cur)))
        }
    }

    // ---------- імпорт ----------
    fun importUri(uri: Uri, name: String) {
        impBusy = true; impResult = null; impItems = emptyList()
        val rules = data.rules
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                try {
                    val ins = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?: return@withContext ImportResult(emptyList(), emptyList(), 0, "Не вдалося відкрити файл «$name» (немає доступу)")
                    val bytes = ins.use { it.readBytes() }
                    if (bytes.isEmpty()) ImportResult(emptyList(), emptyList(), 0, "Файл порожній")
                    else {
                        // курс на дату операції: спершу розбір без архіву, далі архів НБУ за період (по валютах виписки)
                        val first = Importer.parseBytes(bytes, rate, rules)
                        val dates = first.txs.map { it.date }.sorted()
                        val curs = (RATES.keys - "UAH").toList()
                        val archive = HashMap<String, MutableMap<String, Double>>()
                        if (dates.isNotEmpty()) for (c in curs) fetchNbuHistory(c, dates.first(), dates.last()).forEach { (d, v) -> archive.getOrPut(d) { HashMap() }[c] = v }
                        if (archive.isEmpty()) first else Importer.parseBytes(bytes, rate, rules, archive)
                    }
                } catch (e: Throwable) { ImportResult(emptyList(), emptyList(), 0, "Не вдалося прочитати файл (${e.javaClass.simpleName}: ${e.message?.take(80) ?: "—"})") }
            }
            impResult = r
            val existing = if (data.demo && r.accs.isNotEmpty()) emptyList() else data.txs
            impItems = detectDuplicates(r.txs, existing)
            impSkipDuplicates = true
            impBusy = false
        }
    }

    fun importSample() {
        val r = Importer.parseCsv("01.11.2026;Сільпо;-1250,40\n01.11.2026;Uber;-310\n02.11.2026;Аптека Доба;-480\n02.11.2026;Зарплата;45000\n02.11.2026;Spotify;-169", rate, data.rules)
        impResult = r
        val existing = if (data.demo && r.accs.isNotEmpty()) emptyList() else data.txs
        impItems = detectDuplicates(r.txs, existing)
        impSkipDuplicates = true
    }

    fun toggleImportItem(index: Int) {
        if (index in impItems.indices) {
            impItems = impItems.mapIndexed { i, itm -> if (i == index) itm.copy(selected = !itm.selected) else itm }
        }
    }

    fun toggleSkipDuplicates(skip: Boolean) {
        impSkipDuplicates = skip
        impItems = impItems.map { itm -> if (itm.isDuplicate) itm.copy(selected = !skip) else itm }
    }

    fun selectAllImport(select: Boolean) {
        impItems = impItems.map { it.copy(selected = select) }
    }

    private fun existingKeys(): Set<String> = if (data.demo && impResult?.accs?.isNotEmpty() == true) emptySet() else data.txs.mapNotNull { it.key.ifEmpty { null } }.toSet()
    val dupN: Int get() = impItems.count { it.isDuplicate }
    fun removeImportDups() {
        impItems = impItems.filter { !it.isDuplicate }
    }
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
        val r = impResult ?: return
        val selectedTxs = impItems.filter { it.selected }.map { it.tx }
        if (selectedTxs.isEmpty()) return
        val s = data
        val rep = s.demo && r.accs.isNotEmpty()
        val keep = if (rep) emptyList() else s.txs
        val txs = selectedTxs + keep
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
    fun lockNow() { sheet = Sheet.None; kmode = KMode.Unlock; entry = ""; kerr = lockMsg() }
    fun cancelPin() { kmode = KMode.None; entry = ""; kerr = ""; tmp = "" }
    fun unlockByBio() { lock = PinLock(); PinLockStore.save(kv, lock); kmode = KMode.None; entry = ""; kerr = "" }
    fun onStopped() { hiddenAt = System.currentTimeMillis() }
    fun onStarted() { runRecurring(); refreshNotifyState(); ibkr.autoSync(); if (sec.hash.isNotEmpty() && hiddenAt > 0 && kmode == KMode.None && System.currentTimeMillis() - hiddenAt >= sec.autoMin * 60000L) { kmode = KMode.Unlock; entry = ""; kerr = lockMsg(); sheet = Sheet.None } }
    fun backspace() { entry = entry.dropLast(1) }
    fun press(d: String) {
        if (kmode == KMode.None || pinBusy) return
        val checking = kmode == KMode.Unlock || kmode == KMode.Old
        val left = pinLockLeft(lock, System.currentTimeMillis())
        if (checking && left > 0) { kerr = pinLockText(left); entry = ""; return }
        val v = entry + d
        if (v.length < 4) { entry = v; kerr = ""; return }
        entry = v; val m = kmode
        when (m) {
            KMode.Unlock, KMode.Old -> {
                pinBusy = true; val stored = sec.hash
                viewModelScope.launch {
                    val chk = withContext(Dispatchers.Default) { verifyPin(v, stored) }
                    // старий SHA-256 хеш після успішного входу перезаписується в новому форматі
                    val upgraded = if (chk.ok && chk.upgrade) withContext(Dispatchers.Default) { hashPin(v) } else null
                    pinBusy = false
                    if (chk.ok) {
                        lock = PinLock(); PinLockStore.save(kv, lock)
                        if (upgraded != null) saveSec(sec.copy(hash = upgraded))
                        if (m == KMode.Unlock) { kmode = KMode.None; entry = ""; kerr = "" }
                        else if (kact == "disable") { saveSec(Security("", false, sec.autoMin)); kmode = KMode.None; entry = ""; kerr = "" }
                        else { kmode = KMode.New; entry = ""; kerr = "" }
                    } else {
                        val now = System.currentTimeMillis()
                        lock = pinFailed(lock, now); PinLockStore.save(kv, lock); entry = ""
                        val l = pinLockLeft(lock, now)
                        kerr = if (l > 0) pinLockText(l) else "Невірний PIN. Спроба ${lock.fails} з $PIN_MAX_FAILS"
                    }
                }
            }
            KMode.New -> { tmp = v; kmode = KMode.Confirm; entry = ""; kerr = "" }
            KMode.Confirm -> if (v == tmp) {
                pinBusy = true
                viewModelScope.launch {
                    val h = withContext(Dispatchers.Default) { hashPin(v) }
                    pinBusy = false; saveSec(sec.copy(hash = h)); kmode = KMode.None; entry = ""; kerr = ""; tmp = ""
                }
            } else { kmode = KMode.New; entry = ""; tmp = ""; kerr = "PIN не збігається. Створіть заново" }
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
    fun upcomingOvdp(): List<OvdpEvent> = LocalDate.parse(TODAY).let { ovdpEvents(data.pos, it, it.plusDays(60), nbuIndex) }

    /** Наступна очікувана виплата купона або погашення ОВДП (для банера нагадувань). */
    fun nextOvdpEvent(): OvdpEvent? = LocalDate.parse(TODAY).let { ovdpEvents(data.pos, it, it.plusDays(365), nbuIndex).firstOrNull() }

    /** Надсилає тестове сповіщення про виплату ОВДП на пристрій для перевірки. */
    fun testOvdpNotification() {
        val msg = Jobs.sendTestOvdpNotification(ctx)
        remindMsg = msg
        try { android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
    }

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
