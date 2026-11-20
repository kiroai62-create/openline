package com.vaaniflow.app.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
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

@Composable
fun AssistantScreen(state: HomeState, actions: HomeActions) {
    val s = state.setup
    val ai = state.ai
    fun save(next: AiSettings.Settings) = actions.saveAi(next)
    val stepsDone = listOf(s.isDefault, s.paired && s.connected, s.audioDone, s.batteryDone).count { it }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        MainSwitch(state, ::save)

        if (stepsDone < 4) SetupSection(state, actions, stepsDone)

        SectionLabel("When the AI answers")
        val modes = AiSettings.Mode.entries
        SegmentedGroup(modes.size + if (ai.mode == AiSettings.Mode.RING_FIRST) 1 else 0) { i ->
            if (i < modes.size) {
                val m = modes[i]
                Row(Modifier.fillMaxWidth().clickable { save(ai.copy(mode = m)) }.padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = ai.mode == m, onClick = { save(ai.copy(mode = m)) })
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            when (m) {
                                AiSettings.Mode.RING_FIRST -> "You get a chance to answer. Missed calls go to the AI."
                                AiSettings.Mode.INSTANT -> "Best for a dedicated business phone."
                                AiSettings.Mode.OFF -> "Works as a normal phone app."
                            },
                            style = MaterialTheme.typography.bodyMedium, color = Vaani.tokens.muted,
                        )
                    }
                }
            } else {
                var seconds by remember(ai.ringSeconds) { mutableStateOf(ai.ringSeconds.toFloat()) }
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Row {
                        Text("Ring first for", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text("${seconds.toInt()} seconds", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(value = seconds, onValueChange = { seconds = it }, valueRange = 5f..40f, steps = 6, onValueChangeFinished = { save(ai.copy(ringSeconds = seconds.toInt())) })
                }
            }
        }

        SectionLabel("Personal calls")
        val multiSim = state.accounts.size > 1
        SegmentedGroup(if (multiSim) 2 else 1) { i ->
            if (i == 0) {
                SwitchRow("Saved contacts ring normally", "Family and friends never reach the AI", ai.skipContacts) { save(ai.copy(skipContacts = it)) }
            } else {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 12.dp)) {
                    Text("SIM the AI handles", style = MaterialTheme.typography.bodyLarge)
                    Text("Calls on the other SIM ring normally.", style = MaterialTheme.typography.bodyMedium, color = Vaani.tokens.muted)
                    Spacer(Modifier.height(8.dp))
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = ai.aiSimId.isBlank(), onClick = { save(ai.copy(aiSimId = "")) }, label = { Text("All SIMs") })
                        state.accounts.forEach { (id, label) -> FilterChip(selected = ai.aiSimId == id, onClick = { save(ai.copy(aiSimId = id)) }, label = { Text(label) }) }
                    }
                }
            }
        }

        SectionLabel("Business hours")
        SegmentedGroup(if (ai.hoursEnabled) 3 else 1) { i ->
            when (i) {
                0 -> SwitchRow("Answer instantly when closed", "Outside business hours the AI picks up without ringing", ai.hoursEnabled) { save(ai.copy(hoursEnabled = it)) }
                1 -> Row {
                    ListRow("Opens", clock(ai.openMinute), Modifier.weight(1f).clickable { actions.setup.pickTime(ai.openMinute) { save(ai.copy(openMinute = it)) } })
                    ListRow("Closes", clock(ai.closeMinute), Modifier.weight(1f).clickable { actions.setup.pickTime(ai.closeMinute) { save(ai.copy(closeMinute = it)) } })
                }
                else -> {
                    val days = listOf(2 to "M", 3 to "T", 4 to "W", 5 to "T", 6 to "F", 7 to "S", 1 to "S")
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        days.forEach { (d, label) ->
                            val on = d in ai.openDays
                            val cs = MaterialTheme.colorScheme
                            Box(
                                Modifier.size(40.dp).clip(CircleShape).background(if (on) cs.primary else cs.surfaceContainerHighest)
                                    .clickable { save(ai.copy(openDays = if (on) ai.openDays - d else ai.openDays + d)) },
                                contentAlignment = Alignment.Center,
                            ) { Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) cs.onPrimary else cs.onSurfaceVariant) }
                        }
                    }
                }
            }
        }

        if (stepsDone == 4) SetupSection(state, actions, stepsDone)

        var advanced by remember { mutableStateOf(false) }
        Spacer(Modifier.height(16.dp))
        Segment {
            ListRow(
                "Advanced", "Server address and device details", Modifier.clickable { advanced = !advanced },
                trailing = { Icon(if (advanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = Vaani.tokens.muted) },
            )
            AnimatedVisibility(advanced) {
                var ws by remember(s.wsUrl) { mutableStateOf(s.wsUrl) }
                var id by remember(s.deviceId) { mutableStateOf(s.deviceId) }
                var token by remember(s.deviceToken) { mutableStateOf(s.deviceToken) }
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(ws, { ws = it }, label = { Text("Server channel URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(id, { id = it }, label = { Text("Device ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(token, { token = it }, label = { Text("Device token") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = actions.setup.restartAudio, modifier = Modifier.weight(1f)) { Text("Restart audio") }
                        Button(onClick = { actions.setup.saveAdvanced(ws.trim(), id.trim(), token.trim()) }, modifier = Modifier.weight(1f)) { Text("Save") }
                    }
                }
            }
        }
    }
}

/** Big on/off card at the top, like "Use Call Screen" in Google Phone settings. */
@Composable
private fun MainSwitch(state: HomeState, save: (AiSettings.Settings) -> Unit) {
    val s = state.setup
    val ai = state.ai
    val on = ai.mode != AiSettings.Mode.OFF
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(28.dp), color = if (on) cs.primaryContainer else cs.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clickable { save(ai.copy(mode = if (on) AiSettings.Mode.OFF else AiSettings.Mode.RING_FIRST)) },
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Brand.gradient), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Use AI receptionist", style = MaterialTheme.typography.titleLarge, color = if (on) cs.onPrimaryContainer else cs.onSurface)
                    Text(
                        when {
                            s.pairingMessage != null -> s.pairingMessage
                            !s.paired -> "Not connected yet"
                            s.connected -> "Connected to ${s.host}"
                            else -> "Can't reach ${s.host}. Retrying…"
                        },
                        style = MaterialTheme.typography.bodyMedium, color = if (on) cs.onPrimaryContainer.copy(alpha = 0.8f) else cs.onSurfaceVariant,
                    )
                }
                Switch(checked = on, onCheckedChange = { save(ai.copy(mode = if (it) AiSettings.Mode.RING_FIRST else AiSettings.Mode.OFF)) })
            }
            Spacer(Modifier.height(14.dp))
            Text(
                when (ai.mode) {
                    AiSettings.Mode.OFF -> "Every call rings on this phone. The AI doesn't answer."
                    AiSettings.Mode.INSTANT -> "The AI answers incoming calls at once${if (ai.skipContacts) ", except saved contacts" else ""}."
                    AiSettings.Mode.RING_FIRST -> "Calls ring for ${ai.ringSeconds} seconds. If you don't pick up, the AI answers${if (ai.skipContacts) ". Saved contacts always ring normally" else ""}."
                },
                style = MaterialTheme.typography.bodyMedium, color = if (on) cs.onPrimaryContainer else cs.onSurfaceVariant,
            )
            if (s.licence != null) {
                Spacer(Modifier.height(8.dp))
                Text(s.licence, style = MaterialTheme.typography.labelMedium, color = if (s.licenceValid) (if (on) cs.onPrimaryContainer.copy(alpha = 0.7f) else cs.onSurfaceVariant) else cs.error)
            }
        }
    }
}

