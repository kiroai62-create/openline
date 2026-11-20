package com.vaaniflow.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 40.dp)) {
        Hero(state, ::save)

        // Setup comes first until everything is done.
        if (stepsDone < 4) SetupCard(state, actions, stepsDone)

        SectionLabel("When the AI answers")
        VCard(Modifier.padding(horizontal = 16.dp)) {
            AiSettings.Mode.entries.forEachIndexed { i, m ->
                if (i > 0) CardDivider(56.dp)
                OptionRow(
                    m.label,
                    when (m) {
                        AiSettings.Mode.RING_FIRST -> "You get a chance to answer. Missed calls go to the AI."
                        AiSettings.Mode.INSTANT -> "Best for a dedicated business phone."
                        AiSettings.Mode.OFF -> "Use VaaniFlow as a normal phone app."
                    },
                    ai.mode == m,
                ) { save(ai.copy(mode = m)) }
            }
            AnimatedVisibility(ai.mode == AiSettings.Mode.RING_FIRST) {
                var seconds by remember(ai.ringSeconds) { mutableStateOf(ai.ringSeconds.toFloat()) }
                Column(Modifier.fillMaxWidth().background(Vaani.tokens.cardRaised).padding(horizontal = 18.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Ring first for", style = MaterialTheme.typography.bodyMedium, color = Vaani.tokens.muted, modifier = Modifier.weight(1f))
                        Text("${seconds.toInt()} sec", style = MaterialTheme.typography.titleSmall)
                    }
                    Slider(
                        value = seconds, onValueChange = { seconds = it }, valueRange = 5f..40f, steps = 6,
                        onValueChangeFinished = { save(ai.copy(ringSeconds = seconds.toInt())) },
                        colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = Vaani.tokens.accent, inactiveTrackColor = Vaani.tokens.hairlineStrong, activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
                    )
                }
            }
        }

        SectionLabel("Keep personal calls personal")
        VCard(Modifier.padding(horizontal = 16.dp)) {
            SwitchRow("Saved contacts ring normally", "Family and friends never reach the AI.", ai.skipContacts) { save(ai.copy(skipContacts = it)) }
            if (state.accounts.size > 1) {
                CardDivider()
                Column(Modifier.padding(16.dp)) {
                    Text("SIM the AI handles", style = MaterialTheme.typography.titleSmall, fontSize = 15.sp)
                    Spacer(Modifier.height(10.dp))
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("All SIMs", ai.aiSimId.isBlank()) { save(ai.copy(aiSimId = "")) }
                        state.accounts.forEach { (id, label) -> Chip(label, ai.aiSimId == id) { save(ai.copy(aiSimId = id)) } }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Calls on the other SIM ring normally and never go to the AI.", style = MaterialTheme.typography.bodySmall, color = Vaani.tokens.muted)
                }
            }
        }

        SectionLabel("Business hours")
        VCard(Modifier.padding(horizontal = 16.dp)) {
            SwitchRow("Answer instantly when closed", "Outside these hours the AI picks up without ringing.", ai.hoursEnabled) { save(ai.copy(hoursEnabled = it)) }
            AnimatedVisibility(ai.hoursEnabled) {
                Column {
                    CardDivider()
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TimeBox("Opens", clock(ai.openMinute), Modifier.weight(1f)) { actions.setup.pickTime(ai.openMinute) { save(ai.copy(openMinute = it)) } }
                        TimeBox("Closes", clock(ai.closeMinute), Modifier.weight(1f)) { actions.setup.pickTime(ai.closeMinute) { save(ai.copy(closeMinute = it)) } }
                    }
                    val days = listOf(2 to "M", 3 to "T", 4 to "W", 5 to "T", 6 to "F", 7 to "S", 1 to "S")
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        days.forEach { (d, label) ->
                            val on = d in ai.openDays
                            Box(
                                Modifier.size(38.dp).clip(CircleShape)
                                    .background(if (on) MaterialTheme.colorScheme.primary else Vaani.tokens.cardRaised)
                                    .border(1.dp, if (on) Color.Transparent else Vaani.tokens.hairline, CircleShape)
                                    .clickable { save(ai.copy(openDays = if (on) ai.openDays - d else ai.openDays + d)) },
                                contentAlignment = Alignment.Center,
                            ) { Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) MaterialTheme.colorScheme.onPrimary else Vaani.tokens.muted) }
                        }
                    }
                }
            }
        }

        if (stepsDone == 4) SetupCard(state, actions, stepsDone)

        var advanced by remember { mutableStateOf(false) }
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { advanced = !advanced }.padding(horizontal = 6.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp), tint = Vaani.tokens.muted)
            Spacer(Modifier.width(10.dp))
            Text("Advanced settings", style = MaterialTheme.typography.labelLarge, color = Vaani.tokens.muted, modifier = Modifier.weight(1f))
            Icon(if (advanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = Vaani.tokens.muted)
        }
        AnimatedVisibility(advanced) {
            var ws by remember(s.wsUrl) { mutableStateOf(s.wsUrl) }
            var id by remember(s.deviceId) { mutableStateOf(s.deviceId) }
            var token by remember(s.deviceToken) { mutableStateOf(s.deviceToken) }
            VCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), padding = PaddingValues(16.dp)) {
                val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Vaani.tokens.accent, unfocusedBorderColor = Vaani.tokens.hairlineStrong, focusedLabelColor = Vaani.tokens.accent)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(ws, { ws = it }, label = { Text("Server channel URL") }, singleLine = true, shape = RoundedCornerShape(12.dp), colors = colors, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(id, { id = it }, label = { Text("Device ID") }, singleLine = true, shape = RoundedCornerShape(12.dp), colors = colors, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(token, { token = it }, label = { Text("Device token") }, singleLine = true, shape = RoundedCornerShape(12.dp), colors = colors, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("Restart audio", actions.setup.restartAudio, Modifier.weight(1f), style = PillStyle.Secondary)
                        PillButton("Save and reconnect", { actions.setup.saveAdvanced(ws.trim(), id.trim(), token.trim()) }, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun Hero(state: HomeState, save: (AiSettings.Settings) -> Unit) {
    val s = state.setup
    val ai = state.ai
    val on = ai.mode != AiSettings.Mode.OFF
    val (dot, status) = when {
        s.pairingMessage != null -> Brand.Gold to s.pairingMessage
        !s.paired -> Brand.Gold to "Not connected yet"
        s.connected -> Color(0xFF34D399) to "Online  ·  ${s.host}"
        else -> Color(0xFFF87171) to "Can't reach ${s.host}. Retrying…"
    }
    HeroSurface(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Eyebrow("AI receptionist", color = Color.White.copy(alpha = 0.7f))
                    Spacer(Modifier.height(12.dp))
                    Text(if (on) "Answering for you" else "AI is off", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontSize = 26.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
                        Spacer(Modifier.width(8.dp))
                        Text(status, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Switch(
                    checked = on,
                    onCheckedChange = { save(ai.copy(mode = if (it) AiSettings.Mode.RING_FIRST else AiSettings.Mode.OFF)) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White, checkedTrackColor = Brand.Indigo, checkedBorderColor = Color.Transparent,
                        uncheckedThumbColor = Color.White.copy(alpha = 0.8f), uncheckedTrackColor = Color.White.copy(alpha = 0.12f), uncheckedBorderColor = Color.White.copy(alpha = 0.2f),
                    ),
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                when (ai.mode) {
                    AiSettings.Mode.OFF -> "Every call rings on this phone. The AI doesn't answer."
                    AiSettings.Mode.INSTANT -> "The AI answers incoming calls at once${if (ai.skipContacts) ", except saved contacts" else ""}."
                    AiSettings.Mode.RING_FIRST -> "Calls ring for ${ai.ringSeconds} seconds. If you don't pick up, the AI answers${if (ai.skipContacts) ". Saved contacts always ring normally" else ""}."
                },
                color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium, lineHeight = 21.sp,
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroStat(Icons.Rounded.Schedule, if (ai.mode == AiSettings.Mode.RING_FIRST) "${ai.ringSeconds}s ring" else ai.mode.label, Modifier.weight(1f))
                HeroStat(Icons.Rounded.PhoneAndroid, if (ai.skipContacts) "Contacts" else "Everyone", Modifier.weight(1f))
                if (s.licence != null) HeroStat(Icons.Rounded.Verified, if (s.licenceValid) "Licensed" else "Licence off", Modifier.weight(1f), warn = !s.licenceValid)
                else HeroStat(Icons.Rounded.GraphicEq, if (s.audioDone) "Audio ready" else "Audio setup", Modifier.weight(1f))
            }
            if (s.licence != null) {
                Spacer(Modifier.height(12.dp))
                Text(s.licence, color = if (s.licenceValid) Color.White.copy(alpha = 0.55f) else Color(0xFFFFB4B4), style = MaterialTheme.typography.labelMedium, maxLines = 2)
            }
        }
    }
}

@Composable
private fun HeroStat(icon: ImageVector, text: String, modifier: Modifier = Modifier, warn: Boolean = false) {
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.07f)).border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint = if (warn) Color(0xFFFFB4B4) else Color.White.copy(alpha = 0.7f))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Color.White, style = MaterialTheme.typography.labelMedium, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SetupCard(state: HomeState, actions: HomeActions, done: Int) {
    val s = state.setup
    SectionLabel("Setup", trailing = "$done of 4 done")
    VCard(Modifier.padding(horizontal = 16.dp)) {
        // Progress bar
        Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).height(6.dp).clip(RoundedCornerShape(50)).background(Vaani.tokens.hairline)) {
            Box(Modifier.fillMaxWidth(done / 4f).height(6.dp).clip(RoundedCornerShape(50)).background(Brand.gradient))
        }
        Step(
            Icons.Rounded.PhoneAndroid, "Default phone app",
            when {
                s.isDefault -> "VaaniFlow handles this phone's calls."
                state.dialerBlocked -> "Android blocked it. Allow restricted settings first."
                else -> "Needed to place and answer calls."
            },
            s.isDefault, if (s.isDefault) null else if (state.dialerBlocked) "Fix" else "Set",
        ) { if (state.dialerBlocked) actions.dialerHelp(true) else actions.setup.setDefault() }
        CardDivider(68.dp)
        Step(Icons.Rounded.QrCodeScanner, "Connect to your workspace", when {
            !s.paired -> "Scan the QR code on the dashboard's Phones page."
            s.connected -> "Connected to ${s.host}."
            else -> "${s.host} is not reachable right now."
        }, s.paired && s.connected, if (s.paired) "Rescan" else "Scan", actions.setup.scanQr)
        CardDivider(68.dp)
        Step(Icons.Rounded.GraphicEq, "AI call audio", s.audioText, s.audioDone, s.audioAction, actions.setup.audio)
        CardDivider(68.dp)
        Step(Icons.Rounded.BatteryChargingFull, "Stay online in the background", s.batteryText, s.batteryDone, s.batteryAction, actions.setup.battery)
    }
}

@Composable
private fun OptionRow(title: String, text: String, selected: Boolean, onClick: () -> Unit) {
    val t = Vaani.tokens
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(if (selected) t.accent else Color.Transparent).border(1.5.dp, if (selected) t.accent else t.hairlineStrong, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(14.dp), tint = Color.White) }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontSize = 15.sp)
            Spacer(Modifier.height(2.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = t.muted)
        }
    }
}

