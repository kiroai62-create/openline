package com.vaaniflow.app.ui

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallMissed
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.rounded.AccessTimeFilled
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonOutline
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vaaniflow.app.ai.AiSettings
import com.vaaniflow.app.data.PhoneData
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Bottom tabs, Google Phone order, with VaaniFlow AI added on the right. */
enum class HomeTab(val label: String, val icon: ImageVector, val iconOff: ImageVector) {
    FAVOURITES("Favourites", Icons.Rounded.Star, Icons.Outlined.StarOutline),
    RECENTS("Recents", Icons.Rounded.AccessTimeFilled, Icons.Outlined.AccessTime),
    CONTACTS("Contacts", Icons.Rounded.Contacts, Icons.Outlined.Contacts),
    ASSISTANT("AI", Icons.Rounded.AutoAwesome, Icons.Outlined.AutoAwesome),
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
    /** Android refused the default-phone-app request because the APK was sideloaded (restricted settings). */
    val dialerBlocked: Boolean = false,
    val showDialerHelp: Boolean = false,
    /** Android 14+ turned off full-screen incoming calls for this app. */
    val fullScreenBlocked: Boolean = false,
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
    val openAppInfo: () -> Unit = {},
    val dialerHelp: (Boolean) -> Unit = {},
    val allowFullScreen: () -> Unit = {},
)

@Composable
fun HomeScreen(state: HomeState, actions: HomeActions, initialTab: HomeTab, dialNumber: String?, onDialConsumed: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var dialpadOpen by rememberSaveable { mutableStateOf(dialNumber != null) }
    if (dialNumber != null && !dialpadOpen) dialpadOpen = true
    val t = Vaani.tokens

    Scaffold(
        containerColor = t.background,
        topBar = {
            if (tab == HomeTab.ASSISTANT && !searching) AssistantTopBar(state.setup)
            else SearchBar(query, searching, { query = it }, { searching = it; if (!it) query = "" }, openRecents = { tab = HomeTab.RECENTS }, openAi = { tab = HomeTab.ASSISTANT })
        },
        bottomBar = {
            if (!searching) NavigationBar(containerColor = t.background, tonalElevation = 0.dp) {
                HomeTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item; query = "" },
                        icon = { Icon(if (tab == item) item.icon else item.iconOff, null) },
                        label = { Text(item.label, maxLines = 1) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab != HomeTab.ASSISTANT && !searching) {
                FloatingActionButton(onClick = { dialpadOpen = true }, shape = RoundedCornerShape(16.dp)) { Icon(Icons.Rounded.Dialpad, "Keypad") }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!searching && (state.missingPermissions || !state.isDefaultDialer || (state.isDefaultDialer && state.fullScreenBlocked))) Banners(state, actions)
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
    if (state.showDialerHelp) DialerHelpSheet(actions)
}

// ---- Top bar --------------------------------------------------------------------------------

/** Google Phone's rounded search bar with the overflow menu; turns into a search field. */
@Composable
private fun SearchBar(query: String, searching: Boolean, onQuery: (String) -> Unit, onSearching: (Boolean) -> Unit, openRecents: () -> Unit, openAi: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().background(Vaani.tokens.background).statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(cs.surfaceContainerHigh)
                .clickable(enabled = !searching) { onSearching(true) }.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (searching) {
                IconButton(onClick = { onSearching(false) }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = cs.onSurface) }
                val focus = remember { FocusRequester() }
                BasicTextField(
                    value = query, onValueChange = onQuery, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                    cursorBrush = SolidColor(cs.primary),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    decorationBox = { inner -> Box { if (query.isEmpty()) Text("Search contacts", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant); inner() } },
                )
                LaunchedEffect(Unit) { focus.requestFocus() }
                if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, "Clear", tint = cs.onSurfaceVariant) }
            } else {
                Icon(Icons.Rounded.Search, null, Modifier.padding(start = 12.dp, end = 16.dp), tint = cs.onSurfaceVariant)
                Text("Search contacts", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More options", tint = cs.onSurfaceVariant) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Call history") }, onClick = { menu = false; openRecents() })
                        DropdownMenuItem(text = { Text("AI settings") }, onClick = { menu = false; openAi() })
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantTopBar(setup: SetupState) {
    val t = Vaani.tokens
    Row(Modifier.fillMaxWidth().background(t.background).statusBarsPadding().padding(start = 24.dp, end = 16.dp, top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("VaaniFlow AI", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        val (label, color) = when {
            !setup.paired -> "Not set up" to t.warning
            setup.connected -> "Online" to t.success
            else -> "Offline" to t.danger
        }
        StatusPill(label, color)
    }
}

// ---- Banners --------------------------------------------------------------------------------

@Composable
private fun Banners(state: HomeState, actions: HomeActions) {
    Column(Modifier.padding(top = 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!state.isDefaultDialer) {
            if (state.dialerBlocked) {
                Banner(
                    Icons.Rounded.GppMaybe, "Allow VaaniFlow to be your phone app",
                    "Android blocked it because the app was installed from a file. Allow it once in App info.",
                    "Show me how", { actions.dialerHelp(true) }, "Try again", actions.setDefaultDialer,
                )
            } else {
                Banner(Icons.Rounded.PhoneAndroid, "Set as default phone app", "To make and answer calls with this app.", "Set as default", actions.setDefaultDialer)
            }
        }
        if (state.isDefaultDialer && state.fullScreenBlocked) {
            Banner(Icons.Rounded.PhoneAndroid, "Show incoming calls on the full screen", "Android turned this off for this app, so calls only appear as a notification.", "Allow", actions.allowFullScreen)
        }
        if (state.missingPermissions) {
            Banner(Icons.Rounded.Contacts, "Allow access to contacts and call history", "To show who's calling and your recent calls.", "Allow", actions.requestPermissions)
        }
    }
}

@Composable
fun Banner(icon: ImageVector, title: String, text: String, action: String, onClick: () -> Unit, secondary: String? = null, onSecondary: () -> Unit = {}) {
    Segment {
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                IconTile(icon, size = 36.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = Vaani.tokens.muted)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                if (secondary != null) TextButton(onClick = onSecondary) { Text(secondary) }
                TextButton(onClick = onClick) { Text(action) }
            }
        }
    }
}

