package com.vaaniflow.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaaniflow.app.data.PhoneData

val DIAL_KEYS = listOf("1" to "", "2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL", "6" to "MNO", "7" to "PQRS", "8" to "TUV", "9" to "WXYZ", "*" to "", "0" to "+", "#" to "")

/** Google Phone keypad: suggestions on top, number, 4×3 keys and the green call button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialpadSheet(initial: String, contacts: List<PhoneData.Contact>, onCall: (String) -> Unit, onAddContact: (String) -> Unit, onDismiss: () -> Unit) {
    var number by remember { mutableStateOf(initial) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val cs = MaterialTheme.colorScheme
    val t = Vaani.tokens
    // T9 search: "2742" finds "Asha", and digits match numbers.
    val matches = remember(number, contacts) {
        val d = number.filter(Char::isDigit)
        if (d.length < 2) emptyList() else contacts.filter { c ->
            PhoneData.t9(c.name).split(' ').any { it.startsWith(d) } || c.numbers.any { it.number.filter(Char::isDigit).contains(d) }
        }.take(3)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = cs.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.fillMaxWidth().height(156.dp).padding(horizontal = 12.dp), verticalArrangement = Arrangement.Bottom) {
                matches.forEach { c ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onCall(c.numbers.first().number) }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(c.name, c.numbers.first().number, size = 36.dp)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(formatNumber(c.numbers.first().number), style = MaterialTheme.typography.bodyMedium, color = t.muted)
                        }
                    }
                }
                if (matches.isEmpty() && number.filter(Char::isDigit).length >= 3) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onAddContact(number) }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconTile(Icons.Rounded.PersonAdd, size = 36.dp)
                        Spacer(Modifier.width(16.dp))
                        Text("Create new contact", style = MaterialTheme.typography.bodyLarge, color = cs.primary)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.size(48.dp))
                Text(
                    formatNumber(number).ifEmpty { " " },
                    style = MaterialTheme.typography.displaySmall, fontSize = if (number.length > 13) 28.sp else 36.sp,
                    textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f),
                )
                Box(Modifier.size(48.dp)) {
                    if (number.isNotEmpty()) BackspaceButton({ number = number.dropLast(1) }, { number = "" })
                }
            }
            Spacer(Modifier.height(8.dp))
            Keypad(Modifier.padding(horizontal = 32.dp)) { number += it }
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier.size(width = 104.dp, height = 64.dp).clip(RoundedCornerShape(32.dp)).background(t.callGreen)
                    .clickable(enabled = number.isNotBlank()) { onCall(number) },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Call, "Call", tint = if (t.dark) Color(0xFF0A3818) else Color.White, modifier = Modifier.size(28.dp)) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BackspaceButton(onTap: () -> Unit, onLong: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).combinedClickable(onClick = onTap, onLongClick = onLong), contentAlignment = Alignment.Center) {
        Icon(Icons.AutoMirrored.Outlined.Backspace, "Backspace", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 4×3 keys; [onDark] colours them for a dark backdrop. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Keypad(modifier: Modifier = Modifier, onDark: Boolean = false, onKey: (String) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val cs = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DIAL_KEYS.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (digit, letters) ->
                    Column(
                        Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(32.dp))
                            .background(if (onDark) Color.White.copy(alpha = 0.10f) else cs.surfaceContainerHighest)
                            .combinedClickable(
                                onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onKey(digit) },
                                onLongClick = { if (digit == "0") onKey("+") else onKey(digit) },
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        Text(digit, style = MaterialTheme.typography.headlineMedium, color = if (onDark) Color.White else cs.onSurface)
                        if (letters.isNotEmpty()) Text(letters, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, color = if (onDark) Color.White.copy(alpha = 0.7f) else cs.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
