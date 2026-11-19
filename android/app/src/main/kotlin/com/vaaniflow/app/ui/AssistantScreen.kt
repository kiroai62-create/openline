package com.vaaniflow.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vaaniflow.app.ai.AiSettings

/** Status of the four setup steps and the connection, refreshed by the activity. */
data class SetupState(
    val isDefault: Boolean = false,
    val paired: Boolean = false,
    val connected: Boolean = false,
    val host: String = "",
    val pairingMessage: String? = null,
    val licence: String? = null,
    val licenceValid: Boolean = true,
    val audioDone: Boolean = false,
    val audioText: String = "Checking…",
    val audioAction: String? = null,
    val batteryDone: Boolean = false,
    val batteryText: String = "",
    val batteryAction: String? = null,
    val wsUrl: String = "",
    val deviceId: String = "",
    val deviceToken: String = "",
)

class SetupActions(
    val setDefault: () -> Unit,
    val scanQr: () -> Unit,
    val audio: () -> Unit,
    val battery: () -> Unit,
    val saveAdvanced: (String, String, String) -> Unit,
    val restartAudio: () -> Unit,
    val pickTime: (initialMinute: Int, onPicked: (Int) -> Unit) -> Unit,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AssistantScreen(state: HomeState, actions: HomeActions) {
    val s = state.setup
    val ai = state.ai
    fun save(next: AiSettings.Settings) = actions.saveAi(next)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        // Hero status card
        Box(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Brand.gradient).padding(20.dp)) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("VaaniFlow AI", color = Color.White, style = MaterialTheme.typography.titleLarge)
                        Text(
                            when {
                                s.pairingMessage != null -> s.pairingMessage
                                !s.paired -> "Not connected yet"
                                s.connected -> "Online · ${s.host}"
                                else -> "Can't reach ${s.host}. Retrying…"
                            },
                            color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = ai.mode != AiSettings.Mode.OFF,
                        onCheckedChange = { on -> save(ai.copy(mode = if (on) AiSettings.Mode.RING_FIRST else AiSettings.Mode.OFF)) },
                    )
                }
                if (s.licence != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(s.licence, color = if (s.licenceValid) Color.White else Color(0xFFFFD1D1), style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    when (ai.mode) {
                        AiSettings.Mode.OFF -> "Every call rings on this phone. The AI doesn't answer."
                        AiSettings.Mode.INSTANT -> "The AI answers incoming calls at once${if (ai.skipContacts) ", except saved contacts" else ""}."
                        AiSettings.Mode.RING_FIRST -> "Calls ring for ${ai.ringSeconds} seconds. If you don't pick up, the AI answers${if (ai.skipContacts) ". Saved contacts always ring normally" else ""}."
                    },
                    color = Color.White, style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // When the AI answers
        SectionLabel("When the AI answers")
        Card {
            AiSettings.Mode.entries.forEach { m ->
                Row(Modifier.fillMaxWidth().clickable { save(ai.copy(mode = m)) }.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = ai.mode == m, onClick = { save(ai.copy(mode = m)) })
                    Column(Modifier.weight(1f)) {
                        Text(m.label, style = MaterialTheme.typography.titleSmall)
                        Text(
                            when (m) {
                                AiSettings.Mode.RING_FIRST -> "You get a chance to answer; missed calls go to the AI"
                                AiSettings.Mode.INSTANT -> "Best for a dedicated business phone"
                                AiSettings.Mode.OFF -> "Use VaaniFlow as a normal phone app"
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (ai.mode == AiSettings.Mode.RING_FIRST) {
                var seconds by remember(ai.ringSeconds) { mutableStateOf(ai.ringSeconds.toFloat()) }
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text("Ring for ${seconds.toInt()} seconds first", style = MaterialTheme.typography.bodyMedium)
                    Slider(value = seconds, onValueChange = { seconds = it }, valueRange = 5f..40f, steps = 6, onValueChangeFinished = { save(ai.copy(ringSeconds = seconds.toInt())) })
                }
            }
        }

        SectionLabel("Keep personal calls personal")
        Card {
            SwitchRow("Saved contacts always ring normally", "Family and friends never reach the AI", ai.skipContacts) { save(ai.copy(skipContacts = it)) }
            if (state.accounts.size > 1) {
                Text("SIM the AI handles", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 20.dp, top = 12.dp))
                FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = ai.aiSimId.isBlank(), onClick = { save(ai.copy(aiSimId = "")) }, label = { Text("All SIMs") })
                    state.accounts.forEach { (id, label) -> FilterChip(selected = ai.aiSimId == id, onClick = { save(ai.copy(aiSimId = id)) }, label = { Text(label) }) }
                }
                Text("Calls on the other SIM ring normally and never go to the AI.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp))
            }
        }

        SectionLabel("Business hours")
        Card {
            SwitchRow("Answer instantly when closed", "Outside these hours the AI picks up without ringing", ai.hoursEnabled) { save(ai.copy(hoursEnabled = it)) }
            if (ai.hoursEnabled) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { actions.setup.pickTime(ai.openMinute) { save(ai.copy(openMinute = it)) } }) { Text("Opens ${clock(ai.openMinute)}") }
                    OutlinedButton(onClick = { actions.setup.pickTime(ai.closeMinute) { save(ai.copy(closeMinute = it)) } }) { Text("Closes ${clock(ai.closeMinute)}") }
                }
                val days = listOf(2 to "Mon", 3 to "Tue", 4 to "Wed", 5 to "Thu", 6 to "Fri", 7 to "Sat", 1 to "Sun")
                FlowRow(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    days.forEach { (d, label) ->
                        FilterChip(selected = d in ai.openDays, onClick = { save(ai.copy(openDays = if (d in ai.openDays) ai.openDays - d else ai.openDays + d)) }, label = { Text(label) })
                    }
                }
            }
        }

        SectionLabel("Setup")
        Card {
            Step(Icons.Rounded.PhoneAndroid, "Default phone app", if (s.isDefault) "VaaniFlow handles this phone's calls" else "Needed to place and answer calls", s.isDefault, if (s.isDefault) null else "Set", actions.setup.setDefault)
            Step(Icons.Rounded.QrCodeScanner, "Connect to your workspace", when {
                !s.paired -> "Scan the QR code from the dashboard (Phones page)"
                s.connected -> "Connected to ${s.host}"
                else -> "${s.host} is not reachable right now"
            }, s.paired && s.connected, if (s.paired) "Scan again" else "Scan QR", actions.setup.scanQr)
            Step(Icons.Rounded.GraphicEq, "AI call audio", s.audioText, s.audioDone, s.audioAction, actions.setup.audio)
            Step(Icons.Rounded.BatteryChargingFull, "Stay online in the background", s.batteryText, s.batteryDone, s.batteryAction, actions.setup.battery)
        }

        var advanced by remember { mutableStateOf(false) }
        TextButton(onClick = { advanced = !advanced }, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Text(if (advanced) "Hide advanced settings" else "Advanced settings")
            Icon(if (advanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
        }
        if (advanced) {
            var ws by remember(s.wsUrl) { mutableStateOf(s.wsUrl) }
            var id by remember(s.deviceId) { mutableStateOf(s.deviceId) }
            var token by remember(s.deviceToken) { mutableStateOf(s.deviceToken) }
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(ws, { ws = it }, label = { Text("Server channel URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(id, { id = it }, label = { Text("Device ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(token, { token = it }, label = { Text("Device token") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { actions.setup.saveAdvanced(ws.trim(), id.trim(), token.trim()) }) { Text("Save and reconnect") }
                        OutlinedButton(onClick = actions.setup.restartAudio) { Text("Restart audio") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest, tonalElevation = 0.dp, shadowElevation = 0.dp) {
        Column(Modifier.padding(vertical = 6.dp)) { content() }
    }
}

@Composable
private fun SwitchRow(title: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Step(icon: ImageVector, title: String, text: String, done: Boolean, action: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(if (done) Brand.Green.copy(alpha = 0.14f) else MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(if (done) Icons.Rounded.Check else icon, null, tint = if (done) Brand.Green else MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            Button(onClick = onClick) { Text(action) }
        }
    }
}

private fun clock(minute: Int): String {
    val h = minute / 60
    val m = minute % 60
    val suffix = if (h < 12) "am" else "pm"
    val h12 = if (h % 12 == 0) 12 else h % 12
    return if (m == 0) "$h12 $suffix" else "%d:%02d %s".format(h12, m, suffix)
}
