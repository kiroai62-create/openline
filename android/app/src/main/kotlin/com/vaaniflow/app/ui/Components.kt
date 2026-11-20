package com.vaaniflow.app.ui

import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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

private val AvatarHues = listOf(
    Color(0xFF6D5BFF), Color(0xFF0EA5A0), Color(0xFF3E7BF6), Color(0xFFE0703A),
    Color(0xFFD9467A), Color(0xFF22A06B), Color(0xFFB7791F), Color(0xFF8B5CF6),
)

/** Letter avatar: a quiet tinted disc with a hairline ring, coloured from the name. */
@Composable
fun Avatar(name: String?, number: String = "", size: Dp = 44.dp, @Suppress("UNUSED_PARAMETER") photo: Uri? = null) {
    val key = (name ?: number).ifBlank { "?" }
    val hue = AvatarHues[key.hashCode().absoluteValue % AvatarHues.size]
    val dark = Vaani.tokens.dark
    val letter = name?.trim()?.firstOrNull { it.isLetter() }?.uppercaseChar()?.toString()
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(Brush.linearGradient(listOf(hue.copy(alpha = if (dark) 0.30f else 0.16f), hue.copy(alpha = if (dark) 0.14f else 0.07f)), start = Offset.Zero, end = Offset.Infinite))
            .border(1.dp, hue.copy(alpha = if (dark) 0.35f else 0.22f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val fg = if (dark) hue.copy(alpha = 1f).lighten(0.35f) else hue.darken(0.15f)
        if (letter != null) Text(letter, color = fg, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.4f).sp, letterSpacing = 0.sp)
        else Icon(Icons.Rounded.Person, null, tint = fg, modifier = Modifier.size(size * 0.5f))
    }
}

private fun Color.lighten(f: Float) = Color(red + (1 - red) * f, green + (1 - green) * f, blue + (1 - blue) * f, alpha)
private fun Color.darken(f: Float) = Color(red * (1 - f), green * (1 - f), blue * (1 - f), alpha)

// ---- Type helpers ----------------------------------------------------------------------------

/** Eyebrow label: small uppercase text after a rotated accent square (Rajnath-style). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Eyebrow(text)
        Spacer(Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelMedium, color = Vaani.tokens.subtle)
    }
}

@Composable
fun Eyebrow(text: String, color: Color = Vaani.tokens.muted, mark: Brush = Brand.gradient) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).rotate(45f).clip(RoundedCornerShape(1.5.dp)).background(mark))
        Spacer(Modifier.width(9.dp))
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color, fontSize = 11.sp, letterSpacing = 1.4.sp)
    }
}

// ---- Surfaces --------------------------------------------------------------------------------

/** Card with a hairline border and a very soft shadow, like dub.co's panels. */
@Composable
fun VCard(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(18.dp), padding: PaddingValues = PaddingValues(0.dp), content: @Composable ColumnScope.() -> Unit) {
    val t = Vaani.tokens
    Column(
        modifier.fillMaxWidth()
            .then(if (t.dark) Modifier else Modifier.shadow(2.dp, shape, ambientColor = Color(0x14101028), spotColor = Color(0x14101028)))
            .clip(shape).background(t.card).border(1.dp, t.hairline, shape).padding(padding),
        content = content,
    )
}

@Composable
fun CardDivider(inset: Dp = 0.dp) = HorizontalDivider(Modifier.padding(start = inset), thickness = 1.dp, color = Vaani.tokens.hairline)

/** Rounded square icon tile with a hairline border. */
@Composable
fun IconTile(icon: ImageVector, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurface, background: Color = Vaani.tokens.cardRaised, size: Dp = 40.dp) {
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(background).border(1.dp, Vaani.tokens.hairline, RoundedCornerShape(size * 0.3f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** Deep ink hero with indigo and teal glows and a faint ring pattern. */
@Composable
fun HeroSurface(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(24.dp), content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier.fillMaxWidth().clip(shape).background(Brand.heroGradient)
            .background(Brush.radialGradient(listOf(Brand.Indigo.copy(alpha = 0.55f), Color.Transparent), center = Offset(980f, -60f), radius = 760f))
            .background(Brush.radialGradient(listOf(Brand.Teal.copy(alpha = 0.22f), Color.Transparent), center = Offset(-80f, 620f), radius = 640f))
            .drawBehind {
                // Concentric rings off the top-right corner, a quiet nod to a sound wave.
                val c = Offset(size.width - 24.dp.toPx(), 18.dp.toPx())
                for (i in 1..4) drawCircle(Color.White.copy(alpha = 0.075f - i * 0.012f), radius = (44 * i).dp.toPx(), center = c, style = Stroke(1.dp.toPx()))
                // Fixed star field, like Rajnath's night-sky hero (no randomness, so screenshots are stable).
                Stars.forEachIndexed { i, (x, y, r) ->
                    drawCircle(if (i % 3 == 0) Color(0xFFBDB4FF).copy(alpha = 0.7f) else Color.White.copy(alpha = 0.55f), radius = r.dp.toPx(), center = Offset(x * size.width, y * size.height))
                }
            }
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape),
        content = content,
    )
}

private val Stars = listOf(
    Triple(0.06f, 0.14f, 0.9f), Triple(0.18f, 0.66f, 0.6f), Triple(0.29f, 0.28f, 1.0f), Triple(0.38f, 0.86f, 0.7f), Triple(0.46f, 0.08f, 0.6f),
    Triple(0.55f, 0.48f, 0.9f), Triple(0.63f, 0.74f, 0.6f), Triple(0.71f, 0.2f, 0.8f), Triple(0.79f, 0.57f, 1.0f), Triple(0.87f, 0.9f, 0.7f),
    Triple(0.93f, 0.33f, 0.8f), Triple(0.12f, 0.93f, 0.8f), Triple(0.42f, 0.62f, 0.5f), Triple(0.74f, 0.4f, 0.5f), Triple(0.24f, 0.46f, 0.5f),
)

/** Thin indigo → blue → teal line under the header (Rajnath's brand stripe). */
@Composable
fun BrandStripe(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(2.dp).background(Brush.horizontalGradient(listOf(Brand.Indigo, Brand.Blue, Brand.Teal))))
}