@Composable
private fun SetupSection(state: HomeState, actions: HomeActions, done: Int) {
    val s = state.setup
    SectionLabel("Setup", trailing = "$done of 4")
    LinearProgressIndicator(
        progress = { done / 4f }, strokeCap = StrokeCap.Round,
        modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, bottom = 12.dp).height(6.dp),
    )
    SegmentedGroup(4) { i ->
        when (i) {
            0 -> Step(
                Icons.Rounded.PhoneAndroid, "Default phone app",
                when {
                    s.isDefault -> "This app handles your calls"
                    state.dialerBlocked -> "Android blocked it. Allow restricted settings first."
                    else -> "Needed to make and answer calls"
                },
                s.isDefault, if (s.isDefault) null else if (state.dialerBlocked) "Fix" else "Set",
            ) { if (state.dialerBlocked) actions.dialerHelp(true) else actions.setup.setDefault() }
            1 -> Step(Icons.Rounded.QrCodeScanner, "Connect to your workspace", when {
                !s.paired -> "Scan the QR code on the dashboard's Phones page"
                s.connected -> "Connected to ${s.host}"
                else -> "${s.host} is not reachable right now"
            }, s.paired && s.connected, if (s.paired) "Rescan" else "Scan", actions.setup.scanQr)
            2 -> Step(Icons.Rounded.GraphicEq, "AI call audio", s.audioText, s.audioDone, s.audioAction, actions.setup.audio)
            else -> Step(Icons.Rounded.BatteryChargingFull, "Stay online in the background", s.batteryText, s.batteryDone, s.batteryAction, actions.setup.battery)
        }
    }
}

@Composable
private fun SwitchRow(title: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = Vaani.tokens.muted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Step(icon: ImageVector, title: String, text: String, done: Boolean, action: String?, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (done) IconTile(Icons.Rounded.Check, tint = cs.onTertiaryContainer, background = cs.tertiaryContainer)
        else IconTile(icon)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = Vaani.tokens.muted)
        }
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            if (done) OutlinedButton(onClick = onClick) { Text(action) } else FilledTonalButton(onClick = onClick) { Text(action) }
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
