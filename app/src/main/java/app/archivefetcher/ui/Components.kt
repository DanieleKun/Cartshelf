package app.archivefetcher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.archivefetcher.R
import kotlinx.coroutines.delay

/**
 * The single interactive building block: reacts to touch, D-pad focus and the confirm key alike.
 * Focus is shown with an accent ring and a slight scale-up so it is readable from arm's length.
 */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    focusRequester: FocusRequester? = null,
    cornerRadius: Int = 12,
    idleColor: Color = Palette.Surface,
    content: @Composable BoxScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.02f else 1f, label = "focusScale")
    val shape = RoundedCornerShape(cornerRadius.dp)
    val container = when {
        highlighted -> Palette.Accent.copy(alpha = 0.18f)
        focused -> Palette.SurfaceFocus
        else -> idleColor
    }
    Box(
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .scale(scale)
            .clip(shape)
            .background(container)
            .border(if (focused) 3.dp else 1.dp, if (focused) Palette.Accent else Color.Transparent, shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        content = content,
    )
}

@Composable
fun AppButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    FocusSurface(onClick, modifier, highlighted, focusRequester, cornerRadius = 10, idleColor = Palette.SurfaceHigh) {
        Text(
            label,
            color = if (highlighted) Palette.Accent else Palette.Text,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 12.dp, vertical = 9.dp),
        )
    }
}

@Composable
fun Chip(label: String, selected: Boolean, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    FocusSurface(
        onClick,
        Modifier.widthIn(max = 300.dp),
        highlighted = selected,
        focusRequester = focusRequester,
        cornerRadius = 18,
    ) {
        Text(
            (if (selected) "✓ " else "") + label,
            color = if (selected) Palette.Accent else Palette.TextDim,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

/**
 * Shared page frame: back button, title, optional actions, then the content.
 * On narrow screens (phones held upright) the actions move to their own row so the title stays readable.
 */
@Composable
fun ScreenFrame(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        val narrow = maxWidth < NARROW_WIDTH
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onBack != null) AppButton(stringResource(R.string.back), onBack)
                Column(Modifier.weight(1f)) {
                    Text(title, color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) {
                        Text(subtitle, color = Palette.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (actions != null && !narrow) actions()
            }
            if (actions != null && narrow) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    content = actions,
                )
            }
            Box(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)) { content() }
        }
    }
}

/**
 * Summary line plus the action buttons at the bottom of list screens. Buttons should use `Modifier.weight(1f)`.
 * On short landscape screens everything fits in one row to leave room for the list.
 */
@Composable
fun SelectionBar(summary: String, buttons: @Composable RowScope.() -> Unit) {
    val config = LocalConfiguration.current
    val oneRow = config.screenHeightDp < SHORT_HEIGHT_DP && config.screenWidthDp >= WIDE_WIDTH_DP
    if (oneRow) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(summary, color = Palette.TextDim, fontSize = 12.sp, maxLines = 2, modifier = Modifier.weight(1.4f))
            buttons()
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(summary, color = Palette.TextDim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = buttons)
        }
    }
}

private val NARROW_WIDTH = 440.dp
private const val SHORT_HEIGHT_DP = 440
private const val WIDE_WIDTH_DP = 600

@Composable
fun CenterMessage(text: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = Palette.TextDim, fontSize = 14.sp, modifier = Modifier.padding(bottom = 12.dp))
        if (actionLabel != null) AppButton(actionLabel, onAction, highlighted = true)
    }
}

/** Requests focus once the target is attached to the layout, retrying while lists are still composing. */
suspend fun FocusRequester.requestFocusWhenReady(attempts: Int = 20) {
    repeat(attempts) {
        if (runCatching { requestFocus() }.getOrDefault(false)) return
        delay(100)
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "—"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "${value.toInt()} B" else "%.1f %s".format(value, units[unit])
}
