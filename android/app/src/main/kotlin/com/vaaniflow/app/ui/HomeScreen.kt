package com.vaaniflow.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonOutline
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaaniflow.app.ai.AiSettings
import com.vaaniflow.app.data.PhoneData
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class HomeTab(val label: String, val icon: ImageVector, val title: String) {
    FAVOURITES("Favourites", Icons.Rounded.Star, "Favourites"),
    RECENTS("Recents", Icons.Rounded.AccessTime, "Recents"),
    CONTACTS("Contacts", Icons.Rounded.Contacts, "Contacts"),
    ASSISTANT("AI", Icons.Rounded.AutoAwesome, "VaaniFlow AI"),
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
        topBar = {
            TopArea(tab, query, searching, { query = it }, { searching = it; if (!it) query = "" }, state.setup) { tab = HomeTab.ASSISTANT; searching = false }
        },
        bottomBar = { BottomNav(tab) { tab = it; searching = false; query = "" } },
        floatingActionButton = {
            if (tab != HomeTab.ASSISTANT && !searching) {
                FloatingActionButton(
                    onClick = { dialpadOpen = true },
                    containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(20.dp), elevation = FloatingActionButtonDefaults.elevation(6.dp, 8.dp),
                    modifier = Modifier.size(60.dp),
                ) { Icon(Icons.Rounded.Dialpad, "Keypad", Modifier.size(26.dp)) }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!searching && (state.missingPermissions || !state.isDefaultDialer)) Banners(state, actions)
            when {
                searching -> SearchResults(query, state, actions)
                tab == HomeTab.FAVOURITES -> Favourites(state, actions, QuickActions({ dialpadOpen = true }, { tab = HomeTab.RECENTS }, { tab = HomeTab.ASSISTANT }, { actions.addContact("") }))
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

// ---- Top area -------------------------------------------------------------------------------

@Composable
private fun TopArea(tab: HomeTab, query: String, searching: Boolean, onQuery: (String) -> Unit, onSearching: (Boolean) -> Unit, setup: SetupState, openAssistant: () -> Unit) {
    val t = Vaani.tokens
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(Brand.gradient), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.GraphicEq, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(if (searching) "Search" else tab.title, style = MaterialTheme.typography.headlineSmall, fontSize = 24.sp, modifier = Modifier.weight(1f), maxLines = 1)
            // AI status at a glance; tap opens VaaniFlow AI.
            val (label, color) = when {
                !setup.paired -> "Set up AI" to t.warning
                setup.connected -> "AI live" to t.success
                else -> "AI offline" to t.danger
            }
            Box(Modifier.clip(RoundedCornerShape(50)).clickable { openAssistant() }) { StatusPill(label, color) }
        }
        if (tab != HomeTab.ASSISTANT || searching) {
            Spacer(Modifier.height(14.dp))
            val focusBorder by animateColorAsState(if (searching) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f) else t.hairline, label = "searchBorder")
            Row(
                Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(14.dp)).background(t.card).border(1.dp, focusBorder, RoundedCornerShape(14.dp))
                    .clickable(enabled = !searching) { onSearching(true) }.padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, Modifier.size(20.dp), tint = t.muted)
                Spacer(Modifier.width(10.dp))
                if (searching) {
                    val focus = remember { FocusRequester() }
                    BasicTextField(
                        value = query, onValueChange = onQuery, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp),
                        cursorBrush = SolidColor(Vaani.tokens.accent),
                        modifier = Modifier.weight(1f).focusRequester(focus),
                        decorationBox = { inner -> Box { if (query.isEmpty()) Text("Name or number", color = t.subtle, fontSize = 15.sp); inner() } },
                    )
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    IconButton(onClick = { onSearching(false) }) { Icon(Icons.Rounded.Close, "Close search", tint = t.muted) }
                } else {
                    Text("Search contacts and numbers", color = t.subtle, fontSize = 15.sp, modifier = Modifier.weight(1f))
                }
            }
        }
    }
    BrandStripe(Modifier.alpha(0.85f))
    }
}

