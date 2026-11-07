package ua.bella.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

/** Вибір випуску з довідника НБУ, форма позиції після вибору і звірка (на збережених відповідях НБУ, без мережі). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h1400dp-xxhdpi")
class OvdpNbuShotTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private fun shot(name: String) = saveShot(rule, File("build/screenshots").apply { mkdirs() }, name)
    private fun res(n: String) = javaClass.classLoader!!.getResource("nbu/$n")!!.readText()

    private fun registry(): OvdpRegistry = refreshRegistry(OvdpRegistry(), LocalDate.parse(TODAY), System.currentTimeMillis()) { url ->
        when {
            url == NBU_DEPO_URL -> res("depo_securities_sample.json")
            url == NBU_AUCTION_URL || url.endsWith("&date=03.11.2026") -> res("ovdp_2026-11-03.json")
            url.endsWith("&date=27.10.2026") -> res("ovdp_2026-10-27.json")
            else -> "[]"
        }
    }.first

    /** setContent можна викликати лише раз за тест, тож вміст перемикається станом. */
    private var view by mutableStateOf<(@Composable () -> Unit)?>(null)
    private fun show(vm: BellaViewModel, body: @Composable () -> Unit) {
        if (view == null) { view = body; rule.setContent {
            BellaTheme(vm.light) { Column(Modifier.fillMaxSize().background(C.bg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { view?.invoke() } }
        } } else rule.runOnUiThread { view = body }
    }

    @Test fun pickIssueFillsForm() {
        val vm = androidx.lifecycle.ViewModelProvider(rule.activity)[BellaViewModel::class.java]
        rule.runOnUiThread { vm.seedNbu(registry()); vm.openPos(); vm.openOvdpPick() }
        assertEquals(Sheet.OvdpPick, vm.sheet)
        assertEquals(6, vm.pickList().size)
        rule.runOnUiThread { vm.pickCur = "USD" }; assertEquals(listOf("UA4000236541"), vm.pickList().map { it.isin })
        rule.runOnUiThread { vm.pickCur = null; vm.pickTerm = 3; vm.pickQ = "2391" }; assertEquals(listOf("UA4000239131"), vm.pickList().map { it.isin })
        rule.runOnUiThread { vm.pickTerm = null; vm.pickQ = "" }
        show(vm) { OvdpPickView(vm) }
        shot("10-ovdp-pick")
        // вибір доларового випуску: ISIN, валюта, купон, погашення, наступна виплата; кількість і ціну вводить користувач
        val b = vm.nbu.bonds.first { it.isin == "UA4000236541" }
        rule.runOnUiThread { vm.pickBond(b) }
        assertEquals(Sheet.Pos, vm.sheet)
        assertEquals("ОВДП UA4000236541", vm.form.pName); assertEquals("USD", vm.form.pCur); assertEquals("2027-02-04", vm.form.pMat); assertEquals("4.17", vm.form.pRate); assertEquals("2027-02-04", vm.form.pNext)
        // лишилось дозаповнити кількість і ціну — вони підсвічені
        assertEquals(setOf("qty", "buy"), posMissing(vm.form, vm.formOrig))
        show(vm) { PosForm(vm, false) }
        shot("11-ovdp-pos-picked")
        rule.runOnUiThread { vm.form = vm.form.copy(pQty = "3", pBuy = "1012.40") }
        assertTrue(posMissing(vm.form, vm.formOrig).isEmpty())
        shot("11b-ovdp-pos-filled")
        val before = vm.data.pos.size
        rule.runOnUiThread { vm.submit() }
        val p = vm.data.pos.last()
        assertEquals(before + 1, vm.data.pos.size); assertEquals("USD", p.cur); assertEquals(1012.40, p.buy, 1e-9); assertEquals(3.0, p.qty, 0.0)
        assertEquals("UA4000236541", p.calc?.isin); assertEquals(1000.0, p.calc!!.nom, 0.0); assertEquals(6, p.calc!!.freq)
        // нагадування за офіційним графіком
        val e = ovdpEvents(listOf(p), LocalDate.parse(TODAY), LocalDate.parse(TODAY).plusYears(1), vm.nbuIndex).single()
        assertEquals(62.55, e.coupon, 1e-9); assertEquals(3000.0, e.principal, 0.0); assertEquals("USD", e.cur)
    }

    @Test fun reconcileExistingPositions() {
        val vm = androidx.lifecycle.ViewModelProvider(rule.activity)[BellaViewModel::class.java]
        rule.runOnUiThread {
            vm.seedNbu(registry())
            vm.commit(vm.data.copy(pos = vm.data.pos.filterNot { it.group == "ОВДП" } + listOf(
                Pos("ОВДП", "ОВДП UA4000239149", 20.0, 985.0, 990.0, "2028-01-20", 15.0, "2027-01-15"),
                Pos("ОВДП", "ОВДП UA4000239131", 5.0, 1000.0, 1000.0, "2029-03-21", 16.49, "2027-03-24"),
                Pos("ОВДП", "Облігації з банку", 10.0, 1000.0, 1000.0, "2027-06-01", 16.0, "2026-12-01"))))
            vm.openOvdpCheck()
        }
        val rows = vm.ovdpCheck()
        assertEquals(3, rows.size); assertEquals(3, rows[0].diffs.size); assertTrue(rows[1].diffs.isEmpty()); assertEquals("", rows[2].isin)
        show(vm) { OvdpCheckView(vm) }
        shot("12-ovdp-check")
        rule.runOnUiThread { vm.applyNbuTo(listOf(rows[0].idx)) }
        val fixed = vm.data.pos[rows[0].idx]
        assertEquals("2028-01-19", fixed.mat); assertEquals(15.1, fixed.rate, 1e-9); assertEquals("2027-01-20", fixed.next); assertEquals(20.0, fixed.qty, 0.0); assertEquals(985.0, fixed.buy, 0.0)
        assertTrue(vm.ovdpCheck().all { it.diffs.isEmpty() })
        // «Скасувати» повертає як було
        rule.runOnUiThread { vm.undo() }
        assertEquals("2028-01-20", vm.data.pos[rows[0].idx].mat)
        // звірка у формі редагування: підставити дані НБУ у поля
        rule.runOnUiThread { vm.editPos(rows[0].idx); vm.togglePosCheck() }
        show(vm) { PosForm(vm, true) }
        shot("13-ovdp-edit-check")
        rule.runOnUiThread { vm.fillFormFromNbu(vm.nbuIndex.getValue("UA4000239149")) }
        assertEquals("2028-01-19", vm.form.pMat); assertEquals("2027-01-20", vm.form.pNext)
        // у редагуванні не можна підставити випуск в іншій валюті
        rule.runOnUiThread { vm.openOvdpPick(); vm.pickBond(vm.nbuIndex.getValue("UA4000236541")) }
        assertEquals(Sheet.OvdpPick, vm.sheet); assertTrue(vm.nbuMsg.contains("відрізняється"))
        rule.runOnUiThread { vm.closeSheet() }
        assertEquals("закриття вибору повертає до форми", Sheet.PosE, vm.sheet); assertNull(vm.form.pBond?.takeIf { it.cur == "USD" })
    }

    @Test fun autoCouponsToAccount() {
        val vm = androidx.lifecycle.ViewModelProvider(rule.activity)[BellaViewModel::class.java]
        val b = registry().bonds.first { it.isin == "UA4000239149" }
        rule.runOnUiThread { vm.seedNbu(registry()); vm.openPos(); vm.openOvdpPick(); vm.pickBond(b) }
        val acc = vm.defaultPayAcc("UAH")
        rule.runOnUiThread { vm.form = vm.form.copy(pQty = "10", pBuy = "985", pAuto = true, pPayAcc = acc) }
        show(vm) { PosForm(vm, false) }
        shot("14-ovdp-auto-coupons")
        rule.runOnUiThread { vm.submit() }
        val p = vm.data.pos.last()
        assertTrue(p.autoCpn); assertEquals(TODAY, p.autoFrom); assertEquals(acc, p.payAcc)
        // без рахунку не зберігається
        rule.runOnUiThread { vm.openPos(); vm.form = vm.form.copy(pName = "ОВДП X", pQty = "1", pBuy = "1000", pAuto = true, pPayAcc = "немає") }
        val n = vm.data.pos.size
        rule.runOnUiThread { vm.submit() }
        assertEquals(n, vm.data.pos.size); assertEquals("Виберіть рахунок для купонів", vm.formErr)
    }

    @Test fun calculatorPicksFromRegistry() {
        val vm = androidx.lifecycle.ViewModelProvider(rule.activity)[BellaViewModel::class.java]
        val b = registry().bonds.first { it.isin == "UA4000236541" }
        rule.runOnUiThread { vm.seedNbu(registry()); vm.go(Screen.Calc); vm.openOvdpPick(forCalc = true) }
        assertEquals(Sheet.OvdpPick, vm.sheet)
        rule.runOnUiThread { vm.pickBond(b) }
        assertEquals(Sheet.None, vm.sheet)
        val c = vm.calcIn
        assertEquals("UA4000236541", c.isin); assertEquals("USD", c.cur); assertEquals("1000", c.nom); assertEquals("4.17", c.coupon); assertEquals(6, c.freq); assertEquals("2027-02-04", c.mat)
        assertTrue(c.sellD <= c.mat)
        // закриття вибору з калькулятора просто закриває вікно
        rule.runOnUiThread { vm.openOvdpPick(forCalc = true); vm.closeSheet() }
        assertEquals(Sheet.None, vm.sheet)
    }
}