/** Round ink orb with an accent ring for quick actions (Rajnath's icon orb). */
@Composable
fun IconOrb(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    Box(
        modifier.size(size).clip(CircleShape)
            .background(Brush.linearGradient(listOf(Brand.Ink800, Brand.Ink900, Color(0xFF2A2170))))
            .border(1.5.dp, Brush.linearGradient(listOf(Brand.IndigoSoft.copy(alpha = 0.9f), Brand.Teal.copy(alpha = 0.6f))), CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(size * 0.42f), tint = Color(0xFFDCD6FF)) }
}

// ---- Buttons ---------------------------------------------------------------------------------

enum class PillStyle { Primary, Secondary, Accent, Danger, Glass }

/** Pill button: ink/white primary, hairline secondary, gradient accent. */
@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, style: PillStyle = PillStyle.Primary, icon: ImageVector? = null, compact: Boolean = false, enabled: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    val t = Vaani.tokens
    val shape = RoundedCornerShape(50)
    val (bg, fg, border) = when (style) {
        PillStyle.Primary -> Triple(cs.primary, cs.onPrimary, null)
        PillStyle.Secondary -> Triple(t.card, cs.onSurface, BorderStroke(1.dp, t.hairlineStrong))
        PillStyle.Accent -> Triple(Color.Transparent, Color.White, null)
        PillStyle.Danger -> Triple(t.danger.copy(alpha = 0.12f), t.danger, BorderStroke(1.dp, t.danger.copy(alpha = 0.3f)))
        PillStyle.Glass -> Triple(Color.White.copy(alpha = 0.10f), Color.White, BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)))
    }
    Surface(
        onClick = onClick, enabled = enabled, shape = shape, color = bg, contentColor = fg, border = border,
        modifier = modifier.then(if (style == PillStyle.Accent) Modifier.clip(shape).background(Brush.horizontalGradient(listOf(Brand.Indigo, Color(0xFF4F63F6), Brand.Blue))) else Modifier),
    ) {
        Row(
            Modifier.defaultMinSize(minHeight = if (compact) 36.dp else 44.dp).padding(horizontal = if (compact) 14.dp else 18.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) { Icon(icon, null, Modifier.size(if (compact) 16.dp else 18.dp)); Spacer(Modifier.width(7.dp)) }
            Text(text, style = MaterialTheme.typography.labelLarge, fontSize = if (compact) 13.sp else 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.10f)).border(1.dp, color.copy(alpha = 0.28f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    }
}

/** Row used inside grouped cards: leading slot, title/subtitle, trailing slot. */
@Composable
fun ListRow(title: String, subtitle: String? = null, modifier: Modifier = Modifier, titleColor: Color = MaterialTheme.colorScheme.onSurface, leading: (@Composable () -> Unit)? = null, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) { leading(); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontSize = 15.sp, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) { Spacer(Modifier.height(2.dp)); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Vaani.tokens.muted) }
        }
        if (trailing != null) { Spacer(Modifier.width(10.dp)); trailing() }
    }
}

/** Shape for item [index] of [count] in a grouped list, so stacked rows read as one card. */
fun groupShape(index: Int, count: Int, radius: Dp = 18.dp): Shape = when {
    count == 1 -> RoundedCornerShape(radius)
    index == 0 -> RoundedCornerShape(topStart = radius, topEnd = radius)
    index == count - 1 -> RoundedCornerShape(bottomStart = radius, bottomEnd = radius)
    else -> RoundedCornerShape(0.dp)
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
