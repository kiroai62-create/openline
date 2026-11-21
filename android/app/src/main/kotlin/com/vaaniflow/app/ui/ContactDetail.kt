package com.vaaniflow.app.ui

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallMissed
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaaniflow.app.data.PhoneData

/** Full-screen contact page like Google Phone: avatar, quick actions, every number, call history. */
@Composable
fun ContactDetail(
    contact: PhoneData.Contact,
    history: List<PhoneData.CallEntry>,
    blocked: Boolean,
    onBack: () -> Unit,
    onCall: (String) -> Unit,
    onMessage: (String) -> Unit,
    onEdit: () -> Unit,
    onStar: (Boolean) -> Unit,
    onBlock: (Boolean) -> Unit,
) {
    val t = Vaani.tokens
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(t.background)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { onStar(!contact.starred) }) {
                Icon(if (contact.starred) Icons.Rounded.Star else Icons.Rounded.StarOutline, "Favourite", tint = if (contact.starred) Brand.Amber else cs.onSurfaceVariant)
            }
            IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "Edit") }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(contact.name, contact.numbers.first().number, size = 96.dp)
                    Spacer(Modifier.height(14.dp))
                    Text(contact.name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        QuickAction(Icons.Rounded.Call, "Call") { onCall(contact.numbers.first().number) }
                        QuickAction(Icons.AutoMirrored.Outlined.Message, "Text") { onMessage(contact.numbers.first().number) }
                        QuickAction(if (blocked) Icons.Outlined.Block else Icons.Outlined.Block, if (blocked) "Unblock" else "Block") { onBlock(!blocked) }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            item { SectionLabel("Contact info") }
            contact.numbers.forEachIndexed { i, n ->
                item(key = "n$i") {
                    Surface(shape = segmentShape(i, contact.numbers.size), color = t.card, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = if (i > 0) 2.dp else 0.dp)) {
                        ListRow(
                            formatNumber(n.number), n.label,
                            Modifier.clickable { onCall(n.number) },
                            leading = { IconTile(Icons.Rounded.Call, tint = t.callGreen, background = t.callGreen.copy(alpha = 0.12f)) },
                            trailing = { IconButton(onClick = { onMessage(n.number) }) { Icon(Icons.AutoMirrored.Outlined.Message, "Message", tint = cs.onSurfaceVariant) } },
                        )
                    }
                }
            }
            if (history.isNotEmpty()) {
                item { SectionLabel("Call history") }
                history.forEachIndexed { i, e ->
                    item(key = "h${e.id}") {
                        Surface(shape = segmentShape(i, history.size), color = t.card, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = if (i > 0) 2.dp else 0.dp)) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                val (icon, tint, label) = callKind(e, t)
                                Icon(icon, null, Modifier.size(20.dp), tint = tint)
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(label, style = MaterialTheme.typography.bodyLarge)
                                    Text(callDateTime(e.date), style = MaterialTheme.typography.bodyMedium, color = t.muted)
                                }
                                if (e.duration > 0) Text(durationText(e.duration), style = MaterialTheme.typography.labelLarge, color = t.muted)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(onClick = onClick, shape = CircleShape, color = cs.secondaryContainer, modifier = Modifier.size(56.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, label, tint = cs.onSecondaryContainer) }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

internal fun callKind(e: PhoneData.CallEntry, t: VaaniTokens): Triple<ImageVector, Color, String> = when {
    e.missed -> Triple(Icons.AutoMirrored.Rounded.CallMissed, t.danger, "Missed")
    e.outgoing -> Triple(Icons.AutoMirrored.Rounded.CallMade, t.muted, "Outgoing")
    else -> Triple(Icons.AutoMirrored.Rounded.CallReceived, t.muted, "Incoming")
}

internal fun callDateTime(millis: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(millis))
