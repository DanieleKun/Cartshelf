package app.archivefetcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.archivefetcher.R

/**
 * A text field that never grabs D-pad focus by accident: at rest it is a normal focusable
 * button, and only a click turns it into an editor. The editor is left again on Done, back,
 * D-pad up/down, or as soon as the on-screen keyboard is dismissed, so navigation never gets stuck.
 * Secret fields use a password keyboard, so the keyboard app neither suggests nor learns what is typed.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
fun TextEntry(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    secret: Boolean = false,
    focusRequester: FocusRequester? = null,
    icon: ImageVector? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    var editing by remember { mutableStateOf(false) }
    var restoreFocus by remember { mutableStateOf(false) }
    var keyboardWasShown by remember { mutableStateOf(false) }
    val restingFocus = focusRequester ?: remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    var revealed by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val keyboardVisible = WindowInsets.isImeVisible

    fun finish() {
        restoreFocus = true
        editing = false
    }

    LaunchedEffect(editing, keyboardVisible) {
        if (!editing) {
            keyboardWasShown = false
        } else if (keyboardVisible) {
            keyboardWasShown = true
        } else if (keyboardWasShown) {
            finish()
        }
    }
    LaunchedEffect(editing) {
        if (editing) {
            fieldFocus.requestFocusWhenReady()
            keyboard?.show()
        } else if (restoreFocus) {
            keyboard?.hide()
            restingFocus.requestFocusWhenReady()
            restoreFocus = false
        }
    }

    // Secret fields get a Show/Hide button next to them, reachable with the D-pad like everything else.
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!editing) {
            FocusSurface(
                onClick = { editing = true },
                focusRequester = restingFocus,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                cornerRadius = 14,
                idleColor = Palette.SurfaceHigh,
            ) {
                FieldContent(icon, label) {
                    val shown = when {
                        value.isBlank() -> hint
                        secret && !revealed -> "•".repeat(minOf(value.length, 16))
                        else -> value
                    }
                    Text(
                        shown,
                        color = if (value.isBlank()) Palette.TextFaint else Palette.Text,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        } else {
            BackHandler { finish() }
            val shape = RoundedCornerShape(14.dp)
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .background(Palette.Surface, shape)
                    .border(2.dp, Palette.Accent, shape),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FieldContent(icon, label) {
                    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
                    BasicTextField(
                        value = field,
                        onValueChange = {
                            field = it
                            onValueChange(it.text)
                        },
                        singleLine = true,
                        textStyle = TextStyle(color = Palette.Text, fontSize = 14.sp),
                        cursorBrush = SolidColor(Palette.Accent),
                        visualTransformation = if (secret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (secret) KeyboardType.Password else keyboardType,
                            autoCorrectEnabled = !secret && keyboardType == KeyboardType.Text,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { finish() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(fieldFocus)
                            .onPreviewKeyEvent { event ->
                                val vertical = event.key == Key.DirectionDown || event.key == Key.DirectionUp
                                if (vertical && event.type == KeyEventType.KeyDown) {
                                    finish()
                                    true
                                } else {
                                    false
                                }
                            },
                    )
                }
            }
        }
        if (secret) {
            AppButton(stringResource(if (revealed) R.string.hide_secret else R.string.show_secret), { revealed = !revealed })
        }
    }
}

@Composable
private fun FieldContent(icon: ImageVector?, label: String?, text: @Composable () -> Unit) {
    Row(
        Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = Palette.TextDim, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            if (label != null) Text(label, style = AppType.Caption.copy(fontSize = 11.sp))
            text()
        }
    }
}