@Composable
private fun SwitchRow(title: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontSize = 15.sp)
            Spacer(Modifier.height(2.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = Vaani.tokens.muted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = Vaani.tokens.accent, checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Vaani.tokens.subtle, uncheckedTrackColor = Vaani.tokens.cardRaised, uncheckedBorderColor = Vaani.tokens.hairlineStrong,
            ),
        )
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val t = Vaani.tokens
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (selected) MaterialTheme.colorScheme.primary else t.cardRaised)
            .border(1.dp, if (selected) Color.Transparent else t.hairline, RoundedCornerShape(50)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) { Icon(Icons.Rounded.Check, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(6.dp)) }
        Text(label, style = MaterialTheme.typography.labelLarge, fontSize = 13.sp, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun TimeBox(label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val t = Vaani.tokens
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(t.cardRaised).border(1.dp, t.hairline, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = t.subtle, fontSize = 10.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Step(icon: ImageVector, title: String, text: String, done: Boolean, action: String?, onClick: () -> Unit) {
    val t = Vaani.tokens
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (done) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(t.success.copy(alpha = 0.12f)).border(1.dp, t.success.copy(alpha = 0.25f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, null, Modifier.size(19.dp), tint = t.success)
            }
        } else IconTile(icon, tint = MaterialTheme.colorScheme.onSurface, size = 38.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = t.muted)
        }
        if (action != null) {
            Spacer(Modifier.width(10.dp))
            PillButton(action, onClick, compact = true, style = if (done) PillStyle.Secondary else PillStyle.Primary)
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
