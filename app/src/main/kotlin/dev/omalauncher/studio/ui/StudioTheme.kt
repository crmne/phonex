package dev.omalauncher.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.foundation.Image
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Phonex's look: monochrome, a chat first. Black on white, or near-white on near-black with the system's
 * dark theme; color only for state (a running server, an error). No brand marks: Phonex isn't OpenAI's.
 */
@Immutable
data class StudioColors(
    val background: Color,
    val surface: Color,
    val raised: Color,
    val text: Color,
    val secondary: Color,
    val faint: Color,
    val border: Color,
    /** Primary buttons: the inverse of the page. */
    val accent: Color,
    val onAccent: Color,
    val good: Color,
    val bad: Color,
    val dark: Boolean,
)

private val Light =
    StudioColors(
        background = Color(0xFFFFFFFF),
        surface = Color(0xFFF4F4F4),
        raised = Color(0xFFFFFFFF),
        text = Color(0xFF0D0D0D),
        secondary = Color(0xFF5D5D5D),
        faint = Color(0xFF8F8F8F),
        border = Color(0xFFE3E3E3),
        accent = Color(0xFF0D0D0D),
        onAccent = Color(0xFFFFFFFF),
        good = Color(0xFF1A9E5C),
        bad = Color(0xFFD93A3F),
        dark = false,
    )

private val Dark =
    StudioColors(
        background = Color(0xFF181818),
        surface = Color(0xFF2A2A2A),
        raised = Color(0xFF242424),
        text = Color(0xFFECECEC),
        secondary = Color(0xFFB4B4B4),
        faint = Color(0xFF7D7D7D),
        border = Color(0xFF363636),
        accent = Color(0xFFECECEC),
        onAccent = Color(0xFF0D0D0D),
        good = Color(0xFF3CC47C),
        bad = Color(0xFFFF6369),
        dark = true,
    )

val LocalStudioColors = staticCompositionLocalOf { Light }

object Type {
    val title = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
    val heading = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 16.sp, lineHeight = 24.sp)
    val small = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val code = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontFamily = FontFamily.Monospace)
}

@Composable
fun StudioTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalStudioColors provides if (isSystemInDarkTheme()) Dark else Light, content = content)
}

val colors: StudioColors @Composable get() = LocalStudioColors.current

@Composable
fun Label(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = Type.body,
    color: Color = colors.text,
    maxLines: Int = Int.MAX_VALUE,
) = BasicText(text, modifier, style.copy(color = color), maxLines = maxLines, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)

/** A pill button: [primary] is the page's one main action, the others are outlined. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
) {
    val c = colors
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .then(if (primary) Modifier.background(c.accent) else Modifier.border(1.dp, c.border, CircleShape))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(PaddingValues(horizontal = 20.dp, vertical = 12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Label(text, style = Type.small.copy(fontWeight = FontWeight.Medium), color = if (primary) c.onAccent else c.text)
    }
}

@Composable
fun IconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = colors.text,
    background: Color = Color.Transparent,
    size: Dp = 40.dp,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.35f)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(rememberVectorPainter(icon), contentDescription = icon.name, Modifier.size(size * 0.5f), colorFilter = ColorFilter.tint(tint))
    }
}

/** Two or three choices side by side, the picked one raised: the phone's Chat / Preview switch. */
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = colors
    Row(modifier.clip(CircleShape).background(c.surface).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEachIndexed { i, option ->
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(if (i == selected) c.raised else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Label(option, style = Type.small.copy(fontWeight = FontWeight.Medium), color = if (i == selected) c.text else c.secondary)
            }
        }
    }
}

@Composable
fun Dot(color: Color) = Box(Modifier.size(8.dp).clip(CircleShape).background(color))

val CardShape = RoundedCornerShape(16.dp)

private fun icon(
    name: String,
    path: String,
): ImageVector =
    ImageVector
        .Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.White))
        .build()

/** Material glyphs (Apache 2.0), tinted where they're drawn. */
object StudioIcons {
    val Back by lazy { icon("Back", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z") }
    val Send by lazy { icon("Send", "M4,12l1.41,1.41L11,7.83V20h2V7.83l5.58,5.59L20,12l-8,-8 -8,8z") }
    val Stop by lazy { icon("Stop", "M7,7h10v10H7z") }
    val Reload by lazy {
        icon(
            "Reload",
            "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6h-2.08" +
                "c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z",
        )
    }
    val OpenOutside by lazy {
        icon(
            "Open in browser",
            "M19,19H5V5h7V3H5c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2v-7h-2v7zM14,3v2h3.59l-9.83,9.83 1.41,1.41L19,6.41V10h2V3h-7z",
        )
    }
    val Delete by lazy { icon("Delete", "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z") }
    val Edit by lazy {
        icon(
            "Edit",
            "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34" +
                "c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z",
        )
    }
    val Chevron by lazy { icon("Chevron", "M10,6L8.59,7.41 13.17,12l-4.58,4.59L10,18l6,-6z") }
    val Terminal by lazy {
        icon(
            "Terminal",
            "M20,4H4C2.89,4 2,4.9 2,6v12c0,1.1 0.89,2 2,2h16c1.1,0 2,-0.9 2,-2V6C22,4.9 21.11,4 20,4zM20,18H4V8h16V18z" +
                "M6.5,10.5l1.41,-1.41L11.83,13l-3.92,3.91L6.5,15.5L9,13L6.5,10.5zM12,15h5.5v1.5H12V15z",
        )
    }
}
