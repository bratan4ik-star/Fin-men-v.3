package ua.bella.finance

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp

/**
 * Знімки основних екранів на демо-даних (телефон 411×891 dp). PNG-файли — у app/build/screenshots,
 * у CI їх видно в артефакті bella-app-screenshots. Тест падає, якщо екран не малюється (виняток у Compose).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val out = File("build/screenshots").apply { mkdirs() }

    private fun vm(): BellaViewModel = androidx.lifecycle.ViewModelProvider(rule.activity)[BellaViewModel::class.java]

    private fun shot(name: String) = saveShot(rule, out, name)

    @Test fun mainScreens() {
        val vm = vm()
        shot("01-home")
        listOf(Screen.Acc to "02-accounts", Screen.Inv to "04-investments", Screen.Goals to "05-goals", Screen.Pass to "06-passive", Screen.Calc to "07-ovdp")
            .forEach { (s, n) -> rule.runOnUiThread { vm.go(s) }; shot(n) }
        listOf(0 to "overview", 1 to "operations", 2 to "report", 3 to "limits").forEach { (t, n) ->
            rule.runOnUiThread { vm.go(Screen.Bud); vm.tab = t }; shot("03-budget-$n")
        }
    }
}

/** Знімок поточного кореня Compose у PNG. */
fun saveShot(rule: androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, out androidx.activity.ComponentActivity>, out: File, name: String) {
    rule.waitForIdle()
    rule.onRoot().assertExists()
    val v = rule.activity.window.decorView
    val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
    rule.runOnUiThread { v.draw(android.graphics.Canvas(bmp)) }
    File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
}

/** Вікно «Графіки IBKR» на прикладі рахунку (малюється напряму, без нижньої шторки: Robolectric не знімає спливні вікна). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h1600dp-xxhdpi")
class IbkrChartsShotTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private fun shot(name: String) = saveShot(rule, File("build/screenshots").apply { mkdirs() }, name)

    @Test fun ibkrCharts() {
        val vm = androidx.lifecycle.ViewModelProvider(rule.activity)[BellaViewModel::class.java]
        val start = LocalDate.parse("2026-08-20")
        val nav = (0..78).map { HistPoint(start.plusDays(it.toLong()).toString(), if (it < 25) 110.0 - it * 0.1 else if (it < 40) 300.0 - (it - 25) * 1.5 else 620.0 + (it % 7) - 3) }
        rule.runOnUiThread {
            vm.commit(vm.data.copy(
                accs = vm.data.accs.filterNot { it.id.startsWith("ibkr-") } + Account("IBKR USD", "USD", 31.89, id = "ibkr-USD"),
                pos = vm.data.pos.filterNot { it.src == "ibkr" } + listOf(
                    Pos("ETF", "VOO", 0.7332, 0.0, 0.0, cur = "USD", pxBuy = 702.95, pxNow = 680.56, buyFx = 41.2, src = "ibkr", ibSym = "VOO"),
                    Pos("ETF", "VEA", 1.114, 0.0, 0.0, cur = "USD", pxBuy = 72.25, pxNow = 68.08, buyFx = 41.2, src = "ibkr", ibSym = "VEA"))))
            vm.seedIbkrHistory(nav, listOf(IbFlow("1", "2026-09-14", 193.0), IbFlow("2", "2026-09-28", 347.0)), px = mapOf(
                "VOO" to (0..78).map { HistPoint(start.plusDays(it.toLong()).toString(), 700.0 - it * 0.3 + (it % 5)) },
                "VEA" to (0..78).map { HistPoint(start.plusDays(it.toLong()).toString(), 72.0 - it * 0.05 + (it % 3) * 0.2) }))
            vm.ibPeriod = "3M"
        }
        rule.setContent {
            BellaTheme(vm.light) {
                androidx.compose.foundation.layout.Column(
                    androidx.compose.ui.Modifier.fillMaxSize().background(C.bg).padding(16.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp)) { IbkrChartsView(vm) }
            }
        }
        shot("08-ibkr-charts")
    }

    @Test fun investmentsWithIbkrCard() {
        val vm = androidx.lifecycle.ViewModelProvider(rule.activity)[BellaViewModel::class.java]
        val start = LocalDate.parse("2026-09-01")
        rule.runOnUiThread {
            vm.commit(vm.data.copy(pos = vm.data.pos.filterNot { it.src == "ibkr" } + Pos("ETF", "VOO", 0.7332, 0.0, 0.0, cur = "USD", pxBuy = 702.95, pxNow = 680.56, buyFx = 41.2, src = "ibkr", ibSym = "VOO")))
            vm.seedIbkrHistory((0..66).map { HistPoint(start.plusDays(it.toLong()).toString(), 500.0 + it % 9) }, emptyList())
        }
        rule.setContent { BellaTheme(vm.light) { InvScreen(vm) } }
        shot("09-investments-ibkr-card")
    }
}
