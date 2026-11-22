package app.archivefetcher.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.archivefetcher.R
import kotlinx.coroutines.delay

/**
 * The single interactive building block: reacts to touch, D-pad focus and the confirm key alike.
 * Focus shows an accent ring and a slight lift so it reads from arm's length; a press sinks it a little.
 */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    focusRequester: FocusRequester? = null,
    cornerRadius: Int = 14,
    idleColor: Color = Palette.Surface,
    enabled: Boolean = true,
    role: Role = Role.Button,
    content: @Composable BoxScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        when {
            pressed -> 0.98f
            focused -> 1.02f
            else -> 1f
        },
        tween(DURATION_SHORT),
        label = "surfaceScale",
    )
    val container by animateColorAsState(
        when {
            highlighted && focused -> Palette.Accent.copy(alpha = 0.28f)
            highlighted -> Palette.AccentSoft
            focused -> Palette.SurfaceFocus
            else -> idleColor
        },
        tween(DURATION_SHORT),
        label = "surfaceColor",
    )
    val ring by animateColorAsState(if (focused) Palette.Accent else Color.Transparent, tween(DURATION_SHORT), label = "ring")
    val shape = RoundedCornerShape(cornerRadius.dp)
    Box(
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .scale(scale)
            .clip(shape)
            .background(container)
            .border(2.dp, ring, shape)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clickable(
                interactionSource = source,
                indication = ripple(color = Palette.Accent),
                enabled = enabled,
                role = role,
                onClick = onClick,
            ),
        content = content,
    )
}

/**
 * Button with an optional leading icon. [highlighted] marks the main action of a screen: solid accent.
 * Everything else is a quiet tonal button, so a screen never has more than one loud action.
 */
@Composable
fun AppButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    focusRequester: FocusRequester? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else if (focused) 1.03f else 1f, tween(DURATION_SHORT), label = "buttonScale")
    val container by animateColorAsState(
        when {
            highlighted -> if (focused) Palette.Accent.copy(alpha = 0.85f) else Palette.Accent
            focused -> Palette.SurfaceFocus
            else -> Palette.SurfaceHigh
        },
        tween(DURATION_SHORT),
        label = "buttonColor",
    )
    val content = if (highlighted) Palette.OnAccent else Palette.Text
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .scale(scale)
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(container)
            .border(2.dp, if (focused) (if (highlighted) Palette.Text else Palette.Accent) else Color.Transparent, shape)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clickable(source, ripple(color = content), enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Text(
            label,
            color = content,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            maxLines = 1, modifier = Modifier.scrollIfLong(),
            overflow = TextOverflow.Clip,
        )
    }
}

/** Square icon button for toolbars. [label] is read by TalkBack; a [badge] shows a small count. */
@Composable
fun IconAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    highlighted: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    Box {
        FocusSurface(
            onClick = onClick,
            modifier = modifier.size(44.dp).semantics { contentDescription = label },
            highlighted = highlighted,
            focusRequester = focusRequester,
            cornerRadius = 12,
            idleColor = Palette.SurfaceHigh,
        ) {
            Icon(icon, contentDescription = null, tint = if (highlighted) Palette.Accent else Palette.Text, modifier = Modifier.align(Alignment.Center).size(22.dp))
        }
        if (badge != null) {
            Text(
                badge,
                color = Palette.OnAccent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 2.dp)
                    .background(Palette.Accent, CircleShape)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
fun Chip(label: String, selected: Boolean, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    FocusSurface(
        onClick,
        Modifier.widthIn(max = 300.dp),
        highlighted = selected,
        focusRequester = focusRequester,
        cornerRadius = 20,
        idleColor = Palette.SurfaceHigh,
        role = Role.Checkbox,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (selected) Icon(AppIcons.Check, contentDescription = null, tint = Palette.Accent, modifier = Modifier.size(16.dp))
            Text(
                label,
                color = if (selected) Palette.Accent else Palette.Text,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, modifier = Modifier.scrollIfLong(),
                overflow = TextOverflow.Clip,
            )
        }
    }
}

/** Shared page frame: back button, title and subtitle, compact actions on the right, then the content. */
@Composable
fun ScreenFrame(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (onBack != null) IconAction(AppIcons.Back, stringResource(R.string.back), onBack)
            Column(Modifier.weight(1f)) {
                Text(title, style = AppType.Title, maxLines = 1, modifier = Modifier.scrollIfLong(), overflow = TextOverflow.Clip)
                if (subtitle != null) {
                    Text(subtitle, style = AppType.Caption, maxLines = 1, modifier = Modifier.scrollIfLong(), overflow = TextOverflow.Clip)
                }
            }
            if (actions != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp)) { content() }
    }
}

