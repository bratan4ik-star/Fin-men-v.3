package ua.bella.finance

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Phosphor-іконки прототипу замінені на Material (Phosphor недоступний у Compose без стороннього пакета). */
fun catIcon(cat: String): ImageVector = when (cat) {
    "Перекази" -> Icons.Outlined.Send; "Надходження" -> Icons.Outlined.SouthWest; "Зняття готівки", "Зарплата" -> Icons.Outlined.Payments
    "Послуги" -> Icons.Outlined.Build; "Поповнення мобільного" -> Icons.Outlined.PhoneAndroid; "Побутова техніка" -> Icons.Outlined.Power
    "Харчування" -> Icons.Outlined.ShoppingCart; "Житло" -> Icons.Outlined.Home; "Транспорт" -> Icons.Outlined.DirectionsCar
    "Розваги" -> Icons.Outlined.Movie; "Здоров'я" -> Icons.Outlined.MonitorHeart; else -> Icons.Outlined.MoreHoriz
}
fun goalIcon(k: String): ImageVector = when (k) { "shield" -> Icons.Outlined.VerifiedUser; "plane" -> Icons.Outlined.Flight; "laptop" -> Icons.Outlined.Laptop; else -> Icons.Outlined.Flag }

@Composable
fun T(text: String, size: TextUnit = 14.sp, color: Color = C.text, weight: FontWeight = FontWeight.Normal, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, strike: Boolean = false, letter: TextUnit = TextUnit.Unspecified) =
    Text(text, modifier, color = color, fontSize = size, fontWeight = weight, maxLines = maxLines, overflow = TextOverflow.Ellipsis, letterSpacing = letter,
        textDecoration = if (strike) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)

@Composable fun Label(text: String) = T(text, 11.sp, C.n500, letter = .08.sp)
@Composable fun Div() = HorizontalDivider(color = C.divider, thickness = 1.dp)
@Composable fun Dot(c: Color) = Box(Modifier.size(6.dp).clip(CircleShape).background(c))

@Composable
fun Ico(i: ImageVector, c: Color = C.accent, size: Dp = 20.dp, modifier: Modifier = Modifier) = Icon(i, null, modifier.size(size), tint = c)

@Composable
fun IconBtn(i: ImageVector, c: Color = C.accent, onClick: () -> Unit) =
    Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) { Ico(i, c) }

@Composable
fun Bar(frac: Float, color: Color = C.accent, h: Dp = 3.dp, modifier: Modifier = Modifier) =
    Box(modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(2.dp)).background(C.divider)) { Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(h).background(color)) }

@Composable
fun Btn(text: String, primary: Boolean, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier.clip(shape).then(if (primary) Modifier.background(C.accent) else Modifier.border(BorderStroke(1.dp, C.divider), shape)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Ico(icon, if (primary) C.bg else C.accent, 16.dp); Spacer(Modifier.width(6.dp)) }
        T(text, 14.sp, if (primary) C.bg else C.text, FontWeight.Medium, maxLines = 1)
    }
}

/** Сегментований вибір («чіпси» прототипу). */
@Composable
fun <K> Seg(options: List<Pair<K, String>>, value: K, modifier: Modifier = Modifier, onPick: (K) -> Unit) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, C.divider, RoundedCornerShape(8.dp)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEach { (k, l) ->
            val sel = k == value
            Box(Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(if (sel) C.accent800 else Color.Transparent).clickable { onPick(k) }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                T(l, 12.sp, if (sel) C.accent300 else C.n500, maxLines = 1)
            }
        }
    }
}

@Composable
fun Field(label: String, value: String, modifier: Modifier = Modifier, hint: String = "", numeric: Boolean = false, onChange: (String) -> Unit) {
    Column(modifier) {
        T(label, 12.sp, C.n500, modifier = Modifier.padding(bottom = 4.dp))
        OutlinedTextField(
            value, onChange, Modifier.fillMaxWidth(), singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = C.text),
            placeholder = { if (hint.isNotEmpty()) T(hint, 14.sp, C.n600) },
            keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text), shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.accent, unfocusedBorderColor = C.divider, cursorColor = C.accent, focusedContainerColor = C.surface, unfocusedContainerColor = C.surface),
        )
    }
}

/** Випадаючий список замість <select>. */
@Composable
fun Dropdown(label: String, options: List<String>, selected: Int, modifier: Modifier = Modifier, onPick: (Int) -> Unit) {
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Column(modifier) {
        T(label, 12.sp, C.n500, modifier = Modifier.padding(bottom = 4.dp))
        Box {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, C.divider, RoundedCornerShape(8.dp)).background(C.surface).clickable { open = true }.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                T(options.getOrElse(selected) { "—" }, 14.sp, modifier = Modifier.weight(1f), maxLines = 1); Ico(Icons.Outlined.ExpandMore, C.n500, 18.dp)
            }
            DropdownMenu(open, { open = false }, containerColor = C.surface) {
                options.forEachIndexed { i, o -> DropdownMenuItem(text = { T(o, 14.sp) }, onClick = { onPick(i); open = false }) }
            }
        }
    }
}

@Composable
fun TopBar(title: String, vm: BellaViewModel, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBtn(Icons.Outlined.ArrowBack, C.text) { vm.go(Screen.Home) }
        T(title, 18.sp, weight = FontWeight.Medium, modifier = Modifier.weight(1f).padding(start = 4.dp), maxLines = 1)
        trailing()
    }
}

@Composable
fun CurPicker(vm: BellaViewModel) {
    Box {
        Row(Modifier.clip(RoundedCornerShape(14.dp)).border(1.dp, C.divider, RoundedCornerShape(14.dp)).clickable { vm.curOpen = !vm.curOpen }.padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            T("${vm.sym} ${vm.cur}", 12.sp, C.accent300, maxLines = 1); Ico(if (vm.curOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, C.accent300, 14.dp)
        }
        DropdownMenu(vm.curOpen, { vm.curOpen = false }, containerColor = C.surface) {
            CURRENCIES.forEach { c -> DropdownMenuItem(text = { T("${SYM[c]}  $c", 13.sp, if (c == vm.cur) C.accent300 else C.text) }, onClick = { vm.setCur(c) }) }
        }
    }
}

@Composable
fun TxRowView(t: Tx, vm: BellaViewModel) {
    val tr = t.type == "transfer"; val inc = t.type == "income"
    val acc = vm.accLabel(t)
    Row(Modifier.fillMaxWidth().clickable { vm.editTx(t) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Ico(if (tr) Icons.Outlined.SwapHoriz else catIcon(t.cat), C.accent, 20.dp)
        Column(Modifier.weight(1f)) { T(t.desc, 14.sp, weight = FontWeight.Medium, maxLines = 1); T("${t.cat} · ${ddmm(t.date)}" + if (acc.isNotEmpty()) " · $acc" else "", 11.sp, C.n500, maxLines = 1) }
        T((if (tr) "⇄ " else if (inc) "+" else "−") + vm.m(t.amt) + " " + vm.sym, 14.sp, if (tr) C.n500 else if (inc) C.pos else C.text, FontWeight.Medium, maxLines = 1)
    }
}

@Composable
fun SectionHead(title: String, right: String? = null, rightColor: Color = C.accent400, onRight: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        T(title, 13.sp, weight = FontWeight.Medium, maxLines = 1)
        if (right != null) T(right, 12.sp, rightColor, modifier = if (onRight != null) Modifier.clickable(onClick = onRight) else Modifier, maxLines = 1)
    }
}
