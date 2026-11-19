package com.vaaniflow.app.ui

import android.telecom.Call
import android.telecom.CallAudioState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMerge
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.SwapCalls
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaaniflow.app.data.PhoneData
import com.vaaniflow.app.telecom.CallActions
import com.vaaniflow.app.telecom.CallRegistry

private val White = Color.White
private val Dim = Color.White.copy(alpha = 0.65f)

@Composable
fun InCallScreen(calls: List<CallRegistry.Entry>, audio: CallAudioState?, onAddCall: () -> Unit, onMinimise: () -> Unit) {
    val context = LocalContext.current
    val live = calls.filter { CallRegistry.androidState(it.call) !in setOf(Call.STATE_DISCONNECTED) }
    val primary = live.firstOrNull { CallRegistry.androidState(it.call) == Call.STATE_RINGING && !CallRegistry.isAiOwned(it.callId) }
        ?: live.firstOrNull { CallRegistry.androidState(it.call) in setOf(Call.STATE_ACTIVE, Call.STATE_AUDIO_PROCESSING) }
        ?: live.firstOrNull { CallRegistry.androidState(it.call) in setOf(Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_SELECT_PHONE_ACCOUNT) }
        ?: live.firstOrNull()
    val others = live.filter { it != primary }

    Box(
        Modifier.fillMaxSize().background(Brand.Night)
            .background(Brush.radialGradient(listOf(Brand.Indigo.copy(alpha = 0.55f), Color.Transparent), radius = 900f, center = androidx.compose.ui.geometry.Offset(150f, 200f)))
            .background(Brush.radialGradient(listOf(Brand.Teal.copy(alpha = 0.35f), Color.Transparent), radius = 900f, center = androidx.compose.ui.geometry.Offset(1000f, 2000f))),
    ) {
        if (primary == null) {
            Text("Call ended", color = White, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.align(Alignment.Center))
            return@Box
        }
        val call = primary.call
        val state = CallRegistry.androidState(call)
        val number = call.details.handle?.schemeSpecificPart.orEmpty()
        val name = remember(number) { PhoneData.nameFor(context, number) }
        val ai = CallRegistry.isAiOwned(primary.callId)
        val incoming = state == Call.STATE_RINGING
        val conference = call.details.hasProperty(Call.Details.PROPERTY_CONFERENCE)
        val sim = remember(call.details.accountHandle) {
            val accounts = PhoneData.callAccounts(context)
            if (accounts.size > 1) accounts.firstOrNull { it.first == call.details.accountHandle }?.second else null
        }
        var keypad by remember { mutableStateOf(false) }
        var typed by remember { mutableStateOf("") }

        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onMinimise) { Icon(Icons.Rounded.KeyboardArrowDown, "Minimise", tint = Dim) }
                Spacer(Modifier.weight(1f))
                if (sim != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.SimCard, null, tint = Dim, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(sim, color = Dim, style = MaterialTheme.typography.labelMedium)
                }
            }
            // Other calls (held / waiting)
            others.forEach { o -> OtherCallCard(o) }

            Spacer(Modifier.height(if (others.isEmpty()) 36.dp else 16.dp))
            Text(statusText(primary, state, ai, conference), color = if (ai) Brand.Teal else Dim, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(22.dp))
            if (ai && !incoming) AiOrb() else Avatar(if (conference) "Conference" else name, number, size = 112.dp)
            Spacer(Modifier.height(20.dp))
            Text(
                if (conference) "Conference call" else name ?: formatNumber(number).ifBlank { "Unknown caller" },
                color = White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (name != null && !conference) Text(formatNumber(number), color = Dim, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
            if (incoming) {
                val decision = CallRegistry.decision(primary.callId)
                val reason = CallRegistry.decisionReason(primary.callId)
                if (decision != null && reason != null) {
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.clip(RoundedCornerShape(50)).background(White.copy(alpha = 0.1f)).padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = Brand.Teal, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (decision.startsWith("after:")) "VaaniFlow AI answers if you don't" else reason, color = White, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            when {
                incoming -> IncomingControls(primary.callId, CallRegistry.decision(primary.callId) != "never" || true)
                ai -> AiControls(primary.callId)
                keypad -> {
                    Text(typed.ifEmpty { " " }, color = White, fontSize = 30.sp, maxLines = 1)
                    Spacer(Modifier.height(12.dp))
                    Keypad(Modifier.padding(horizontal = 12.dp), dark = true) { d -> typed += d; CallActions.dtmf(primary.callId, d.first()) }
                    TextButton(onClick = { keypad = false }) { Text("Hide keypad", color = White) }
                    Spacer(Modifier.height(8.dp))
                    EndButton { CallActions.hangup(primary.callId) }
                }
                else -> ActiveControls(primary, others, state, audio, onAddCall, { keypad = true })
            }
            Spacer(Modifier.height(36.dp))
        }

        if (state == Call.STATE_SELECT_PHONE_ACCOUNT) {
            val accounts = remember { PhoneData.callAccounts(context) }
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Call with") },
                text = {
                    Column {
                        accounts.forEach { (handle, label) ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { CallActions.selectAccount(primary.callId, handle) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.SimCard, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Text(label, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { CallActions.hangup(primary.callId) }) { Text("Cancel") } },
            )
        }
    }
}

private fun statusText(entry: CallRegistry.Entry, state: Int, ai: Boolean, conference: Boolean): String = when (state) {
    Call.STATE_RINGING -> "Incoming call"
    Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_SELECT_PHONE_ACCOUNT -> "Calling…"
    Call.STATE_HOLDING -> "On hold"
    Call.STATE_DISCONNECTING, Call.STATE_DISCONNECTED -> "Call ended"
    else -> {
        val connect = entry.call.details.connectTimeMillis
        val elapsed = if (connect > 0) durationText((System.currentTimeMillis() - connect) / 1000) else ""
        when {
            ai -> "VaaniFlow AI is talking · $elapsed"
            conference -> "Conference · $elapsed"
            else -> elapsed
        }
    }
}

@Composable
private fun OtherCallCard(entry: CallRegistry.Entry) {
    val context = LocalContext.current
    val number = entry.call.details.handle?.schemeSpecificPart.orEmpty()
    val name = remember(number) { PhoneData.nameFor(context, number) ?: formatNumber(number).ifBlank { "Unknown" } }
    val state = CallRegistry.androidState(entry.call)
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(18.dp)).background(White.copy(alpha = 0.08f)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name, number, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = White, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(
                when {
                    CallRegistry.isAiOwned(entry.callId) -> "With VaaniFlow AI"
                    state == Call.STATE_HOLDING -> "On hold"
                    state == Call.STATE_RINGING -> "Waiting"
                    else -> "On call"
                },
                color = Dim, style = MaterialTheme.typography.bodySmall,
            )
        }
        when {
            state == Call.STATE_HOLDING -> TextButton(onClick = { CallActions.swap() }) { Text("Swap", color = Brand.Teal) }
            state == Call.STATE_RINGING && !CallRegistry.isAiOwned(entry.callId) -> Row {
                TextButton(onClick = { CallActions.handToAi(entry.callId) }) { Text("AI", color = Brand.Teal) }
                TextButton(onClick = { CallActions.decline(entry.callId) }) { Text("Decline", color = Brand.Red) }
                TextButton(onClick = { CallActions.answer(entry.callId) }) { Text("Answer", color = Brand.Green) }
            }
            CallRegistry.isAiOwned(entry.callId) -> TextButton(onClick = { CallActions.takeOver(entry.callId) }) { Text("Take over", color = Brand.Teal) }
        }
    }
}

@Composable
private fun IncomingControls(callId: String, @Suppress("UNUSED_PARAMETER") aiAllowed: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        RoundAction(Icons.Rounded.CallEnd, "Decline", Brand.Red) { CallActions.decline(callId) }
        RoundAction(Icons.Rounded.Call, "Answer", Brand.Green, pulse = true) { CallActions.answer(callId) }
    }
    Spacer(Modifier.height(26.dp))
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Brand.gradient).clickable { CallActions.handToAi(callId) }.padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.AutoAwesome, null, tint = White)
        Spacer(Modifier.width(8.dp))
        Text("Let VaaniFlow AI answer", color = White, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun AiControls(callId: String) {
    Text("The caller is talking with your AI receptionist. The phone stays silent.", color = Dim, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp))
    Spacer(Modifier.height(28.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        RoundAction(Icons.Rounded.PhoneInTalk, "Take over", White.copy(alpha = 0.14f)) { CallActions.takeOver(callId) }
        RoundAction(Icons.Rounded.CallEnd, "End call", Brand.Red) { CallActions.hangup(callId) }
    }
}

@Composable
private fun ActiveControls(primary: CallRegistry.Entry, others: List<CallRegistry.Entry>, state: Int, audio: CallAudioState?, onAddCall: () -> Unit, openKeypad: () -> Unit) {
    val muted = audio?.isMuted == true
    val route = audio?.route ?: CallAudioState.ROUTE_EARPIECE
    val (routeIcon, routeLabel) = when (route) {
        CallAudioState.ROUTE_SPEAKER -> Icons.AutoMirrored.Rounded.VolumeUp to "Speaker"
        CallAudioState.ROUTE_BLUETOOTH -> Icons.Rounded.Bluetooth to "Bluetooth"
        CallAudioState.ROUTE_WIRED_HEADSET -> Icons.Rounded.Headset to "Headset"
        else -> Icons.AutoMirrored.Rounded.VolumeUp to "Speaker"
    }
    val held = others.any { CallRegistry.androidState(it.call) == Call.STATE_HOLDING }
    val canHold = primary.call.details.can(Call.Details.CAPABILITY_HOLD) || state == Call.STATE_HOLDING
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Toggle(if (muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, if (muted) "Unmute" else "Mute", muted) { CallActions.setMuted(!muted) }
            Toggle(Icons.Rounded.Dialpad, "Keypad", false, onClick = openKeypad)
            Toggle(routeIcon, routeLabel, route == CallAudioState.ROUTE_SPEAKER || route == CallAudioState.ROUTE_BLUETOOTH) { CallActions.cycleRoute() }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            if (held) Toggle(Icons.AutoMirrored.Rounded.CallMerge, "Merge", false) { CallActions.merge() } else Toggle(Icons.Rounded.Add, "Add call", false, onClick = onAddCall)
            if (held) Toggle(Icons.Rounded.SwapCalls, "Swap", false) { CallActions.swap() }
            else Toggle(Icons.Rounded.Pause, if (state == Call.STATE_HOLDING) "Resume" else "Hold", state == Call.STATE_HOLDING, enabled = canHold) { CallActions.toggleHold(primary.callId) }
            Toggle(Icons.Rounded.AutoAwesome, "Give to AI", false) { CallActions.handToAi(primary.callId) }
        }
    }
    Spacer(Modifier.height(30.dp))
    EndButton { CallActions.hangup(primary.callId) }
}

