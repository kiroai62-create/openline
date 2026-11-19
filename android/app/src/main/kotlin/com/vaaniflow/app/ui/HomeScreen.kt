package com.vaaniflow.app.ui

import android.provider.CallLog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallMissed
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vaaniflow.app.ai.AiSettings
import com.vaaniflow.app.data.PhoneData
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class HomeTab(val label: String, val icon: ImageVector) {
    FAVOURITES("Favourites", Icons.Rounded.Star),
    RECENTS("Recents", Icons.Rounded.AccessTime),
    CONTACTS("Contacts", Icons.Rounded.Contacts),
    ASSISTANT("VaaniFlow AI", Icons.Rounded.AutoAwesome),
}

/** Everything the home screen shows; the activity refreshes it. */
data class HomeState(
    val contacts: List<PhoneData.Contact> = emptyList(),
    val recents: List<PhoneData.CallEntry> = emptyList(),
    val accounts: List<Pair<String, String>> = emptyList(),
    val missingPermissions: Boolean = false,
    val isDefaultDialer: Boolean = true,
    val setup: SetupState = SetupState(),
    val ai: AiSettings.Settings = AiSettings.Settings(),
)

class HomeActions(
    val call: (String) -> Unit,
    val message: (String) -> Unit,
    val addContact: (String) -> Unit,
    val openContact: (PhoneData.Contact) -> Unit,
    val requestPermissions: () -> Unit,
    val setDefaultDialer: () -> Unit,
    val setup: SetupActions,
    val saveAi: (AiSettings.Settings) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: HomeState, actions: HomeActions, initialTab: HomeTab, dialNumber: String?, onDialConsumed: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var dialpadOpen by rememberSaveable { mutableStateOf(dialNumber != null) }
    if (dialNumber != null && !dialpadOpen) dialpadOpen = true

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { SearchBar(query, searching, { query = it }, { searching = it; if (!it) query = "" }, state.setup) { tab = HomeTab.ASSISTANT } },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                HomeTab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t; searching = false; query = "" },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer, selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary),
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab != HomeTab.ASSISTANT && !searching) {
                FloatingActionButton(onClick = { dialpadOpen = true }, containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer, shape = RoundedCornerShape(18.dp)) {
                    Icon(Icons.Rounded.Dialpad, "Keypad")
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.missingPermissions || !state.isDefaultDialer) Banners(state, actions)
            when {
                searching -> SearchResults(query, state, actions)
                tab == HomeTab.FAVOURITES -> Favourites(state, actions)
                tab == HomeTab.RECENTS -> Recents(state, actions)
                tab == HomeTab.CONTACTS -> ContactsList(state.contacts, actions)
                tab == HomeTab.ASSISTANT -> AssistantScreen(state, actions)
            }
        }
    }
    if (dialpadOpen) {
        DialpadSheet(
            initial = dialNumber.orEmpty(),
            contacts = state.contacts,
            onCall = { actions.call(it); dialpadOpen = false; onDialConsumed() },
            onAddContact = actions.addContact,
            onDismiss = { dialpadOpen = false; onDialConsumed() },
        )
    }
}

@Composable
private fun SearchBar(query: String, searching: Boolean, onQuery: (String) -> Unit, onSearching: (Boolean) -> Unit, setup: SetupState, openAssistant: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)
            .clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable { onSearching(true) }.padding(horizontal = 6.dp).height(54.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (searching) {
            IconButton(onClick = { onSearching(false) }) { Icon(Icons.Rounded.Close, "Close search") }
            val focus = remember { androidx.compose.ui.focus.FocusRequester() }
            androidx.compose.foundation.text.BasicTextField(
                value = query, onValueChange = onQuery, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f).focusRequester(focus),
                decorationBox = { inner -> Box { if (query.isEmpty()) Text("Search contacts and calls", color = MaterialTheme.colorScheme.onSurfaceVariant); inner() } },
            )
            androidx.compose.runtime.LaunchedEffect(Unit) { focus.requestFocus() }
        } else {
            Icon(Icons.Rounded.Search, null, Modifier.padding(start = 12.dp, end = 12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Search contacts", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            // AI status at a glance; tap opens VaaniFlow AI.
            val (label, color) = when {
                !setup.paired -> "Set up AI" to Brand.Amber
                setup.connected -> "AI ready" to Brand.Green
                else -> "AI offline" to Brand.Red
            }
            Box(Modifier.clip(RoundedCornerShape(50)).clickable { openAssistant() }.padding(end = 6.dp)) { StatusPill(label, color) }
        }
    }
}