@Composable
private fun BottomNav(tab: HomeTab, onSelect: (HomeTab) -> Unit) {
    val t = Vaani.tokens
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp,
        modifier = Modifier.drawBehind { drawLine(t.hairline, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) },
    ) {
        HomeTab.entries.forEach { item ->
            NavigationBarItem(
                selected = tab == item,
                onClick = { onSelect(item) },
                icon = { Icon(item.icon, null, Modifier.size(22.dp)) },
                label = { Text(item.label, maxLines = 1, fontSize = 12.sp, fontWeight = if (tab == item) FontWeight.SemiBold else FontWeight.Medium) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    selectedIconColor = MaterialTheme.colorScheme.onSurface, selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    unselectedIconColor = t.subtle, unselectedTextColor = t.muted,
                ),
            )
        }
    }
}

// ---- Banners --------------------------------------------------------------------------------

@Composable
private fun Banners(state: HomeState, actions: HomeActions) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!state.isDefaultDialer) {
            if (state.dialerBlocked) {
                Banner(
                    Icons.Rounded.GppMaybe, Vaani.tokens.warning, "Android blocked the request",
                    "VaaniFlow was installed outside Play Store, so Android wants your OK before it can be the phone app. Takes 30 seconds.",
                    "Show me how", { actions.dialerHelp(true) }, "Try again", actions.setDefaultDialer,
                )
            } else {
                Banner(Icons.Rounded.PhoneAndroid, Vaani.tokens.accent, "Make VaaniFlow your phone app", "Needed to place and answer calls. Personal calls keep working as normal.", "Set as default", actions.setDefaultDialer)
            }
        }
        if (state.missingPermissions) {
            Banner(Icons.Rounded.Contacts, Vaani.tokens.accent, "Allow contacts and call history", "So you can see who's calling and your recent calls.", "Allow", actions.requestPermissions)
        }
    }
}

@Composable
fun Banner(icon: ImageVector, tint: Color, title: String, text: String, action: String, onClick: () -> Unit, secondary: String? = null, onSecondary: () -> Unit = {}) {
    VCard(padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            IconTile(icon, tint = tint, background = tint.copy(alpha = 0.10f), size = 38.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontSize = 15.sp)
                Spacer(Modifier.height(3.dp))
                Text(text, style = MaterialTheme.typography.bodySmall, color = Vaani.tokens.muted, lineHeight = 18.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            if (secondary != null) PillButton(secondary, onSecondary, style = PillStyle.Secondary, compact = true)
            PillButton(action, onClick, compact = true)
        }
    }
}

