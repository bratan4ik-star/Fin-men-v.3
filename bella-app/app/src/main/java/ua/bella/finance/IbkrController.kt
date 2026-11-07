package ua.bella.finance

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Interactive Brokers у застосунку: стан підключення й синхронізації, історія рахунку й цін для графіків.
 * Дані застосунку читає через data(), зміни вносить через commit() (з журналом «Скасувати»), а історію,
 * що живе поза журналом, зберігає через persist(). Розбір Flex-звіту й злиття — у Ibkr.kt і IbkrCharts.kt.
 */
class IbkrController(
    private val app: Application,
    private val scope: CoroutineScope,
    loaded: Persisted,
    private val data: () -> AppData,
    private val commit: (AppData) -> Unit,
    private val persist: () -> Unit,
) {
    var on by mutableStateOf(try { IbkrStore.configured(app) } catch (_: Exception) { false }); private set
    var at by mutableStateOf(try { IbkrStore.lastAt(app) } catch (_: Exception) { 0L }); private set
    var busy by mutableStateOf(false); private set
    var msg by mutableStateOf(""); private set
    /** ETF з IBKR, яких більше немає в рахунку: чекають рішення «прибрати / залишити». */
    var gone by mutableStateOf(listOf<String>()); private set

    /** Історія рахунку для графіків (поза журналом «Скасувати»): вартість, поповнення, базова валюта. */
    var nav by mutableStateOf(loaded.ibNav); private set
    var flows by mutableStateOf(loaded.ibFlows); private set
    var base by mutableStateOf(loaded.ibBase); private set
    /** Історія цін ETF за символом (з Flex-звітів), до 5 років. */
    var px by mutableStateOf(loaded.ibPx); private set
    /** Період перемикача у вікні графіків. */
    var period by mutableStateOf("1Y")

    fun restore(p: Persisted) { nav = p.ibNav; flows = p.ibFlows; base = p.ibBase; px = p.ibPx }
    /** Токен і Query ID щойно збережено. */
    fun connected() { on = true }

    fun sync() {
        if (busy || !on) return
        busy = true; msg = ""
        scope.launch {
            try {
                val (f, err) = withContext(Dispatchers.IO) { try { fetchFlex(IbkrStore.token(app), IbkrStore.query(app)) } catch (e: Throwable) { null to "Помилка IBKR: ${e.javaClass.simpleName}: ${e.message?.take(120) ?: ""}" } }
                if (f == null) msg = err
                else {
                    val (nd, sum) = applyFlex(data(), f, RATES, System.currentTimeMillis())
                    val b = f.base.ifEmpty { base }.takeIf { RATES.containsKey(it) } ?: "USD"
                    if (b != base) { nav = emptyList(); flows = emptyList() }
                    base = b
                    mergeIbHist(nav, flows, f, ibkrSnapshot(nd, RATES, b), TODAY).let { (n, fl) -> nav = n; flows = fl }
                    px = mergeIbPrices(px, f)
                    commit(nd); gone = sum.gone
                    val t = System.currentTimeMillis(); IbkrStore.setLastAt(app, t); at = t
                    msg = (if (f.asOf.isNotEmpty()) "Дані IBKR на ${ddmmyyyy(f.asOf)}. " else "") + sum.text
                }
            } catch (e: Throwable) {
                msg = "Помилка застосунку під час синхронізації: ${e.javaClass.simpleName}: ${e.message?.take(160) ?: ""}"
            } finally { busy = false }
        }
    }

    private var tryAt = 0L
    /** Синхронізація при кожному відкритті чи поверненні до застосунку, але не частіше ніж раз на годину (і після невдалої спроби теж). */
    fun autoSync() {
        val now = System.currentTimeMillis()
        if (!on || !ibkrDue(at, tryAt, now)) return
        tryAt = now; sync()
    }

    fun removeGone() { if (gone.isNotEmpty()) commit(removeGone(data(), gone)); gone = emptyList() }
    fun keepGone() { gone = emptyList() }

    fun disconnect() {
        try { IbkrStore.clear(app) } catch (_: Exception) {}
        on = false; at = 0L; nav = emptyList(); flows = emptyList(); px = emptyMap(); persist()
        msg = "IBKR відключено. Позиції лишились як є."
    }

    /** Лише для знімків екранів у тестах: підставляє історію рахунку без синхронізації. */
    @androidx.annotation.VisibleForTesting
    internal fun seed(nav: List<HistPoint>, flows: List<IbFlow>, base: String = "USD", px: Map<String, List<HistPoint>> = emptyMap()) {
        this.nav = nav; this.flows = flows; this.base = base; this.px = px
    }
}
