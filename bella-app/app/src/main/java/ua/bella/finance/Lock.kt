package ua.bella.finance

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

@Composable
fun LockOverlay(vm: BellaViewModel) {
    val ctx = LocalContext.current
    fun bio() {
        val act = ctx as? FragmentActivity ?: return
        if (BiometricManager.from(act).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) != BiometricManager.BIOMETRIC_SUCCESS) { vm.kerr = "Біометрія недоступна"; return }
        val p = BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { vm.unlockByBio() }
        })
        p.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Bella Finance").setSubtitle("Підтвердіть особу").setNegativeButtonText("Скасувати").setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK).build())
    }
    val unlock = vm.kmode == KMode.Unlock
    LaunchedEffect(unlock) { if (unlock && vm.sec.bio) bio() }
    val title = when (vm.kmode) { KMode.Unlock -> "Введіть PIN-код"; KMode.Old -> "Введіть поточний PIN"; KMode.New -> "Створіть PIN-код"; KMode.Confirm -> "Повторіть PIN-код"; else -> "" }
    Column(Modifier.fillMaxSize().background(C.bg).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Ico(Icons.Outlined.Lock, C.accent, 36.dp)
        T(title, 18.sp, weight = androidx.compose.ui.text.font.FontWeight.Medium, modifier = Modifier.padding(top = 14.dp))
        T(vm.kerr.ifEmpty { if (unlock) "Bella Finance заблоковано" else "4 цифри" }, 12.sp, if (vm.kerr.isNotEmpty()) C.neg else C.n500, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.padding(vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(4) { i -> Box(Modifier.size(14.dp).clip(CircleShape).border(1.5.dp, C.accent, CircleShape).background(if (i < vm.entry.length) C.accent else Color.Transparent)) }
        }
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("bio", "0", "del")).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                row.forEach { k ->
                    val vis = k != "bio" || (unlock && vm.sec.bio)
                    Box(Modifier.size(68.dp).clip(CircleShape).then(if (k.length == 1 && vis) Modifier.background(C.surface) else Modifier)
                        .clickable(enabled = vis) { when (k) { "bio" -> bio(); "del" -> vm.backspace(); else -> vm.press(k) } }, contentAlignment = Alignment.Center) {
                        when { !vis -> {}; k == "bio" -> Ico(Icons.Outlined.Fingerprint, C.accent, 28.dp); k == "del" -> Ico(Icons.AutoMirrored.Outlined.Backspace, C.n500, 24.dp); else -> T(k, 24.sp) }
                    }
                }
            }
        }
        if (vm.kmode != KMode.Unlock) T("Скасувати", 13.sp, C.accent400, modifier = Modifier.padding(top = 18.dp).clickable { vm.cancelPin() })
    }
}
