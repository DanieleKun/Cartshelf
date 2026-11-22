package app.archivefetcher.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Palette {
    val Background = Color(0xFF0E1116)
    val Surface = Color(0xFF171C24)
    val SurfaceHigh = Color(0xFF222A36)
    val SurfaceFocus = Color(0xFF2B3544)
    val Accent = Color(0xFF5EEAD4)
    val OnAccent = Color(0xFF06231F)
    val Text = Color(0xFFE6EDF3)
    val TextDim = Color(0xFF8B98A9)
    val Danger = Color(0xFFF87171)
    val Warning = Color(0xFFFBBF24)
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Accent,
            onPrimary = Palette.OnAccent,
            background = Palette.Background,
            surface = Palette.Surface,
            onBackground = Palette.Text,
            onSurface = Palette.Text,
        ),
        content = content,
    )
}
