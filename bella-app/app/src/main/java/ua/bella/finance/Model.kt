package ua.bella.finance

import kotlinx.serialization.Serializable
import java.util.Locale

/** Курси до гривні. Початкові значення з прототипу; після «Оновити курси» підміняються курсами НБУ (змінюється лише з головного потоку). */
val RATES: MutableMap<String, Double> = mutableMapOf("UAH" to 1.0, "USD" to 41.5, "EUR" to 46.0, "PLN" to 10.0)
val SYM = mapOf("UAH" to "₴", "USD" to "$", "EUR" to "€", "PLN" to "zł")
val CURRENCIES = listOf("UAH", "USD", "EUR", "PLN")
/** Сьогоднішня дата і поточний місяць телефону (раніше були зафіксовані на 2026-11-02 з прототипу). */
val TODAY: String get() = java.time.LocalDate.now().toString()
val CUR_MONTH: String get() = TODAY.take(7)

val MONTH_NAMES = listOf("Січень", "Лютий", "Березень", "Квітень", "Травень", "Червень", "Липень", "Серпень", "Вересень", "Жовтень", "Листопад", "Грудень")

/** Групи інвестицій і їхні кольори. */
val GROUPS = listOf("ОВДП" to 0xFF9184D9, "Депозити" to 0xFF60A5FA, "ETF" to 0xFFFBBF24, "Крипто" to 0xFFC084FC)
val DEFAULT_TARGETS = linkedMapOf("ОВДП" to 40.0, "Депозити" to 20.0, "ETF" to 30.0, "Крипто" to 10.0)
val LIMITS = linkedMapOf("Харчування" to 12000.0, "Житло" to 20000.0, "Транспорт" to 3000.0, "Розваги" to 2000.0, "Здоров'я" to 1500.0, "Інше" to 0.0)
val CATEGORIES = listOf("Перекази", "Надходження", "Зняття готівки", "Послуги", "Поповнення мобільного", "Побутова техніка", "Харчування", "Житло", "Транспорт", "Розваги", "Здоров'я", "Зарплата", "Інше")

/** Правило категорії: match — фрагменти через кому (без урахування регістру), cat — категорія. */
@Serializable data class CatRule(val match: String, val cat: String)
val DEFAULT_RULES = listOf(
    CatRule("сільпо, атб, novus, ашан, silpo, продукт", "Харчування"),
    CatRule("uber, bolt, uklon, метро, таксі, wog, okko", "Транспорт"),
    CatRule("netflix, кіно, spotify, steam", "Розваги"),
    CatRule("аптек, лікар, apteka", "Здоров'я"),
    CatRule("оренд", "Житло"),
    CatRule("зарплат, salary, фріланс", "Зарплата"),
)

@Serializable data class Account(val name: String, val cur: String, val bal: Double, val cash: Boolean = false, val sav: Boolean = false, val id: String = "")
/**
 * amt завжди в гривнях. type: income | expense | transfer. key використовується для пошуку повторів при імпорті.
 * acc — id рахунку («» = без рахунку), accAmt — сума у валюті рахунку, що змінила його баланс.
 * Для переказу acc — звідки, toAcc/toAmt — куди і скільки зараховано у валюті отримувача.
 */
@Serializable data class Tx(
    val desc: String, val cat: String, val type: String, val amt: Double, val date: String, val key: String = "",
    val acc: String = "", val accAmt: Double = 0.0, val toAcc: String = "", val toAmt: Double = 0.0,
)
@Serializable data class Debt(val who: String, val owedToMe: Boolean, val amt: Double, val done: Boolean = false, val due: String = "")
@Serializable data class CalcExtra(val isin: String, val nom: Double, val freq: Int, val sell: String, val sellD: String, val buy: String)
@Serializable data class Pos(val group: String, val name: String, val qty: Double, val buy: Double, val now: Double, val mat: String = "", val rate: Double = 0.0, val next: String = "", val calc: CalcExtra? = null,
    /** Депозит: щомісячна виплата відсотків у дохід на рахунок payAcc («» = без рахунку). */
    val monthly: Boolean = false, val payAcc: String = "",
    /** ETF: валюта ціни, ціни купівлі й поточна у ній, курс купівлі, тікер Yahoo і час котирування (див. Etf.kt). */
    val cur: String = "UAH", val pxBuy: Double = 0.0, val pxNow: Double = 0.0, val buyFx: Double = 0.0, val ticker: String = "", val pxAt: Long = 0L,
)
/** Ціль: суми в гривнях; deadline — РРРР-ММ-ДД або «». */
@Serializable data class Goal(val name: String, val saved: Double, val target: Double, val icon: String, val step: Double, val deadline: String = "",
    /** Запланований внесок на місяць (₴) для прогнозу; 0 — не задано. */
    val monthly: Double = 0.0)
@Serializable data class Override(val name: String, val cur: String, val bal: Double, val sav: Boolean)
@Serializable data class Security(val hash: String = "", val bio: Boolean = false, val autoMin: Int = 1)

@Serializable
data class AppData(
    val txs: List<Tx> = DemoData.txs,
    val accs: List<Account> = DemoData.accs,
    val debts: List<Debt> = DemoData.debts,
    val pos: List<Pos> = DemoData.pos,
    val goals: List<Goal> = DemoData.goals,
    val demo: Boolean = true,
    /** Місячні ліміти витрат за категоріями (у гривнях) і список категорій — редагуються в «Бюджет → Ліміти». */
    val limits: Map<String, Double> = LIMITS.filterValues { it > 0 },
    val cats: List<String> = CATEGORIES,
    /** Повторювані щомісячні операції (оренда, підписки). */
    val recurring: List<Recurring> = emptyList(),
    /** Правила категорій для імпорту: перше правило, фрагмент якого є в описі, задає категорію. */
    val rules: List<CatRule> = DEFAULT_RULES,
    /** Цільовий розподіл інвестицій за групами, % (для ребалансування в «Інвестиціях»). */
    val targets: Map<String, Double> = DEFAULT_TARGETS,
)

