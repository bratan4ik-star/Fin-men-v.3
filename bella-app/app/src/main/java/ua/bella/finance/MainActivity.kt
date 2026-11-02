package ua.bella.finance

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.fragment.app.FragmentActivity

class MainActivity : FragmentActivity() {
    private val vm: BellaViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BellaTheme(vm.light) { BellaApp(vm) } }
    }
    override fun onStop() { super.onStop(); vm.onStopped() }
    override fun onStart() { super.onStart(); vm.onStarted() }
}

@Composable
fun BellaApp(vm: BellaViewModel) {
    BackHandler(enabled = vm.screen != Screen.Home && vm.kmode == KMode.None) { vm.go(Screen.Home) }
    Box(Modifier.fillMaxSize().background(C.bg).statusBarsPadding().navigationBarsPadding()) {
        when (vm.screen) {
            Screen.Home -> HomeScreen(vm); Screen.Acc -> AccScreen(vm); Screen.Bud -> BudgetScreen(vm); Screen.Inv -> InvScreen(vm)
            Screen.Goals -> GoalsScreen(vm); Screen.Pass -> PassScreen(vm); Screen.Calc -> CalcScreen(vm)
        }
        if (vm.toastAt > 0) Row(
            Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.surface).padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) { T("Зміни збережено", 13.sp); T("Скасувати", 13.sp, C.accent, FontWeight.Medium, Modifier.clickable { vm.undo() }) }
        SheetHost(vm)
        if (vm.kmode != KMode.None) LockOverlay(vm)
    }
}
