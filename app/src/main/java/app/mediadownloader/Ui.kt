package app.mediadownloader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/** Colors from the "Motion Downloader App UI" design (light) plus a matching dark set. */
class Pal(
    val bg: Color, val surface: Color, val text: Color, val muted: Color, val border: Color,
    val primary: Color, val tint: Color, val onTint: Color, val soft: Color, val segBg: Color,
    val okBg: Color, val okFg: Color, val errBg: Color, val errFg: Color, val warnBg: Color, val warnFg: Color,
    val scrim: Color,
)

private val LightPal = Pal(
    bg = Color(0xFFF5F6FA), surface = Color.White, text = Color(0xFF14162B), muted = Color(0xFF5B6078),
    border = Color(0xFFE3E5EE), primary = Color(0xFF4F46E5), tint = Color(0xFFECEBFD), onTint = Color(0xFF4338CA),
    soft = Color(0xFFF5F6FA), segBg = Color(0xFFE9EAF2),
    okBg = Color(0xFFE3F5EC), okFg = Color(0xFF0F7A47), errBg = Color(0xFFFDE8EA), errFg = Color(0xFFBF2433),
    warnBg = Color(0xFFFFF1D9), warnFg = Color(0xFF8A5200), scrim = Color(0x8C14162B),
)

private val DarkPal = Pal(
    bg = Color(0xFF0F1018), surface = Color(0xFF1A1C28), text = Color(0xFFECEDF5), muted = Color(0xFFA3A8BD),
    border = Color(0xFF2C2F40), primary = Color(0xFF6A62F2), tint = Color(0xFF26284C), onTint = Color(0xFFC3C0FF),
    soft = Color(0xFF23253A), segBg = Color(0xFF23253A),
    okBg = Color(0xFF12301F), okFg = Color(0xFF6FD9A0), errBg = Color(0xFF3A1A1F), errFg = Color(0xFFFF8A94),
    warnBg = Color(0xFF3A2C10), warnFg = Color(0xFFF5C26B), scrim = Color(0xA0000000),
)

val LocalPal = compositionLocalOf { LightPal }

@Composable
internal fun AppTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val p = if (dark) DarkPal else LightPal
    val scheme = if (dark) darkColorScheme(
        primary = p.primary, onPrimary = Color.White, background = p.bg, onBackground = p.text,
        surface = p.surface, onSurface = p.text, surfaceVariant = p.soft, onSurfaceVariant = p.muted,
        outline = p.border, error = p.errFg,
    ) else lightColorScheme(
        primary = p.primary, onPrimary = Color.White, background = p.bg, onBackground = p.text,
        surface = p.surface, onSurface = p.text, surfaceVariant = p.soft, onSurfaceVariant = p.muted,
        outline = p.border, error = p.errFg,
    )
    CompositionLocalProvider(LocalPal provides p) { MaterialTheme(colorScheme = scheme, content = content) }
}

// ---------------------------------------------------------------- icons (paths copied from the design)

private fun stroke(d: String, w: Float = 2f): ImageVector =
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        addPath(
            PathParser().parsePathString(d).toNodes(), stroke = SolidColor(Color.Black), strokeLineWidth = w,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
    }.build()

private fun filled(d: String): ImageVector =
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        addPath(PathParser().parsePathString(d).toNodes(), fill = SolidColor(Color.Black))
    }.build()

object Ic {
    val Download = stroke("M12 4v11 M7 11l5 5 5-5 M5 20h14")
    val Home = stroke("M4 11 12 4l8 7 M6 10v10h12V10", 1.9f)
    val Settings = stroke("M4 7h10 M18 7h2 M14 7a2 2 0 1 0 4 0a2 2 0 1 0 -4 0 M4 17h2 M10 17h10 M6 17a2 2 0 1 0 4 0a2 2 0 1 0 -4 0", 1.9f)
    val Back = stroke("M15 5 8 12l7 7")
    val Pause = stroke("M9 6v12M15 6v12")
    val Close = stroke("M6 6l12 12M18 6 6 18")
    val Trash = stroke("M5 7h14 M9 7V5h6v2 M7 7l1 12h8l1-12", 1.9f)
    val Check = stroke("m5 12 5 5 9-10", 2.4f)
    val Clock = stroke("M4 12a8 8 0 1 0 16 0a8 8 0 1 0 -16 0 M12 8v4l3 2", 2.2f)
    val Link = stroke("M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1 M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1")
    val Paste = stroke("M8 5h8a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z M9 5V4h6v1")
    val Chevron = stroke("m9 6 6 6-6 6")
    val More = stroke("M5 12h.01 M12 12h.01 M19 12h.01", 3.6f)
    val Play = filled("M8 5v14l11-7z")
    val Retry = stroke("M20 12a8 8 0 1 1-2.3-5.6 M20 4v5h-5")
    val Copy = stroke("M9 9h10v11H9z M5 15V5h10")
    val Subs = stroke("M5 6h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z M8 11h3 M8 14h6 M14 11h2")
}