@Composable
private fun Banners(state: HomeState, actions: HomeActions) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!state.isDefaultDialer) {
            Banner("Make VaaniFlow your phone app", "Needed to place and answer calls. Personal calls keep working as normal.", "Set as default", actions.setDefaultDialer)
        }
        if (state.missingPermissions) {
            Banner("Allow contacts and call history", "So you can see who's calling and your recent calls.", "Allow", actions.requestPermissions)
        }
    }
}

@Composable
fun Banner(title: String, text: String, action: String, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
            }
            Spacer(Modifier.width(10.dp))
            Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) { Text(action) }
        }
    }
}

// ---- Favourites ------------------------------------------------------------------------------

@Composable
private fun Favourites(state: HomeState, actions: HomeActions) {
    val starred = state.contacts.filter { it.starred }
    val frequent = state.recents.groupBy { PhoneData.digits(it.number) }.entries
        .sortedByDescending { it.value.size }.take(8).map { it.value.first() }
        .filter { e -> starred.none { c -> c.numbers.any { PhoneData.digits(it.number) == PhoneData.digits(e.number) } } }
    if (starred.isEmpty() && frequent.isEmpty()) {
        Empty(Icons.Rounded.StarOutline, "No favourites yet", "Star a contact in your contacts app, and people you call often appear here.")
        return
    }
    LazyVerticalGrid(columns = GridCells.Fixed(3), contentPadding = PaddingValues(12.dp), modifier = Modifier.fillMaxSize()) {
        items(starred, key = { "s${it.id}" }) { c -> FavTile(c.name, c.numbers.first().number) { actions.call(c.numbers.first().number) } }
        if (frequent.isNotEmpty()) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) { SectionLabel("Frequently called", Modifier.padding(top = 8.dp)) }
            items(frequent, key = { "f${it.id}" }) { e -> FavTile(e.name ?: formatNumber(e.number), e.number) { actions.call(e.number) } }
        }
    }
}