/** Step-by-step help for Android's "restricted settings" block on sideloaded apps. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DialerHelpSheet(actions: HomeActions) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = { actions.dialerHelp(false) }, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 20.dp)) {
            Text("Allow VaaniFlow to be your phone app", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "Android shows \"App was denied access\" for apps installed from a file until you allow restricted settings once.",
                style = MaterialTheme.typography.bodyMedium, color = Vaani.tokens.muted,
            )
            Spacer(Modifier.height(20.dp))
            HelpStep(1, "Open App info", "Tap the button below.")
            HelpStep(2, "Tap ⋮ at the top right", "Choose Allow restricted settings and confirm with your PIN or fingerprint.")
            HelpStep(3, "Come back and tap Set as default", "Pick VaaniFlow in the list.")
            Spacer(Modifier.height(8.dp))
            Text(
                "No ⋮ menu? Tap Set as default once more, then open App info again. Installing from Google Play skips this step.",
                style = MaterialTheme.typography.bodySmall, color = Vaani.tokens.muted,
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { actions.dialerHelp(false); actions.setDefaultDialer() }, modifier = Modifier.weight(1f)) { Text("Set as default") }
                Button(onClick = actions.openAppInfo, modifier = Modifier.weight(1f)) { Text("Open App info") }
            }
        }
    }
}

@Composable
private fun HelpStep(n: Int, title: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Text("$n", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = Vaani.tokens.muted)
        }
    }
}

// ---- Favourites ------------------------------------------------------------------------------

@Composable
private fun Favourites(state: HomeState, actions: HomeActions) {
    val starred = state.contacts.filter { it.starred }
    val starredDigits = remember(starred) { starred.flatMap { c -> c.numbers.map { PhoneData.digits(it.number) } }.toSet() }
    val frequent = remember(state.recents, starredDigits) {
        state.recents.filter { it.number.isNotBlank() }.groupBy { PhoneData.digits(it.number) }
            .filterKeys { it !in starredDigits }.entries
            .sortedByDescending { it.value.size }.take(8)
            .map { g -> g.value.firstOrNull { it.name != null } ?: g.value.first() }
    }
    if (starred.isEmpty() && frequent.isEmpty()) {
        Empty(Icons.Rounded.StarOutline, "No favourites yet", "Add contacts to favourites and they'll show up here.")
        return
    }
    LazyVerticalGrid(columns = GridCells.Fixed(3), contentPadding = PaddingValues(bottom = 96.dp), modifier = Modifier.fillMaxSize()) {
        if (starred.isNotEmpty()) {
            item(span = { GridItemSpan(3) }) { Spacer(Modifier.height(8.dp)) }
            items(starred, key = { "s${it.id}" }) { c -> FavTile(c.name, c.numbers.first().number) { actions.call(c.numbers.first().number) } }
        }
        if (frequent.isNotEmpty()) {
            item(span = { GridItemSpan(3) }) { SectionLabel("Frequents") }
            frequent.forEachIndexed { i, e ->
                item(span = { GridItemSpan(3) }, key = "f${e.id}") {
                    Surface(shape = segmentShape(i, frequent.size), color = Vaani.tokens.card, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = if (i > 0) 2.dp else 0.dp)) {
                        ListRow(
                            e.name ?: formatNumber(e.number), if (e.name != null) formatNumber(e.number) else null,
                            Modifier.clickable { actions.call(e.number) },
                            leading = { Avatar(e.name, e.number) },
                            trailing = { IconButton(onClick = { actions.call(e.number) }) { Icon(Icons.Rounded.Call, "Call", tint = MaterialTheme.colorScheme.onSurfaceVariant) } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FavTile(name: String, number: String, onClick: () -> Unit) {
    Column(Modifier.padding(4.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Avatar(name, number, size = 80.dp)
        Spacer(Modifier.height(8.dp))
        Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

// ---- Recents --------------------------------------------------------------------------------

@Composable
private fun Recents(state: HomeState, actions: HomeActions) {
    if (state.recents.isEmpty()) {
        Empty(Icons.Rounded.AccessTimeFilled, "No recent calls", "Your call history will appear here.")
        return
    }
    // Consecutive calls with the same number collapse into one row, like Google Phone; rows are grouped by day.
    val days = remember(state.recents) {
        val rows = mutableListOf<Pair<PhoneData.CallEntry, Int>>()
        for (e in state.recents) {
            val last = rows.lastOrNull()
            if (last != null && PhoneData.digits(last.first.number) == PhoneData.digits(e.number) && sameDay(last.first.date, e.date) && last.first.missed == e.missed) {
                rows[rows.lastIndex] = last.first to last.second + 1
            } else rows.add(e to 1)
        }
        rows.groupBy { dayLabel(it.first.date) }.toList()
    }
    var expanded by remember { mutableStateOf<Long?>(null) }
    var history by remember { mutableStateOf<PhoneData.CallEntry?>(null) }
    val accounts = state.accounts.toMap()
    history?.let { h -> HistorySheet(h, state.recents.filter { PhoneData.digits(it.number) == PhoneData.digits(h.number) }, if (accounts.size > 1) accounts else emptyMap(), actions) { history = null } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        days.forEach { (day, rows) ->
            item(key = "d$day") { SectionLabel(day) }
            rows.forEachIndexed { i, (e, count) ->
                item(key = e.id) {
                    Surface(shape = segmentShape(i, rows.size), color = Vaani.tokens.card, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = if (i > 0) 2.dp else 0.dp)) {
                        RecentRow(e, count, if (accounts.size > 1) accounts[e.accountId] else null, expanded == e.id, { expanded = if (expanded == e.id) null else e.id }, actions) { history = e }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentRow(e: PhoneData.CallEntry, count: Int, sim: String?, open: Boolean, toggle: () -> Unit, actions: HomeActions, showHistory: () -> Unit) {
    val t = Vaani.tokens
    val cs = MaterialTheme.colorScheme
    val title = (e.name ?: formatNumber(e.number).ifBlank { "Private number" }) + if (count > 1) " ($count)" else ""
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().combinedClickable(onClick = toggle, onLongClick = toggle).padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(e.name, e.number)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = if (e.missed) t.danger else cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (icon, tint) = when {
                        e.missed -> Icons.AutoMirrored.Rounded.CallMissed to t.danger
                        e.outgoing -> Icons.AutoMirrored.Rounded.CallMade to t.muted
                        else -> Icons.AutoMirrored.Rounded.CallReceived to t.muted
                    }
                    Icon(icon, null, Modifier.size(16.dp), tint = tint)
                    Spacer(Modifier.width(6.dp))
                    Text(listOfNotNull(sim, timeOfDay(e.date)).joinToString(" • "), style = MaterialTheme.typography.bodyMedium, color = t.muted, maxLines = 1)
                }
            }
            IconButton(onClick = { actions.call(e.number) }, enabled = e.number.isNotBlank()) { Icon(Icons.Rounded.Call, "Call", tint = cs.onSurfaceVariant) }
        }
        AnimatedVisibility(open) {
            Row(Modifier.fillMaxWidth().padding(start = 72.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                RowAction(Icons.AutoMirrored.Outlined.Message, "Message") { actions.message(e.number) }
                RowAction(Icons.Outlined.AccessTime, "History", showHistory)
                if (e.name == null) RowAction(Icons.Outlined.PersonAdd, "Add contact") { actions.addContact(e.number) }
            }
        }
    }
}

/** Google Phone's "History": every call with this number, newest first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(e: PhoneData.CallEntry, calls: List<PhoneData.CallEntry>, sims: Map<String, String>, actions: HomeActions, onDismiss: () -> Unit) {
    val t = Vaani.tokens
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(e.name, e.number, size = 72.dp)
            Spacer(Modifier.height(12.dp))
            Text(e.name ?: formatNumber(e.number).ifBlank { "Private number" }, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
            if (e.name != null) Text(formatNumber(e.number), style = MaterialTheme.typography.bodyLarge, color = t.muted)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                SheetAction(Icons.Rounded.Call, "Call", e.number.isNotBlank()) { onDismiss(); actions.call(e.number) }
                SheetAction(Icons.AutoMirrored.Outlined.Message, "Message", e.number.isNotBlank()) { actions.message(e.number) }
                if (e.name == null) SheetAction(Icons.Outlined.PersonAdd, "Add contact", e.number.isNotBlank()) { actions.addContact(e.number) }
            }
            Spacer(Modifier.height(16.dp))
            SectionLabel("History")
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(calls.size) { i ->
                    val c = calls[i]
                    val (icon, tint, label) = when {
                        c.missed -> Triple(Icons.AutoMirrored.Rounded.CallMissed, t.danger, "Missed")
                        c.outgoing -> Triple(Icons.AutoMirrored.Rounded.CallMade, t.muted, "Outgoing")
                        else -> Triple(Icons.AutoMirrored.Rounded.CallReceived, t.muted, "Incoming")
                    }
                    Surface(shape = segmentShape(i, calls.size), color = t.card, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = if (i > 0) 2.dp else 0.dp)) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, null, Modifier.size(20.dp), tint = tint)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(label + (sims[c.accountId]?.let { " • $it" } ?: ""), style = MaterialTheme.typography.bodyLarge)
                                Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(Date(c.date)), style = MaterialTheme.typography.bodyMedium, color = t.muted)
                            }
                            if (c.duration > 0) Text(durationText(c.duration), style = MaterialTheme.typography.labelLarge, color = t.muted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(onClick = onClick, enabled = enabled, shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(56.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, label, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun RowAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick, label = { Text(label) },
        leadingIcon = { Icon(icon, null, Modifier.size(AssistChipDefaults.IconSize)) },
        shape = RoundedCornerShape(50),
    )
}

/** Kept for other screens that need a small action pill. */
@Composable
fun ActionChip(icon: ImageVector, label: String, onClick: () -> Unit) = RowAction(icon, label, onClick)

