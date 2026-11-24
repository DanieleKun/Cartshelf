package app.archivefetcher.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Dark palette: deep violet-ink surfaces with a single electric-violet accent, like the glow of a handheld screen.
 * Status colours are used only for status, never for decoration.
 */
object Palette {
    val Background = Color(0xFF0E0D15)
    val Surface = Color(0xFF17161F)
    val SurfaceHigh = Color(0xFF212030)
    val SurfaceFocus = Color(0xFF2E2B45)
    val Outline = Color(0xFF2B2940)
    val Accent = Color(0xFF9D8CFF)
    val AccentSoft = Color(0x299D8CFF)
    val OnAccent = Color(0xFF120C33)
    val Text = Color(0xFFF1F0F8)
    val TextDim = Color(0xFFA6A3BC)
    val TextFaint = Color(0xFF71708A)
    val Danger = Color(0xFFFF6B6B)
    val Warning = Color(0xFFFFC44D)
    val Success = Color(0xFF4FD18B)
    val Info = Color(0xFF6CB6FF)

    /** Muted tints for the letter tiles of platforms and consoles; picked by a stable hash of the name. */
    val tiles = listOf(
        Color(0xFFE07A5F), Color(0xFF52B788), Color(0xFF6C9BEF), Color(0xFFD4A72C),
        Color(0xFF9CC25A), Color(0xFFE5687B), Color(0xFF4CC3B5), Color(0xFFE39B4B),
    )
}

/** Text styles, from the largest to the smallest. Sizes stay readable on a 3.5" square screen at arm's length. */
object AppType {
    val Display = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp, color = Palette.Text)
    val Title = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp, color = Palette.Text)
    val Heading = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
    val Body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, color = Palette.Text)
    val BodyDim = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, color = Palette.TextDim)
    val Label = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Palette.Text)
    val Caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, color = Palette.TextDim)
    val Overline = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, color = Palette.TextFaint)
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Accent,
            onPrimary = Palette.OnAccent,
            background = Palette.Background,
            surface = Palette.Surface,
            surfaceVariant = Palette.SurfaceHigh,
            onBackground = Palette.Text,
            onSurface = Palette.Text,
            onSurfaceVariant = Palette.TextDim,
            outline = Palette.Outline,
            error = Palette.Danger,
        ),
        content = content,
    )
}
