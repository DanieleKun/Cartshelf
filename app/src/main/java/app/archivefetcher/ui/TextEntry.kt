package app.archivefetcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import app.archivefetcher.R
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A text field that never grabs D-pad focus by accident: at rest it is a normal focusable
 * button, and only a click turns it into an editor. The editor is left again on Done, back,
 * D-pad up/down, or as soon as the on-screen keyboard is dismissed, so navigation never gets stuck.
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
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    if (!editing) {
        FocusSurface(onClick = { editing = true }, focusRequester = restingFocus, modifier = Modifier.weight(1f)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                if (label != null) Text(label, color = Palette.TextDim, fontSize = 11.sp)
                val shown = when {
                    value.isBlank() -> hint
                    secret && !revealed -> "•".repeat(minOf(value.length, 16))
                    else -> value
                }
                Text(
                    shown,
                    color = if (value.isBlank()) Palette.TextDim else Palette.Text,
                    fontSize = 14.sp,
                    maxLines = 1,
                )
            }
        }
    } else {
        BackHandler { finish() }
        val shape = RoundedCornerShape(12.dp)
        Column(
            Modifier
                .weight(1f)
                .background(Palette.Surface, shape)
                .border(3.dp, Palette.Accent, shape)
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            if (label != null) Text(label, color = Palette.TextDim, fontSize = 11.sp)
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
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
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
    if (secret) {
        AppButton(
            stringResource(if (revealed) R.string.hide_secret else R.string.show_secret),
            { revealed = !revealed },
        )
    }
    }
}