/** Summary line plus the action buttons at the bottom of list screens. Buttons should use `Modifier.weight(1f)`. */
@Composable
fun SelectionBar(summary: String, buttons: @Composable RowScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.Surface, RoundedCornerShape(16.dp))
            .border(1.dp, Palette.Outline, RoundedCornerShape(16.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(summary, style = AppType.Caption, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = buttons)
    }
}

/** Rounded container for a group of related things. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    padding: Dp = 14.dp,
    spacing: Dp = 8.dp,
    border: Color = Palette.Outline,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Palette.Surface, RoundedCornerShape(16.dp))
            .border(1.dp, border, RoundedCornerShape(16.dp))
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

@Composable
fun Overline(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = AppType.Overline, modifier = modifier)
}

/** Small rounded label for metadata such as the format or a status. */
@Composable
fun Pill(text: String, color: Color = Palette.TextDim, background: Color = Palette.SurfaceHigh) {
    Text(
        text,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier
            .background(background, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Letter tile used as the picture of a platform or a console; its tint is stable for a given name. */
@Composable
fun Monogram(name: String, size: Dp = 44.dp) {
    val tint = Palette.tiles[Math.floorMod(name.hashCode(), Palette.tiles.size)]
    val letters = monogramLetters(name)
    Box(
        Modifier.size(size).background(tint.copy(alpha = 0.16f), RoundedCornerShape(size * 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(letters, color = tint, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.36f).sp, maxLines = 1)
    }
}

/** "Nintendo - Game Boy Color" → "GBC": the significant words after the maker, or the first two letters. */
internal fun monogramLetters(name: String): String {
    val afterMaker = name.substringAfter(" - ", name)
    val words = afterMaker.split(' ', '-', '_').filter { it.isNotBlank() && it.first().isLetterOrDigit() }
    return when {
        words.size >= 2 -> words.take(3).joinToString("") { it.first().uppercase() }
        words.size == 1 -> words[0].take(2).replaceFirstChar { it.uppercase() }
        else -> "?"
    }
}

/** Round tick used for selectable rows; fills with the accent when chosen. */
@Composable
fun SelectMark(selected: Boolean) {
    val fill by animateColorAsState(if (selected) Palette.Accent else Color.Transparent, tween(DURATION_SHORT), label = "markFill")
    val edge by animateColorAsState(if (selected) Palette.Accent else Palette.TextFaint, tween(DURATION_SHORT), label = "markEdge")
    Box(
        Modifier.size(22.dp).background(fill, CircleShape).border(2.dp, edge, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(AppIcons.Check, contentDescription = null, tint = Palette.OnAccent, modifier = Modifier.size(15.dp))
    }
}

/** Thin rounded progress bar; the fill glides to new values instead of jumping. */
@Composable
fun ProgressBar(progress: Float, modifier: Modifier = Modifier, color: Color = Palette.Accent, height: Dp = 4.dp) {
    val shown by animateFloatAsState(progress.coerceIn(0f, 1f), tween(DURATION_MEDIUM, easing = FastOutSlowInEasing), label = "progress")
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height)).background(Palette.SurfaceHigh)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(shown).background(color, RoundedCornerShape(height)))
    }
}

/** Progress bar for work of unknown length: a short segment sweeping across. */
@Composable
fun IndeterminateBar(modifier: Modifier = Modifier, color: Color = Palette.Accent, height: Dp = 4.dp) {
    val sweep = rememberInfiniteTransition(label = "sweep")
    val offset by sweep.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing)), label = "offset")
    Row(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height)).background(Palette.SurfaceHigh)) {
        if (offset > 0.01f) Spacer(Modifier.weight(offset))
        Box(Modifier.weight(0.35f).fillMaxHeight().background(color, RoundedCornerShape(height)))
        if (offset < 0.99f) Spacer(Modifier.weight(1f - offset))
    }
}

