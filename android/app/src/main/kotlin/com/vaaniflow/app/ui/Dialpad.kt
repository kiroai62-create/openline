package com.vaaniflow.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaaniflow.app.data.PhoneData

val DIAL_KEYS = listOf("1" to "", "2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL", "6" to "MNO", "7" to "PQRS", "8" to "TUV", "9" to "WXYZ", "*" to "", "0" to "+", "#" to "")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialpadSheet(initial: String, contacts: List<PhoneData.Contact>, onCall: (String) -> Unit, onAddContact: (String) -> Unit, onDismiss: () -> Unit) {
    var number by remember { mutableStateOf(initial) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // T9 search: "2742" finds "Asha", and digits match numbers.
    val matches = remember(number, contacts) {
        val d = number.filter(Char::isDigit)
        if (d.length < 2) emptyList() else contacts.filter { c ->
            PhoneData.t9(c.name).split(' ').any { it.startsWith(d) } || c.numbers.any { it.number.filter(Char::isDigit).contains(d) }
        }.take(3)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Vaani.tokens.card, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 4.dp).size(width = 36.dp, height = 4.dp).clip(CircleShape).background(Vaani.tokens.hairlineStrong)) }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.fillMaxWidth().height(150.dp).padding(horizontal = 12.dp), verticalArrangement = Arrangement.Bottom) {
                matches.forEach { c ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onCall(c.numbers.first().number) }.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(c.name, c.numbers.first().number, size = 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(formatNumber(c.numbers.first().number), style = MaterialTheme.typography.bodySmall, color = Vaani.tokens.muted)
                        }
                        Icon(Icons.Rounded.Call, null, Modifier.size(18.dp), tint = Vaani.tokens.success)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp)) {
                    if (number.length >= 3 && matches.isEmpty()) IconButton(onClick = { onAddContact(number) }) { Icon(Icons.Rounded.PersonAdd, "Add contact", tint = Vaani.tokens.accent) }
                }
                Text(
                    formatNumber(number).ifEmpty { " " },
                    fontSize = if (number.length > 13) 28.sp else 36.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp, style = MaterialTheme.typography.displaySmall,
                    textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f),
                )
                Box(Modifier.size(48.dp)) {
                    if (number.isNotEmpty()) BackspaceButton({ number = number.dropLast(1) }, { number = "" })
                }
            }
            Spacer(Modifier.height(12.dp))
            Keypad(Modifier.padding(horizontal = 36.dp)) { number += it }
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier.size(width = 120.dp, height = 64.dp)
                    .shadow(if (number.isNotBlank()) 12.dp else 0.dp, RoundedCornerShape(32.dp), ambientColor = Brand.Green, spotColor = Brand.Green)
                    .clip(RoundedCornerShape(32.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF22C55E), Brand.Green)))
                    .clickable(enabled = number.isNotBlank()) { onCall(number) },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Call, "Call", tint = Color.White, modifier = Modifier.size(30.dp)) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BackspaceButton(onTap: () -> Unit, onLong: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).combinedClickable(onClick = onTap, onLongClick = onLong), contentAlignment = Alignment.Center) {
        Icon(Icons.AutoMirrored.Rounded.Backspace, "Delete", tint = Vaani.tokens.muted)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Keypad(modifier: Modifier = Modifier, dark: Boolean = false, onKey: (String) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DIAL_KEYS.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                row.forEach { (digit, letters) ->
                    Column(
                        Modifier.weight(1f).aspectRatio(1.45f).clip(CircleShape)
                            .background(if (dark) Color.White.copy(alpha = 0.07f) else Vaani.tokens.cardRaised)
                            .border(1.dp, if (dark) Color.White.copy(alpha = 0.08f) else Vaani.tokens.hairline, CircleShape)
                            .combinedClickable(
                                onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onKey(digit) },
                                onLongClick = { if (digit == "0") onKey("+") else onKey(digit) },
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        Text(digit, fontSize = 26.sp, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.headlineMedium, color = if (dark) Color.White else MaterialTheme.colorScheme.onSurface)
                        if (letters.isNotEmpty()) Text(letters, fontSize = 10.sp, letterSpacing = 1.5.sp, color = if (dark) Color.White.copy(alpha = 0.55f) else Vaani.tokens.subtle, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