// ---- Contacts -------------------------------------------------------------------------------

@Composable
private fun ContactsList(contacts: List<PhoneData.Contact>, actions: HomeActions) {
    if (contacts.isEmpty()) {
        Empty(Icons.Rounded.PersonOutline, "No contacts", "Contacts saved on this phone appear here.") {
            Button(onClick = { actions.addContact("") }) { Text("Create new contact") }
        }
        return
    }
    val groups = remember(contacts) { contacts.groupBy { c -> c.name.firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() } ?: '#' }.toList() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item(key = "create") {
            Spacer(Modifier.height(8.dp))
            Segment {
                ListRow(
                    "Create new contact", null, Modifier.clickable { actions.addContact("") },
                    titleColor = MaterialTheme.colorScheme.primary,
                    leading = { IconTile(Icons.Rounded.PersonAdd, tint = MaterialTheme.colorScheme.onPrimaryContainer, background = MaterialTheme.colorScheme.primaryContainer) },
                )
            }
        }
        groups.forEach { (letter, list) ->
            item(key = "h$letter") { SectionLabel(letter.toString()) }
            list.forEachIndexed { i, c ->
                item(key = c.id) {
                    Surface(shape = segmentShape(i, list.size), color = Vaani.tokens.card, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = if (i > 0) 2.dp else 0.dp)) {
                        ContactRow(c, actions)
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactRow(c: PhoneData.Contact, actions: HomeActions, showNumber: Boolean = false) {
    val n = c.numbers.first()
    ListRow(
        c.name, if (showNumber) "${n.label} • ${formatNumber(n.number)}" else null,
        Modifier.clickable { actions.openContact(c) },
        leading = { Avatar(c.name, n.number) },
        trailing = { if (showNumber) IconButton(onClick = { actions.call(n.number) }) { Icon(Icons.Rounded.Call, "Call", tint = MaterialTheme.colorScheme.onSurfaceVariant) } },
    )
}

// ---- Search ---------------------------------------------------------------------------------

@Composable
private fun SearchResults(query: String, state: HomeState, actions: HomeActions) {
    val q = query.trim().lowercase()
    val digits = q.filter(Char::isDigit)
    val contacts = remember(q, state.contacts) {
        if (q.isEmpty()) emptyList() else state.contacts.filter { c -> c.name.lowercase().contains(q) || (digits.length >= 3 && c.numbers.any { it.number.filter(Char::isDigit).contains(digits) }) }.take(60)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp)) {
        if (contacts.isNotEmpty()) {
            item { SectionLabel("Contacts") }
            contacts.forEachIndexed { i, c ->
                item(key = c.id) {
                    Surface(shape = segmentShape(i, contacts.size), color = Vaani.tokens.card, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = if (i > 0) 2.dp else 0.dp)) {
                        ContactRow(c, actions, showNumber = true)
                    }
                }
            }
        }
        if (digits.length >= 3) {
            item {
                Spacer(Modifier.height(16.dp))
                Segment {
                    ListRow("Call ${formatNumber(query.trim())}", null, Modifier.clickable { actions.call(query.trim()) }, leading = { IconTile(Icons.Rounded.Call) })
                }
                Spacer(Modifier.height(2.dp))
                Segment {
                    ListRow("Create new contact", null, Modifier.clickable { actions.addContact(query.trim()) }, leading = { IconTile(Icons.Rounded.PersonAdd) })
                }
            }
        }
        if (q.isNotEmpty() && contacts.isEmpty() && digits.length < 3) item { Empty(Icons.Rounded.Search, "No results", "Try another name or number.") }
    }
}

@Composable
fun Empty(icon: ImageVector, title: String, text: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(56.dp), tint = Vaani.tokens.muted)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Vaani.tokens.muted, textAlign = TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(20.dp)); action() }
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
