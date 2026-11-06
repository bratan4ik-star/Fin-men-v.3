package ua.bella.finance

/* Типи стану інтерфейсу: вікна, екрани, поля форм і фільтри (раніше — на початку BellaViewModel.kt). */

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
enum class Screen { Home, Acc, Bud, Inv, Goals, Pass, Calc }
