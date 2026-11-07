package ua.bella.finance

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Збережені дані є, але не розшифровуються (ключ у Keystore втрачено, дані пошкоджено). Порожній стан мовчки
 * не створюється: тут можна відновити дані з автокопії чи файлу, спробувати ще раз або свідомо почати з нуля.
 */
@Composable
fun RecoveryScreen(vm: BellaViewModel) {
    val openCopy = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.readBackup(uri) }
    Column(Modifier.fillMaxSize().background(C.bg).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Ico(Icons.Outlined.ErrorOutline, C.neg, 36.dp)
        T("Не вдалося відкрити збережені дані", 18.sp, weight = FontWeight.Medium)
        T("Дані на телефоні є, але розшифрувати їх не вдалося (${vm.storeBroken}). Так буває після скидання захищеного сховища ключів або відновлення телефону з резервної копії системи. Застосунок нічого не змінював і не видаляв.", 13.sp, C.n400)
        T("Відновіть дані з резервної копії. Після відновлення PIN-код потрібно буде створити заново: він у копію не потрапляє.", 13.sp, C.n400)
        if (vm.autoDirName.isNotEmpty()) Btn("Відновити з останньої автокопії", true, Modifier.fillMaxWidth()) { vm.restoreFromAuto() }
        Btn("Вибрати файл копії", vm.autoDirName.isEmpty(), Modifier.fillMaxWidth()) { openCopy.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
        RestorePrompts(vm)
        if (vm.backupMsg.isNotEmpty()) T(vm.backupMsg, 12.sp, if (vm.backupMsg.startsWith("Не") || vm.backupMsg.startsWith("Це не") || vm.backupMsg.startsWith("Автокопію не") || vm.backupMsg.startsWith("Дані все")) C.neg else C.n500)
        Btn("Спробувати ще раз", false, Modifier.fillMaxWidth()) { vm.retryLoad() }
        Spacer(Modifier.height(8.dp))
        T(if (vm.freshAsk) "Натисніть ще раз: застосунок почне з порожніми даними. Старий запис не видаляється, а відкладається." else "Почати з порожніми даними", 13.sp, C.neg,
            modifier = Modifier.align(Alignment.CenterHorizontally).clickable { vm.startFresh() }.padding(vertical = 6.dp))
    }
}
