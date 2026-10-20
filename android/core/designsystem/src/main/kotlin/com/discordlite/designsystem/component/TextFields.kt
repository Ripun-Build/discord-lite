package com.discordlite.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.discordlite.designsystem.theme.DlMotion
import com.discordlite.designsystem.theme.DlTheme

/**
 * Filled text field. The border glows to the accent on focus; validation messages expand in
 * inline as the user types instead of waiting for submit.
 */
@Composable
fun DlTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    leadingIcon: ImageVector? = null,
    prefix: String? = null,
    error: String? = null,
    hint: String? = null,
    password: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    readOnly: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val colors = DlTheme.colors
    val type = DlTheme.type
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var revealed by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val borderColor by animateColorAsState(
        when {
            error != null -> colors.danger
            focused -> colors.accent
            else -> colors.separator
        },
        DlMotion.snappy(),
        label = "border",
    )
    val borderWidth by animateDpAsState(if (focused || error != null) 1.5.dp else 1.dp, DlMotion.snappy(), label = "borderWidth")

    Column(modifier.fillMaxWidth()) {
        if (label != null) {
            Text(label, style = type.footnote, color = colors.textSecondary, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (colors.isDark) colors.surfaceRaised else colors.surface)
                .border(borderWidth, borderColor, RoundedCornerShape(16.dp))
                // The whole field is the target, not just the text run.
                .clickable(interactionSource = null, indication = null) { focusRequester.requestFocus() }
                .padding(horizontal = 16.dp, vertical = if (singleLine) 0.dp else 15.dp),
            verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (leadingIcon != null) {
                Icon(leadingIcon, null, tint = if (focused) colors.accent else colors.textTertiary, modifier = Modifier.size(20.dp))
            }
            if (prefix != null) {
                Text(prefix, style = type.body, color = colors.textTertiary)
            }
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(placeholder, style = type.body, color = colors.textTertiary)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = singleLine,
                    readOnly = readOnly,
                    minLines = minLines,
                    maxLines = if (singleLine) 1 else 6,
                    textStyle = type.body.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.accent),
                    interactionSource = interaction,
                    visualTransformation = if (password && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions,
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
            }
            if (password) {
                Icon(
                    if (revealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = if (revealed) "Hide password" else "Show password",
                    tint = colors.textTertiary,
                    modifier = Modifier
                        .size(22.dp)
                        .pressable(pressedScale = 0.85f) { revealed = !revealed },
                )
            }
        }
        val message = error ?: hint
        AnimatedVisibility(
            visible = message != null,
            enter = expandVertically(DlMotion.snappy()) + fadeIn(DlMotion.snappy()),
            exit = shrinkVertically(DlMotion.snappy()) + fadeOut(DlMotion.snappy()),
        ) {
            Text(
                text = message.orEmpty(),
                style = type.footnote,
                color = if (error != null) colors.danger else colors.textSecondary,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            )
        }
    }
}
