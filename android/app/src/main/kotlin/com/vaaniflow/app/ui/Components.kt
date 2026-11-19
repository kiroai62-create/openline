package com.vaaniflow.app.ui

import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.absoluteValue
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.Icons

private val AvatarColors = listOf(
    Color(0xFF5B47FB) to Color(0xFFE9E6FF), Color(0xFF0FA894) to Color(0xFFD8F7F2), Color(0xFF3E7BF6) to Color(0xFFE2ECFF),
    Color(0xFFC2410C) to Color(0xFFFFE8DC), Color(0xFFBE185D) to Color(0xFFFCE4F1), Color(0xFF15803D) to Color(0xFFDDF6E5),
)

/** Letter avatar in a soft colour picked from the name, like the Google dialer. */
@Composable
fun Avatar(name: String?, number: String = "", size: Dp = 44.dp, @Suppress("UNUSED_PARAMETER") photo: Uri? = null) {
    val key = (name ?: number).ifBlank { "?" }
    val (fg, bg) = AvatarColors[key.hashCode().absoluteValue % AvatarColors.size]
    val letter = name?.trim()?.firstOrNull { it.isLetter() }?.uppercaseChar()?.toString()
    Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        if (letter != null) {
            Text(letter, color = fg, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.42f).sp)
        } else {
            androidx.compose.material3.Icon(Icons.Rounded.Person, null, tint = fg, modifier = Modifier.size(size * 0.55f))
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

fun formatNumber(raw: String): String {
    val d = raw.filter { it.isDigit() || it == '+' }
    val m = Regex("^(\\+91)?(\\d{5})(\\d{5})$").find(d) ?: return raw
    return listOfNotNull(m.groupValues[1].takeIf { it.isNotEmpty() }, m.groupValues[2], m.groupValues[3]).joinToString(" ")
}

fun relativeTime(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

fun durationText(seconds: Long): String = if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)
