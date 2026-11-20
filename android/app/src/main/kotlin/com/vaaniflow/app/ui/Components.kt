package com.vaaniflow.app.ui

import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.absoluteValue

// ---- Avatars --------------------------------------------------------------------------------

/** Google Contacts letter-avatar colours: (light bg, light letter, dark bg, dark letter). */
private val AvatarColors = listOf(
    listOf(0xFFD3E3FD, 0xFF041E49, 0xFF0842A0, 0xFFD3E3FD),
    listOf(0xFFC4EED0, 0xFF072711, 0xFF0F5223, 0xFFC4EED0),
    listOf(0xFFFFDBCB, 0xFF341100, 0xFF7A2E00, 0xFFFFDBCB),
    listOf(0xFFFFD8E4, 0xFF3E001D, 0xFF8A1A4A, 0xFFFFD8E4),
    listOf(0xFFE8DEF8, 0xFF1D192B, 0xFF4F378B, 0xFFE8DEF8),
    listOf(0xFFC2E7FF, 0xFF001D35, 0xFF004A77, 0xFFC2E7FF),
    listOf(0xFFFFDF99, 0xFF261A00, 0xFF5C4300, 0xFFFFDF99),
    listOf(0xFFB9F0E8, 0xFF00201C, 0xFF005048, 0xFFB9F0E8),
)

/** Letter avatar in a tonal colour picked from the name, like Google Phone. */
@Composable
fun Avatar(name: String?, number: String = "", size: Dp = 40.dp, @Suppress("UNUSED_PARAMETER") photo: Uri? = null) {
    val key = (name ?: number).ifBlank { "?" }
    val c = AvatarColors[key.hashCode().absoluteValue % AvatarColors.size]
    val dark = Vaani.tokens.dark
    val bg = Color(if (dark) c[2] else c[0])
    val fg = Color(if (dark) c[3] else c[1])
    val letter = name?.trim()?.firstOrNull { it.isLetter() }?.uppercaseChar()?.toString()
    Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        if (letter != null) Text(letter, color = fg, fontSize = (size.value * 0.45f).sp, fontWeight = FontWeight.Normal)
        else Icon(Icons.Rounded.Person, null, tint = fg, modifier = Modifier.size(size * 0.6f))
    }
}

// ---- Lists ----------------------------------------------------------------------------------

/** Group header ("Today", "A", "Frequents"). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelMedium, color = Vaani.tokens.muted)
    }
}

/** Shape for item [index] of [count] in a segmented list: big outer corners, small inner ones. */
fun segmentShape(index: Int, count: Int, outer: Dp = 20.dp, inner: Dp = 4.dp): Shape = RoundedCornerShape(
    topStart = if (index == 0) outer else inner, topEnd = if (index == 0) outer else inner,
    bottomStart = if (index == count - 1) outer else inner, bottomEnd = if (index == count - 1) outer else inner,
)

/** Google's segmented list: each row is its own rounded segment with a 2 dp gap. */
@Composable
fun SegmentedGroup(count: Int, modifier: Modifier = Modifier, item: @Composable ColumnScope.(Int) -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        repeat(count) { i ->
            if (i > 0) Spacer(Modifier.height(2.dp))
            Surface(shape = segmentShape(i, count), color = Vaani.tokens.card, modifier = Modifier.fillMaxWidth()) {
                Column { item(i) }
            }
        }
    }
}

/** A single rounded segment (a group of one). */
@Composable
fun Segment(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = Vaani.tokens.card, modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(content = content)
    }
}

/** Row: leading slot, title/subtitle, trailing slot. */
@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) { leading(); Spacer(Modifier.width(16.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Vaani.tokens.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
    }
}

/** Circular tonal icon for settings and action rows. */
@Composable
fun IconTile(icon: ImageVector, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSecondaryContainer, background: Color = MaterialTheme.colorScheme.secondaryContainer, size: Dp = 40.dp) {
    Box(modifier.size(size).clip(CircleShape).background(background), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, style = MaterialTheme.typography.labelMedium)
    }
}

// ---- Formatting ------------------------------------------------------------------------------

fun formatNumber(raw: String): String {
    val d = raw.filter { it.isDigit() || it == '+' }
    val m = Regex("^(\\+91)?(\\d{5})(\\d{5})$").find(d) ?: return raw
    return listOfNotNull(m.groupValues[1].takeIf { it.isNotEmpty() }, m.groupValues[2], m.groupValues[3]).joinToString(" ")
}

fun relativeTime(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

fun durationText(seconds: Long): String = if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)
