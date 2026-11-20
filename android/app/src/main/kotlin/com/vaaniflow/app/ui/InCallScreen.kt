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
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.SwapCalls
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vaaniflow.app.data.PhoneData
import com.vaaniflow.app.telecom.CallActions
import com.vaaniflow.app.telecom.CallRegistry

/** Call screen in Google Phone's layout: caller on top, round controls, red end-call pill. */
@Composable
fun InCallScreen(calls: List<CallRegistry.Entry>, audio: CallAudioState?, onAddCall: () -> Unit, onMinimise: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val t = Vaani.tokens
    val live = calls.filter { CallRegistry.androidState(it.call) !in setOf(Call.STATE_DISCONNECTED) }
    val primary = live.firstOrNull { CallRegistry.androidState(it.call) == Call.STATE_RINGING && !CallRegistry.isAiOwned(it.callId) }
        ?: live.firstOrNull { CallRegistry.androidState(it.call) in setOf(Call.STATE_ACTIVE, Call.STATE_AUDIO_PROCESSING) }
        ?: live.firstOrNull { CallRegistry.androidState(it.call) in setOf(Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_SELECT_PHONE_ACCOUNT) }
        ?: live.firstOrNull()
    val others = live.filter { it != primary }

    Box(Modifier.fillMaxSize().background(if (t.dark) cs.surface else cs.surfaceContainer)) {
        if (primary == null) {
            Text("Call ended", style = MaterialTheme.typography.headlineSmall, color = cs.onSurface, modifier = Modifier.align(Alignment.Center))
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
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onMinimise) { Icon(Icons.Rounded.KeyboardArrowDown, "Minimise", tint = cs.onSurfaceVariant) }
                Spacer(Modifier.weight(1f))
                if (sim != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.SimCard, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(sim, color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                }
            }
            others.forEach { o -> OtherCallCard(o) }

            Spacer(Modifier.height(if (others.isEmpty()) 32.dp else 16.dp))
            if (ai && !incoming) AiAvatar() else Avatar(if (conference) "Conference" else name, number, size = 96.dp)
            Spacer(Modifier.height(20.dp))
            Text(
                if (conference) "Conference call" else name ?: formatNumber(number).ifBlank { "Unknown" },
                style = MaterialTheme.typography.displaySmall, color = cs.onSurface, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (name != null && !conference) Text(formatNumber(number), style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(8.dp))
            Text(statusText(primary, state, ai, conference), style = MaterialTheme.typography.titleMedium, color = if (ai) cs.primary else cs.onSurfaceVariant)
            if (incoming) {
                val decision = CallRegistry.decision(primary.callId)
                val reason = CallRegistry.decisionReason(primary.callId)
                if (decision != null && reason != null) {
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.clip(RoundedCornerShape(50)).background(cs.secondaryContainer).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = cs.onSecondaryContainer, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (decision.startsWith("after:")) "AI answers if you don't" else reason, color = cs.onSecondaryContainer, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            when {
                incoming -> IncomingControls(primary.callId)
                ai -> AiControls(primary.callId)
                keypad -> {
                    Text(typed.ifEmpty { " " }, style = MaterialTheme.typography.headlineMedium, color = cs.onSurface, maxLines = 1)
                    Spacer(Modifier.height(12.dp))
                    Keypad(Modifier.padding(horizontal = 8.dp)) { d -> typed += d; CallActions.dtmf(primary.callId, d.first()) }
                    TextButton(onClick = { keypad = false }) { Text("Hide keypad") }
                    Spacer(Modifier.height(8.dp))
                    EndButton { CallActions.hangup(primary.callId) }
                }
                else -> ActiveControls(primary, others, state, audio, onAddCall) { keypad = true }
            }
            Spacer(Modifier.height(32.dp))
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
                                Icon(Icons.Rounded.SimCard, null, tint = cs.primary)
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
            ai -> "AI is talking • $elapsed"
            conference -> "Conference • $elapsed"
            else -> elapsed
        }
    }
}

@Composable
private fun OtherCallCard(entry: CallRegistry.Entry) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val number = entry.call.details.handle?.schemeSpecificPart.orEmpty()
    val name = remember(number) { PhoneData.nameFor(context, number) ?: formatNumber(number).ifBlank { "Unknown" } }
    val state = CallRegistry.androidState(entry.call)
    Surface(shape = RoundedCornerShape(20.dp), color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(name, number, size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    when {
                        CallRegistry.isAiOwned(entry.callId) -> "With AI"
                        state == Call.STATE_HOLDING -> "On hold"
                        state == Call.STATE_RINGING -> "Incoming call"
                        else -> "On call"
                    },
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
            }
            when {
                state == Call.STATE_HOLDING -> TextButton(onClick = { CallActions.swap() }) { Text("Swap") }
                state == Call.STATE_RINGING && !CallRegistry.isAiOwned(entry.callId) -> Row {
                    TextButton(onClick = { CallActions.handToAi(entry.callId) }) { Text("AI") }
                    TextButton(onClick = { CallActions.decline(entry.callId) }) { Text("Decline", color = Vaani.tokens.endRed) }
                    TextButton(onClick = { CallActions.answer(entry.callId) }) { Text("Answer", color = Vaani.tokens.callGreen) }
                }
                CallRegistry.isAiOwned(entry.callId) -> TextButton(onClick = { CallActions.takeOver(entry.callId) }) { Text("Take over") }
            }
        }
    }
}

