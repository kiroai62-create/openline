package com.vaaniflow.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SimCard
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Settings page like Google Phone: blocked numbers, SIMs, assistant shortcut, about. */
@Composable
fun SettingsScreen(state: HomeState, actions: HomeActions, onBack: () -> Unit) {
    val t = Vaani.tokens
    var blocked by remember { mutableStateOf(actions.blockedNumbers()) }
    var showBlocked by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(t.background)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (showBlocked) showBlocked = false else onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Spacer(Modifier.width(4.dp))
            Text(if (showBlocked) "Blocked numbers" else "Settings", style = MaterialTheme.typography.headlineSmall, fontSize = 22.sp)
        }
        if (showBlocked) {
            LazyColumn(Modifier.fillMaxSize()) {
                if (blocked.isEmpty()) item { Empty(Icons.Outlined.Block, "No blocked numbers", "Blocked callers can't reach you. Block a number from a call or contact.") }
                else blocked.forEachIndexed { i, number ->
                    item(key = number) {
                        Surface(shape = segmentShape(i, blocked.size), color = t.card, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = if (i > 0) 2.dp else 0.dp)) {
                            ListRow(
                                formatNumber(number), "Blocked",
                                leading = { IconTile(Icons.Outlined.Block, tint = t.danger, background = t.danger.copy(alpha = 0.12f)) },
                                trailing = {
                                    Surface(onClick = { actions.setBlocked(number, false); blocked = actions.blockedNumbers() }, shape = CircleShape, color = t.cardRaised, modifier = Modifier.size(36.dp)) {
                                        Icon(Icons.Rounded.Close, "Unblock", Modifier.padding(8.dp), tint = t.muted)
                                    }
                                },
                            )
                        }
                    }
                }
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item { SectionLabel("Calls") }
            item {
                Surface(shape = segmentShape(0, 2), color = t.card, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    ListRow("Blocked numbers", if (blocked.isEmpty()) "None" else "${blocked.size} blocked", Modifier.clickable { blocked = actions.blockedNumbers(); showBlocked = true }, leading = { IconTile(Icons.Outlined.Block) })
                }
                Surface(shape = segmentShape(1, 2), color = t.card, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 2.dp)) {
                    ListRow("Calling accounts (SIMs)", if (state.accounts.isEmpty()) "No SIM detected" else state.accounts.joinToString(", ") { it.second }, leading = { IconTile(Icons.Outlined.SimCard) })
                }
            }
            item { SectionLabel("AI receptionist") }
            item {
                Surface(shape = segmentShape(0, 1), color = t.card, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    ListRow(
                        "VaaniFlow AI", when {
                            !state.setup.paired -> "Not set up"
                            state.setup.connected -> "Online"
                            else -> "Offline"
                        },
                        Modifier.clickable(onClick = onBack),
                        leading = { IconTile(Icons.Outlined.AutoAwesome, tint = Vaani.tokens.accent) },
                    )
                }
            }
            item { SectionLabel("Contacts") }
            item {
                Surface(shape = segmentShape(0, 1), color = t.card, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    ListRow("Create new contact", "Opens your contacts app", Modifier.clickable { actions.addContact("") }, leading = { IconTile(Icons.Outlined.Contacts) })
                }
            }
            item { SectionLabel("About") }
            item {
                Surface(shape = segmentShape(0, 1), color = t.card, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    ListRow("VaaniFlow", "Version ${com.vaaniflow.app.BuildConfig.VERSION_NAME}", leading = { IconTile(Icons.Outlined.Info) })
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