@Composable
private fun Toggle(icon: ImageVector, label: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(86.dp)) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(if (on) White else White.copy(alpha = if (enabled) 0.12f else 0.05f)).clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = if (on) Brand.Night else White.copy(alpha = if (enabled) 1f else 0.4f)) }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Dim, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, color: Color, pulse: Boolean = false, onClick: () -> Unit) {
    val scale = if (pulse) {
        val t = rememberInfiniteTransition(label = "pulse")
        t.animateFloat(1f, 1.08f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "s").value
    } else 1f
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.scale(scale).size(76.dp).clip(CircleShape).background(color).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = White, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = Dim, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun EndButton(onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 96.dp, height = 64.dp).clip(RoundedCornerShape(32.dp)).background(Brand.Red).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.CallEnd, "End call", tint = White, modifier = Modifier.size(32.dp))
        }
    }
}

/** Animated "voice" orb for calls the AI is handling. */
@Composable
private fun AiOrb() {
    val t = rememberInfiniteTransition(label = "orb")
    val s by t.animateFloat(0.92f, 1.08f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "orbScale")
    Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.scale(s * 1.15f).size(120.dp).clip(CircleShape).background(Brand.Teal.copy(alpha = 0.18f)))
        Box(Modifier.scale(s).size(104.dp).clip(CircleShape).background(Brand.gradient), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = White, modifier = Modifier.size(44.dp))
        }
    }
}
