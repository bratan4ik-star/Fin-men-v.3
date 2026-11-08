package ua.bella.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Вкладка «Порівняти» на збережених відповідях НБУ: два випуски, драбина, «Додати в портфель». */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h2400dp-xxhdpi")
class OvdpCompareShotTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private fun res(n: String) = javaClass.classLoader!!.getResource("nbu/$n")!!.readText()

    @Test fun compareTwoIssues() {
        val vm = androidx.lifecycle.ViewModelProvider(rule.activity)[BellaViewModel::class.java]
        val reg = refreshRegistry(OvdpRegistry(), java.time.LocalDate.parse(TODAY), System.currentTimeMillis()) { url ->
            when { url == NBU_DEPO_URL -> res("depo_securities_sample.json"); url == NBU_AUCTION_URL -> res("ovdp_2026-11-03.json"); else -> "[]" }
        }.first
        rule.runOnUiThread {
            vm.seedNbu(reg); vm.cmpAmount = "338000"
            vm.openOvdpPick(forCompare = true); vm.pickBond(reg.bonds.first { it.isin == "UA4000239131" })
            vm.openOvdpPick(forCompare = true); vm.pickBond(reg.bonds.first { it.isin == "UA4000239149" })
            vm.cmpSetPrice(0, "1036.58"); vm.cmpSetPrice(1, "1050.68")
        }
        assertEquals(Sheet.None, vm.sheet); assertEquals(2, vm.cmp.size)
        rule.runOnUiThread { vm.cmpAdd(reg.bonds.first { it.isin == "UA4000239149" }) }
        assertTrue(vm.cmpMsg.contains("уже в порівнянні"))
        val res = vm.cmpResults()
        assertEquals(listOf("UA4000239149", "UA4000239131", "Драбина"), res.map { it.title })
        rule.setContent {
            BellaTheme(vm.light) { Column(Modifier.fillMaxSize().background(C.bg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { OvdpCompareTab(vm) } }
        }
        saveShot(rule, File("build/screenshots").apply { mkdirs() }, "15-ovdp-compare")
        // «Додати в портфель»: форма нової позиції з випуском, кількістю й ціною
        rule.runOnUiThread { vm.cmpToPortfolio(res[0]) }
        assertEquals(Sheet.Pos, vm.sheet)
        assertEquals("UA4000239149", vm.form.pBond?.isin); assertEquals(res[0].legs.single().qty.toString(), vm.form.pQty)
        assertEquals(1050.68, parseNum(vm.form.pBuy) * vm.rate, 1e-6)
        // податок зберігається
        rule.runOnUiThread { vm.setOvdpTax("5") }
        assertEquals(5.0, vm.ovdpTax, 0.0)
    }
}
