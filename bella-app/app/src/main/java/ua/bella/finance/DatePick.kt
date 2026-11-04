package ua.bella.finance

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** РРРР-ММ-ДД → мілісекунди UTC (так працює вибір дати Material); некоректний рядок → null. */
fun isoToPickerMillis(iso: String): Long? = iso.trim().takeIf { isIsoDate(it) }?.let { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }

/** Мілісекунди UTC з вибору дати → РРРР-ММ-ДД. */
fun pickerMillisToIso(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()

/**
 * Поле дати: можна вводити вручну (РРРР-ММ-ДД) або вибрати в календарі кнопкою праворуч.
 * Порожнє значення в календарі відкривається на сьогоднішній даті; «Очистити» прибирає дату.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, value: String, modifier: Modifier = Modifier, hint: String = "РРРР-ММ-ДД", onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        T(label, 12.sp, C.n500, modifier = Modifier.padding(bottom = 4.dp))
        OutlinedTextField(
            value, onChange, Modifier.fillMaxWidth(), singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = C.text),
            placeholder = { if (hint.isNotEmpty()) T(hint, 14.sp, C.n600) },
            trailingIcon = { IconBtn(Icons.Outlined.CalendarMonth, C.accent300) { open = true } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.accent, unfocusedBorderColor = C.divider, cursorColor = C.accent, focusedContainerColor = C.surface, unfocusedContainerColor = C.surface),
        )
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = isoToPickerMillis(value) ?: isoToPickerMillis(TODAY))
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { onChange(pickerMillisToIso(it)) }; open = false }) { Text("Вибрати") } },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (value.isNotBlank()) TextButton(onClick = { onChange(""); open = false }) { Text("Очистити") }
                    TextButton(onClick = { state.selectedDateMillis = isoToPickerMillis(TODAY) }) { Text("Сьогодні") }
                    TextButton(onClick = { open = false }) { Text("Скасувати") }
                }
            },
        ) { DatePicker(state) }
    }
}