/**
 * A screen-sized explanation with an icon, used for empty lists and errors. It always says what happened and,
 * when there is one, offers the next step as a button.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String? = null,
    tint: Color = Palette.Accent,
    action: String? = null,
    onAction: () -> Unit = {},
    secondaryAction: String? = null,
    onSecondaryAction: () -> Unit = {},
    actionFocus: FocusRequester? = null,
    /** False when placed inside a lazy list, which already scrolls and gives no height limit. */
    fillScreen: Boolean = true,
) {
    Column(
        (if (fillScreen) Modifier.fillMaxSize().verticalScroll(rememberScrollState()) else Modifier.fillMaxWidth())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(64.dp).background(tint.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(30.dp))
        }
        Text(title, style = AppType.Heading, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp))
        if (body != null) {
            Text(body, style = AppType.BodyDim, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp))
        }
        if (action != null || secondaryAction != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                if (secondaryAction != null) AppButton(secondaryAction, onSecondaryAction)
                if (action != null) AppButton(action, onAction, highlighted = true, focusRequester = actionFocus)
            }
        }
    }
}

/**
 * Loading screen that says what is being loaded and how far along it is, instead of a bare spinner.
 * [progress] null means the length is unknown.
 */
@Composable
fun LoadingState(title: String, detail: String? = null, progress: Float? = null, hint: String? = null) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Palette.Accent, trackColor = Palette.SurfaceHigh, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
        Text(title, style = AppType.Heading, textAlign = TextAlign.Center)
        if (detail != null) {
            Text(detail, style = AppType.Caption, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.widthIn(max = 320.dp).padding(top = 4.dp)) {
            if (progress != null) ProgressBar(progress) else IndeterminateBar()
        }
        if (hint != null) Text(hint, style = AppType.Caption.copy(color = Palette.TextFaint), textAlign = TextAlign.Center)
    }
}

/** Placeholder rows that gently pulse while a list is on its way. */
@Composable
fun SkeletonRows(count: Int = 6) {
    val pulse = rememberInfiniteTransition(label = "skeleton")
    val alpha by pulse.animateFloat(0.35f, 0.7f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "skeletonAlpha")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.alpha(alpha)) {
        repeat(count) { index ->
            Row(
                Modifier.fillMaxWidth().background(Palette.Surface, RoundedCornerShape(14.dp)).padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(22.dp).background(Palette.SurfaceHigh, CircleShape))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.fillMaxWidth(if (index % 2 == 0) 0.7f else 0.5f).height(12.dp).background(Palette.SurfaceHigh, RoundedCornerShape(4.dp)))
                    Box(Modifier.width(110.dp).height(10.dp).background(Palette.SurfaceHigh, RoundedCornerShape(4.dp)))
                }
            }
        }
    }
}

/** Inline message strip: informative, warning or error, with an optional action. */
@Composable
fun Banner(
    text: String,
    icon: ImageVector = AppIcons.Info,
    tint: Color = Palette.Info,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(tint.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
            .border(1.dp, tint.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(text, style = AppType.Caption.copy(color = Palette.Text), modifier = Modifier.weight(1f))
        if (action != null) AppButton(action, onAction)
    }
}

@Composable
fun CenterMessage(text: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
    EmptyState(AppIcons.Info, text, tint = Palette.TextDim, action = actionLabel, onAction = onAction)
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

/**
 * A one-line text that does not fit scrolls slowly sideways after a short pause, so the whole of it can be read;
 * text that fits stays still. Use with `maxLines = 1` and `overflow = TextOverflow.Clip`.
 */
fun Modifier.scrollIfLong(): Modifier = basicMarquee(
    iterations = Int.MAX_VALUE,
    initialDelayMillis = MARQUEE_DELAY_MS,
    repeatDelayMillis = MARQUEE_DELAY_MS,
    velocity = 32.dp,
)

const val DURATION_SHORT = 140
const val DURATION_MEDIUM = 260
private const val DISABLED_ALPHA = 0.45f
private const val MARQUEE_DELAY_MS = 1500