@Serializable
data class Persisted(
    val data: AppData = AppData(),
    val cur: String = "UAH",
    val month: Int = 1,
    val from: Int = 0,
    val to: Int = 1,
    val ov: Map<String, Override> = emptyMap(),
    val light: Boolean = false,
    val sec: Security = Security(),
    val rates: Map<String, Double> = emptyMap(),
    val ratesDate: String = "",
    val ratesAt: Long = 0L,
    /** Щоденні знімки чистого капіталу для графіка на головній (поза журналом «Скасувати»). */
    val hist: List<HistPoint> = emptyList(),
)

object DemoData {
    val accs = listOf(
        Account("Monobank Біла", "UAH", 42180.0), Account("ПриватБанк", "UAH", 31640.0),
        Account("Готівка USD", "USD", 480.0, cash = true), Account("Готівка EUR", "EUR", 300.0, cash = true),
        Account("Готівка UAH", "UAH", 8600.0, cash = true), Account("Готівка PLN", "PLN", 400.0, cash = true),
    )
    val debts = listOf(Debt("Іван", true, 12300.0, false, "2026-12-20"), Debt("Оренда, листопад", false, 7500.0, false, "2026-12-05"))
    val txs = listOf(
        Tx("Зарплата", "Зарплата", "income", 65000.0, "2026-11-01"), Tx("Фріланс", "Зарплата", "income", 12000.0, "2026-11-02"),
        Tx("Оренда", "Житло", "expense", 18000.0, "2026-11-02"), Tx("Сільпо", "Харчування", "expense", 2340.0, "2026-11-01"),
        Tx("АТБ", "Харчування", "expense", 1860.0, "2026-11-02"), Tx("Uber", "Транспорт", "expense", 540.0, "2026-11-01"),
        Tx("Netflix", "Розваги", "expense", 399.0, "2026-11-01"), Tx("Аптека", "Здоров'я", "expense", 820.0, "2026-11-02"),
        Tx("Кіно", "Розваги", "expense", 650.0, "2026-11-02"),
        Tx("Зарплата", "Зарплата", "income", 65000.0, "2026-10-01"), Tx("Оренда", "Житло", "expense", 18000.0, "2026-10-02"),
        Tx("Супермаркети", "Харчування", "expense", 9800.0, "2026-10-15"), Tx("Таксі", "Транспорт", "expense", 1900.0, "2026-10-12"),
        Tx("Розваги", "Розваги", "expense", 2400.0, "2026-10-20"),
    )
    val pos = listOf(
        Pos("ОВДП", "ОВДП UA4000227045", 100.0, 960.0, 985.0, "2027-05-12", 15.5, "2026-12-10"),
        Pos("ОВДП", "ОВДП UA4000225981", 50.0, 990.0, 1010.0, "2028-03-15", 16.0, "2027-01-20"),
        Pos("Депозити", "Депозит Моно, 12 міс.", 1.0, 50000.0, 52100.0, "2027-03-01", 13.0, "2026-12-01"),
        Pos("Депозити", "Депозит Приват, 6 міс.", 1.0, 25000.0, 26400.0, "2027-01-15", 12.5, "2026-12-15"),
        Pos("ETF", "VWCE", 20.0, 2300.0, 2480.0, "", 2.0, "2026-12-20"),
        Pos("Крипто", "BTC", 0.005, 3500000.0, 3900000.0, "", 0.0, ""),
    )
    val goals = listOf(
        Goal("Подушка безпеки", 40000.0, 100000.0, "shield", 5000.0), Goal("Відпустка", 15000.0, 50000.0, "plane", 2000.0),
        Goal("Ноутбук", 7000.0, 60000.0, "laptop", 1000.0),
    )
}

// ---------- форматування ----------
private fun clean(s: String) = s.replace(' ', ' ').replace(' ', ' ')
fun fmtInt(v: Double): String = clean(String.format(Locale("uk", "UA"), "%,d", Math.round(v)))
fun fmt2(v: Double): String = clean(String.format(Locale("uk", "UA"), "%,.2f", v))
fun parseNum(s: String): Double = s.replace(" ", "").replace(',', '.').toDoubleOrNull() ?: 0.0
fun ddmm(d: String) = if (d.length >= 10) d.substring(8, 10) + "." + d.substring(5, 7) else "—"
fun ddmmyy(d: String) = if (d.length >= 10) d.substring(8, 10) + "." + d.substring(5, 7) + "." + d.substring(2, 4) else "—"
fun ddmmyyyy(d: String) = d.substring(8, 10) + "." + d.substring(5, 7) + "." + d.substring(0, 4)
fun monthLabel(k: String) = MONTH_NAMES[k.substring(5, 7).toInt() - 1] + " " + k.substring(0, 4)
fun nativeLabel(a: Account, hide: Boolean): String {
    if (hide) return "••••"
    val v = fmtInt(a.bal)
    return if (a.cur == "USD" || a.cur == "EUR") SYM[a.cur] + v else v + " " + SYM[a.cur]!!.trim()
}