/** Step-by-step help for Android's "restricted settings" block on sideloaded apps. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DialerHelpSheet(actions: HomeActions) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val t = Vaani.tokens
    ModalBottomSheet(onDismissRequest = { actions.dialerHelp(false) }, sheetState = sheet, containerColor = t.card, dragHandle = null, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 18.dp)) {
            Eyebrow("One-time permission")
            Spacer(Modifier.height(10.dp))
            Text("Allow VaaniFlow to be your phone app", style = MaterialTheme.typography.headlineSmall, fontSize = 22.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                "Android protects apps installed from an APK. It shows \"App was denied access\" until you allow restricted settings once for VaaniFlow.",
                style = MaterialTheme.typography.bodyMedium, color = t.muted,
            )
            Spacer(Modifier.height(20.dp))
            VCard {
                HelpStep(1, Icons.Rounded.Settings, "Open App info", "Tap the button below. It opens VaaniFlow's settings page.")
                CardDivider(64.dp)
                HelpStep(2, Icons.Rounded.MoreVert, "Tap ⋮ at the top right", "Choose \"Allow restricted settings\" and confirm with your PIN or fingerprint.")
                CardDivider(64.dp)
                HelpStep(3, Icons.Rounded.TouchApp, "Come back and tap Set as default", "Pick VaaniFlow in the list. Done.")
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(t.cardRaised).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Lock, null, Modifier.size(16.dp), tint = t.muted)
                Spacer(Modifier.width(10.dp))
                Text(
                    "No ⋮ menu? Tap Set as default once more, then reopen App info. Installing from Google Play skips this step.",
                    style = MaterialTheme.typography.bodySmall, color = t.muted,
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Set as default", { actions.dialerHelp(false); actions.setDefaultDialer() }, Modifier.weight(1f), style = PillStyle.Secondary)
                PillButton("Open App info", actions.openAppInfo, Modifier.weight(1f), icon = Icons.Rounded.Settings)
            }
        }
    }
}

@Composable
private fun HelpStep(n: Int, icon: ImageVector, title: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Text("$n", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(icon, null, Modifier.size(18.dp), tint = Vaani.tokens.subtle)
            }
            Spacer(Modifier.height(2.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = Vaani.tokens.muted)
        }
    }
}

// ---- Favourites ------------------------------------------------------------------------------

@Composable
private fun Favourites(state: HomeState, actions: HomeActions, quick: QuickActions) {
    val starred = state.contacts.filter { it.starred }
    val starredDigits = remember(starred) { starred.flatMap { c -> c.numbers.map { PhoneData.digits(it.number) } }.toSet() }
    val frequent = remember(state.recents, starredDigits) {
        state.recents.filter { it.number.isNotBlank() }.groupBy { PhoneData.digits(it.number) }
            .filterKeys { it !in starredDigits }.entries
            .sortedByDescending { it.value.size }.take(9)
            .map { g -> g.value.firstOrNull { it.name != null } ?: g.value.first() }
    }
    LazyVerticalGrid(columns = GridCells.Fixed(3), contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 100.dp), modifier = Modifier.fillMaxSize()) {
        item(span = { GridItemSpan(3) }) { Greeting(state, quick) }
        if (starred.isEmpty() && frequent.isEmpty()) {
            item(span = { GridItemSpan(3) }) { Empty(Icons.Rounded.StarOutline, "No favourites yet", "Star a contact in your contacts app. People you call often show up here too.") }
        }
        if (starred.isNotEmpty()) {
            item(span = { GridItemSpan(3) }) { SectionLabel("Starred", trailing = "${starred.size}") }
            items(starred, key = { "s${it.id}" }) { c -> FavTile(c.name, c.numbers.first().number, c.numbers.first().label) { actions.call(c.numbers.first().number) } }
        }
        if (frequent.isNotEmpty()) {
            item(span = { GridItemSpan(3) }) { SectionLabel("Frequently called") }
            items(frequent, key = { "f${it.id}" }) { e -> FavTile(e.name ?: formatNumber(e.number), e.number, if (e.name != null) formatNumber(e.number) else "Unsaved") { actions.call(e.number) } }
        }
    }
}

private class QuickActions(val keypad: () -> Unit, val recents: () -> Unit, val assistant: () -> Unit, val newContact: () -> Unit)

/** Greeting card with today's date and missed calls, then quick-action orbs (Rajnath home pattern). */
@Composable
private fun Greeting(state: HomeState, quick: QuickActions) {
    val t = Vaani.tokens
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val hello = when (hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }
    val today = remember { SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date()) }
    val now = System.currentTimeMillis()
    val missed = state.recents.count { it.missed && sameDay(it.date, now) }
    Column(Modifier.padding(horizontal = 6.dp)) {
        Spacer(Modifier.height(10.dp))
        VCard {
            Row(
                Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color.Transparent, t.accent.copy(alpha = if (t.dark) 0.12f else 0.07f)))).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("$hello 🙏", style = MaterialTheme.typography.titleLarge, fontSize = 20.sp)
                    Spacer(Modifier.height(3.dp))
                    Row {
                        Text(today, style = MaterialTheme.typography.bodySmall, color = t.muted)
                        if (missed > 0) Text("  ·  $missed missed today", style = MaterialTheme.typography.bodySmall, color = t.danger)
                    }
                }
                Surface(onClick = quick.recents, shape = CircleShape, color = t.cardRaised, border = androidx.compose.foundation.BorderStroke(1.dp, t.hairline), modifier = Modifier.size(42.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.AccessTime, "Recents", Modifier.size(20.dp), tint = t.accent) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            QuickOrb(Icons.Rounded.Dialpad, "Keypad", quick.keypad)
            QuickOrb(Icons.Rounded.PersonAdd, "New contact", quick.newContact)
            QuickOrb(Icons.AutoMirrored.Rounded.CallMissed, "Missed", quick.recents)
            QuickOrb(Icons.Rounded.AutoAwesome, "AI", quick.assistant)
        }
    }
}