@Composable
private fun FavTile(name: String, number: String, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(vertical = 14.dp, horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Avatar(name, number, size = 72.dp)
        Spacer(Modifier.height(8.dp))
        Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---- Recents --------------------------------------------------------------------------------

@Composable
private fun Recents(state: HomeState, actions: HomeActions) {
    if (state.recents.isEmpty()) {
        Empty(Icons.Rounded.AccessTime, "No recent calls", "Calls you make and receive show up here.")
        return
    }
    // Consecutive calls with the same number collapse into one row, like the Google dialer.
    val rows = remember(state.recents) {
        val out = mutableListOf<Pair<PhoneData.CallEntry, Int>>()
        for (e in state.recents) {
            val last = out.lastOrNull()
            if (last != null && PhoneData.digits(last.first.number) == PhoneData.digits(e.number) && sameDay(last.first.date, e.date) && last.first.missed == e.missed) {
                out[out.lastIndex] = last.first to last.second + 1
            } else out.add(e to 1)
        }
        out
    }
    var expanded by remember { mutableStateOf<Long?>(null) }
    val accounts = state.accounts.toMap()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        var lastDay = ""
        rows.forEach { (e, count) ->
            val day = dayLabel(e.date)
            if (day != lastDay) {
                lastDay = day
                item(key = "d$day${e.id}") { SectionLabel(day) }
            }
            item(key = e.id) {
                RecentRow(e, count, if (accounts.size > 1) accounts[e.accountId] else null, expanded == e.id, { expanded = if (expanded == e.id) null else e.id }, actions)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentRow(e: PhoneData.CallEntry, count: Int, sim: String?, open: Boolean, toggle: () -> Unit, actions: HomeActions) {
    val title = e.name ?: formatNumber(e.number).ifBlank { "Private number" }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(20.dp)).background(if (open) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent)) {
        Row(Modifier.fillMaxWidth().combinedClickable(onClick = toggle, onLongClick = toggle).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(e.name, e.number)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title + if (count > 1) " ($count)" else "", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, color = if (e.missed) Brand.Red else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (icon, tint) = when {
                        e.missed -> Icons.AutoMirrored.Rounded.CallMissed to Brand.Red
                        e.outgoing -> Icons.AutoMirrored.Rounded.CallMade to MaterialTheme.colorScheme.onSurfaceVariant
                        else -> Icons.AutoMirrored.Rounded.CallReceived to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Icon(icon, null, Modifier.size(15.dp), tint = tint)
                    Spacer(Modifier.width(4.dp))
                    Text(listOfNotNull(if (e.missed) "Missed" else if (e.outgoing) "Outgoing" else "Incoming", sim, timeOfDay(e.date)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = { actions.call(e.number) }, enabled = e.number.isNotBlank()) { Icon(Icons.Rounded.Call, "Call", tint = MaterialTheme.colorScheme.primary) }
        }
        AnimatedVisibility(open) {
            Row(Modifier.fillMaxWidth().padding(start = 70.dp, end = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionChip(Icons.AutoMirrored.Rounded.Message, "Message") { actions.message(e.number) }
                if (e.name == null) ActionChip(Icons.Rounded.PersonAdd, "Add contact") { actions.addContact(e.number) }
                if (e.duration > 0) Text(durationText(e.duration), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically))
            }
        }
    }
}

@Composable
fun ActionChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ---- Contacts -------------------------------------------------------------------------------

@Composable
private fun ContactsList(contacts: List<PhoneData.Contact>, actions: HomeActions) {
    if (contacts.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            Empty(Icons.Rounded.PersonOutline, "No contacts", "Contacts saved on this phone appear here.")
            TextButton(onClick = { actions.addContact("") }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Create contact") }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item {
            Row(Modifier.fillMaxWidth().clickable { actions.addContact("") }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.PersonAdd, null, tint = MaterialTheme.colorScheme.primary) }
                Spacer(Modifier.width(14.dp))
                Text("Create new contact", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        var letter = ' '
        contacts.forEach { c ->
            val first = c.name.firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() } ?: '#'
            if (first != letter) {
                letter = first
                item(key = "h$first") { SectionLabel(first.toString()) }
            }
            item(key = c.id) { ContactRow(c, actions) }
        }
    }
}

@Composable
private fun ContactRow(c: PhoneData.Contact, actions: HomeActions) {
    Row(Modifier.fillMaxWidth().clickable { actions.openContact(c) }.padding(horizontal = 20.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(c.name, c.numbers.first().number)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${c.numbers.first().label} · ${formatNumber(c.numbers.first().number)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        IconButton(onClick = { actions.call(c.numbers.first().number) }) { Icon(Icons.Rounded.Call, "Call", tint = MaterialTheme.colorScheme.primary) }
    }
}

// ---- Search ---------------------------------------------------------------------------------

@Composable
private fun SearchResults(query: String, state: HomeState, actions: HomeActions) {
    val q = query.trim().lowercase()
    val digits = q.filter(Char::isDigit)
    val contacts = remember(q, state.contacts) {
        if (q.isEmpty()) emptyList() else state.contacts.filter { c -> c.name.lowercase().contains(q) || (digits.length >= 3 && c.numbers.any { it.number.filter(Char::isDigit).contains(digits) }) }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        if (digits.length >= 3 && contacts.isEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().clickable { actions.call(query) }.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Call, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Text("Call $query", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        items(contacts, key = { it.id }) { ContactRow(it, actions) }
        if (q.isNotEmpty() && contacts.isEmpty() && digits.length < 3) item { Empty(Icons.Rounded.Search, "No matches", "Try a name or at least 3 digits of a number.") }
    }
}

@Composable
fun Empty(icon: ImageVector, title: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

private fun sameDay(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}

private fun dayLabel(millis: Long): String {
    val now = System.currentTimeMillis()
    return when {
        sameDay(millis, now) -> "Today"
        sameDay(millis, now - 86_400_000) -> "Yesterday"
        else -> SimpleDateFormat("EEEE, d MMM", Locale.getDefault()).format(Date(millis))
    }
}

private fun timeOfDay(millis: Long): String =
    if (System.currentTimeMillis() - millis < 3_600_000) relativeTime(millis) else java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(Date(millis))

@Suppress("unused")
private val keepCallLogImport = CallLog.Calls.MISSED_TYPE