@Composable
fun Ico(v: ImageVector, size: Dp = 22.dp, tint: Color = LocalPal.current.text) =
    Icon(v, contentDescription = null, modifier = Modifier.size(size), tint = tint)

// ---------------------------------------------------------------- small building blocks

@Composable
fun Card(modifier: Modifier = Modifier, pad: Dp = 12.dp, gap: Dp = 10.dp, content: @Composable () -> Unit) {
    val p = LocalPal.current
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface)
            .border(1.dp, p.border, RoundedCornerShape(16.dp)).padding(pad),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) { content() }
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier = modifier, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = LocalPal.current.muted)

@Composable
fun Muted(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) =
    Text(text, modifier = modifier, fontSize = 12.sp, color = LocalPal.current.muted, maxLines = maxLines, overflow = TextOverflow.Ellipsis)

@Composable
fun Pill(text: String, bg: Color, fg: Color, icon: ImageVector? = null) {
    Row(
        Modifier.height(26.dp).clip(RoundedCornerShape(13.dp)).background(bg).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Ico(icon, 14.dp, fg)
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1)
    }
}

/** Big rounded action button (design: 54dp, radius 16, indigo). */
@Composable
fun BigButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    val p = LocalPal.current
    Row(
        modifier.height(54.dp).clip(RoundedCornerShape(16.dp))
            .background(if (enabled) p.primary else p.primary.copy(alpha = 0.4f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { Ico(icon, 20.dp, Color.White); Box(Modifier.width(8.dp)) }
        Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Round 44dp icon button. */
@Composable
fun RoundIconButton(icon: ImageVector, desc: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(bg).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, Modifier.size(20.dp), tint = fg) }
}

/** Pill-shaped text button (Play / Retry / Cancel). */
@Composable
fun PillButton(text: String, bg: Color, fg: Color, border: Color? = null, onClick: () -> Unit) {
    Box(
        Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(bg)
            .then(if (border != null) Modifier.border(1.5.dp, border, RoundedCornerShape(22.dp)) else Modifier)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPal.current
    Box(
        Modifier.height(44.dp).clip(RoundedCornerShape(22.dp))
            .background(if (selected) p.primary else p.surface)
            .border(1.5.dp, if (selected) p.primary else p.border, RoundedCornerShape(22.dp))
            .clickable(role = Role.Tab, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (selected) Color.White else p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}

/** Selected chip in the tint colour (subtitle chips). */
@Composable
fun TintChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPal.current
    Box(
        Modifier.height(44.dp).clip(RoundedCornerShape(22.dp))
            .background(if (selected) p.tint else p.surface)
            .border(1.5.dp, if (selected) p.primary else p.border, RoundedCornerShape(22.dp))
            .clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (selected) p.onTint else p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}

/** Switch like the design: 52x32 track. */
@Composable
fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPal.current
    Box(
        Modifier.size(width = 52.dp, height = 32.dp).clip(RoundedCornerShape(16.dp))
            .background(if (checked) p.primary else p.segBg)
            .clickable(role = Role.Switch) { onChange(!checked) }.padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) { Box(Modifier.size(26.dp).clip(CircleShape).background(Color.White)) }
}

/** Thumbnail with the design's indigo→cyan gradient when there is no picture. */
@Composable
fun Thumb(url: String?, w: Dp, h: Dp, radius: Dp = 10.dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(w, h).clip(RoundedCornerShape(radius))
            .background(Brush.linearGradient(listOf(Color(0xFF4F46E5), Color(0xFF06B6D4)))),
    ) {
        if (url != null) AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}