@Composable
private fun QuickOrb(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.width(78.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconOrb(icon, size = 54.dp)
        Spacer(Modifier.height(7.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FavTile(name: String, number: String, caption: String, onClick: () -> Unit) {
    Column(
        Modifier.padding(4.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Avatar(name, number, size = 66.dp)
        Spacer(Modifier.height(10.dp))
        Text(name, style = MaterialTheme.typography.titleSmall, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(caption, style = MaterialTheme.typography.bodySmall, fontSize = 11.5.sp, color = Vaani.tokens.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---- Recents --------------------------------------------------------------------------------

@Composable
private fun Recents(state: HomeState, actions: HomeActions) {
    if (state.recents.isEmpty()) {
        Empty(Icons.Rounded.AccessTime, "No recent calls", "Calls you make and receive show up here.")
        return
    }
    // Consecutive calls with the same number collapse into one row, like the Google dialer; rows are grouped by day.
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
    val accounts = state.accounts.toMap()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp)) {
        days.forEach { (day, rows) ->
            item(key = "d$day") {
                SectionLabel(day, trailing = "${rows.sumOf { it.second }} ${if (rows.sumOf { it.second } == 1) "call" else "calls"}")
                VCard(Modifier.padding(horizontal = 16.dp)) {
                    rows.forEachIndexed { i, (e, count) ->
                        if (i > 0) CardDivider(72.dp)
                        RecentRow(e, count, if (accounts.size > 1) accounts[e.accountId] else null, expanded == e.id, { expanded = if (expanded == e.id) null else e.id }, actions)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentRow(e: PhoneData.CallEntry, count: Int, sim: String?, open: Boolean, toggle: () -> Unit, actions: HomeActions) {
    val t = Vaani.tokens
    val title = e.name ?: formatNumber(e.number).ifBlank { "Private number" }
    Column(Modifier.fillMaxWidth().background(if (open) t.cardRaised else Color.Transparent)) {
        Row(Modifier.fillMaxWidth().combinedClickable(onClick = toggle, onLongClick = toggle).padding(start = 14.dp, end = 10.dp, top = 11.dp, bottom = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(e.name, e.number, size = 42.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontSize = 15.sp, color = if (e.missed) t.danger else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (count > 1) {
                        Spacer(Modifier.width(6.dp))
                        Text("$count", style = MaterialTheme.typography.labelMedium, color = t.muted, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(t.cardRaised).border(1.dp, t.hairline, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
                    }
                }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (icon, tint) = when {
                        e.missed -> Icons.AutoMirrored.Rounded.CallMissed to t.danger
                        e.outgoing -> Icons.AutoMirrored.Rounded.CallMade to t.muted
                        else -> Icons.AutoMirrored.Rounded.CallReceived to t.success
                    }
                    Icon(icon, null, Modifier.size(14.dp), tint = tint)
                    Spacer(Modifier.width(5.dp))
                    Text(listOfNotNull(if (e.missed) "Missed" else if (e.outgoing) "Outgoing" else "Incoming", sim, timeOfDay(e.date)).joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = t.muted, maxLines = 1)
                }
            }
            CallIconButton(enabled = e.number.isNotBlank()) { actions.call(e.number) }
        }
        AnimatedVisibility(open) {
            Row(Modifier.fillMaxWidth().padding(start = 70.dp, end = 14.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton("Message", { actions.message(e.number) }, style = PillStyle.Secondary, icon = Icons.AutoMirrored.Rounded.Message, compact = true)
                if (e.name == null) PillButton("Add contact", { actions.addContact(e.number) }, style = PillStyle.Secondary, icon = Icons.Rounded.PersonAdd, compact = true)
                if (e.duration > 0) Text(durationText(e.duration), style = MaterialTheme.typography.labelMedium, color = t.muted)
            }
        }
    }
}

@Composable
private fun CallIconButton(enabled: Boolean = true, onClick: () -> Unit) {
    val t = Vaani.tokens
    Surface(onClick = onClick, enabled = enabled, shape = CircleShape, color = t.cardRaised, border = androidx.compose.foundation.BorderStroke(1.dp, t.hairline), modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Call, "Call", Modifier.size(19.dp), tint = if (enabled) t.success else t.subtle) }
    }
}

/** Kept for other screens that need a small action pill. */
@Composable
fun ActionChip(icon: ImageVector, label: String, onClick: () -> Unit) = PillButton(label, onClick, style = PillStyle.Secondary, icon = icon, compact = true)

// ---- Contacts -------------------------------------------------------------------------------

@Composable
private fun ContactsList(contacts: List<PhoneData.Contact>, actions: HomeActions) {
    if (contacts.isEmpty()) {
        Empty(Icons.Rounded.PersonOutline, "No contacts", "Contacts saved on this phone appear here.") {
            PillButton("Create contact", { actions.addContact("") }, icon = Icons.Rounded.PersonAdd)
        }
        return
    }
    val groups = remember(contacts) { contacts.groupBy { c -> c.name.firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() } ?: '#' }.toList() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp)) {
        item(key = "create") {
            VCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
                ListRow(
                    "Create new contact", "${contacts.size} contacts on this phone",
                    Modifier.clickable { actions.addContact("") },
                    leading = { IconTile(Icons.Rounded.PersonAdd, tint = Vaani.tokens.accent, background = Vaani.tokens.accent.copy(alpha = 0.10f), size = 42.dp) },
                )
            }
        }
        groups.forEach { (letter, list) ->
            item(key = "h$letter") {
                SectionLabel(letter.toString())
                VCard(Modifier.padding(horizontal = 16.dp)) {
                    list.forEachIndexed { i, c ->
                        if (i > 0) CardDivider(72.dp)
                        ContactRow(c, actions)
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactRow(c: PhoneData.Contact, actions: HomeActions) {
    val n = c.numbers.first()
    ListRow(
        c.name, "${n.label}  ·  ${formatNumber(n.number)}",
        Modifier.clickable { actions.openContact(c) },
        leading = { Avatar(c.name, n.number, size = 42.dp) },
        trailing = { CallIconButton { actions.call(n.number) } },
    )
}

// ---- Search ---------------------------------------------------------------------------------

@Composable
private fun SearchResults(query: String, state: HomeState, actions: HomeActions) {
    val q = query.trim().lowercase()
    val digits = q.filter(Char::isDigit)
    val contacts = remember(q, state.contacts) {
        if (q.isEmpty()) emptyList() else state.contacts.filter { c -> c.name.lowercase().contains(q) || (digits.length >= 3 && c.numbers.any { it.number.filter(Char::isDigit).contains(digits) }) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 100.dp)) {
        if (digits.length >= 3) {
            item {
                VCard(Modifier.padding(horizontal = 16.dp)) {
                    ListRow(
                        "Call ${formatNumber(query.trim())}", "Dial this number",
                        Modifier.clickable { actions.call(query.trim()) },
                        leading = { IconTile(Icons.Rounded.Call, tint = Vaani.tokens.success, background = Vaani.tokens.success.copy(alpha = 0.10f), size = 42.dp) },
                    )
                }
            }
        }
        if (contacts.isNotEmpty()) {
            item {
                SectionLabel("Contacts", trailing = "${contacts.size}")
                VCard(Modifier.padding(horizontal = 16.dp)) {
                    contacts.take(60).forEachIndexed { i, c ->
                        if (i > 0) CardDivider(72.dp)
                        ContactRow(c, actions)
                    }
                }
            }
        }
        if (q.isNotEmpty() && contacts.isEmpty() && digits.length < 3) item { Empty(Icons.Rounded.Search, "No matches", "Try a name or at least 3 digits of a number.") }
    }
}

@Composable
fun Empty(icon: ImageVector, title: String, text: String, action: (@Composable () -> Unit)? = null) {
    val t = Vaani.tokens
    Column(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(76.dp).clip(RoundedCornerShape(24.dp)).background(t.card).border(1.dp, t.hairline, RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(t.accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(24.dp), tint = t.accent)
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = t.muted, textAlign = TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(18.dp)); action() }
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