@Composable
private fun IncomingControls(callId: String) {
    val t = Vaani.tokens
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        RoundAction(Icons.Rounded.CallEnd, "Decline", t.endRed) { CallActions.decline(callId) }
        RoundAction(Icons.Rounded.Call, "Answer", t.callGreen, pulse = true) { CallActions.answer(callId) }
    }
    Spacer(Modifier.height(24.dp))
    FilledTonalButton(onClick = { CallActions.handToAi(callId) }) {
        Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Let AI answer")
    }
}

@Composable
private fun AiControls(callId: String) {
    Text("The caller is talking with your AI receptionist. The phone stays silent.", textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
    Spacer(Modifier.height(28.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Toggle(Icons.Rounded.PhoneInTalk, "Take over", false) { CallActions.takeOver(callId) }
    }
    Spacer(Modifier.height(28.dp))
    EndButton { CallActions.hangup(callId) }
}

@Composable
private fun ActiveControls(primary: CallRegistry.Entry, others: List<CallRegistry.Entry>, state: Int, audio: CallAudioState?, onAddCall: () -> Unit, openKeypad: () -> Unit) {
    val muted = audio?.isMuted == true
    val route = audio?.route ?: CallAudioState.ROUTE_EARPIECE
    val (routeIcon, routeLabel) = when (route) {
        CallAudioState.ROUTE_BLUETOOTH -> Icons.Rounded.Bluetooth to "Bluetooth"
        CallAudioState.ROUTE_WIRED_HEADSET -> Icons.Rounded.Headset to "Headset"
        else -> Icons.AutoMirrored.Rounded.VolumeUp to "Speaker"
    }
    val held = others.any { CallRegistry.androidState(it.call) == Call.STATE_HOLDING }
    val canHold = primary.call.details.can(Call.Details.CAPABILITY_HOLD) || state == Call.STATE_HOLDING
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Toggle(Icons.Rounded.Dialpad, "Keypad", false, onClick = openKeypad)
            Toggle(Icons.Rounded.MicOff, "Mute", muted) { CallActions.setMuted(!muted) }
            Toggle(routeIcon, routeLabel, route == CallAudioState.ROUTE_SPEAKER || route == CallAudioState.ROUTE_BLUETOOTH) { CallActions.cycleRoute() }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            if (held) Toggle(Icons.AutoMirrored.Rounded.CallMerge, "Merge", false) { CallActions.merge() } else Toggle(Icons.Rounded.Add, "Add call", false, onClick = onAddCall)
            if (held) Toggle(Icons.Rounded.SwapCalls, "Swap", false) { CallActions.swap() }
            else Toggle(Icons.Rounded.Pause, if (state == Call.STATE_HOLDING) "Resume" else "Hold", state == Call.STATE_HOLDING, enabled = canHold) { CallActions.toggleHold(primary.callId) }
            Toggle(Icons.Rounded.AutoAwesome, "Give to AI", false) { CallActions.handToAi(primary.callId) }
        }
    }
    Spacer(Modifier.height(32.dp))
    EndButton { CallActions.hangup(primary.callId) }
}

@Composable
private fun Toggle(icon: ImageVector, label: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(88.dp)) {
        Box(
            Modifier.size(width = 72.dp, height = 64.dp).clip(RoundedCornerShape(if (on) 20.dp else 32.dp))
                .background(if (on) cs.primary else cs.surfaceContainerHighest.copy(alpha = if (enabled) 1f else 0.5f))
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = if (on) cs.onPrimary else cs.onSurface.copy(alpha = if (enabled) 1f else 0.38f)) }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, color: Color, pulse: Boolean = false, onClick: () -> Unit) {
    val scale = if (pulse) {
        val tr = rememberInfiniteTransition(label = "pulse")
        tr.animateFloat(1f, 1.06f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "s").value
    } else 1f
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.scale(scale).size(72.dp).clip(CircleShape).background(color).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EndButton(onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 104.dp, height = 64.dp).clip(RoundedCornerShape(32.dp)).background(Vaani.tokens.endRed).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.CallEnd, "End call", tint = Color.White, modifier = Modifier.size(30.dp))
        }
    }
}

/** Calls the AI is handling: sparkle avatar with a soft pulse. */
@Composable
private fun AiAvatar() {
    val tr = rememberInfiniteTransition(label = "ai")
    val s by tr.animateFloat(0.96f, 1.04f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "aiScale")
    Box(Modifier.scale(s).size(96.dp).clip(CircleShape).background(Brand.gradient), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(40.dp))
    }
}
