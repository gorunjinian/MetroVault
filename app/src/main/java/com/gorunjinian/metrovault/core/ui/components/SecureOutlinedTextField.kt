package com.gorunjinian.metrovault.core.ui.components

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDataType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.PlatformImeOptions
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import com.gorunjinian.metrovault.R

/**
 * A wrapper around OutlinedTextField that completely disables:
 * - Autocorrect
 * - Predictive text / suggestions bar
 * - Autofill / password manager suggestions (fill and save)
 * - Keyboard learning from input
 *
 * This is critical for security in a Bitcoin wallet app - we don't want the system
 * keyboard to learn from or suggest passwords, seed phrases, or other sensitive data,
 * nor an external password manager to capture and store the typed password/passphrase.
 *
 * Uses multiple layers of protection:
 * 1. contentDataType = ContentDataType.None on the field's semantics node - opts the field
 *    out of the autofill framework so no fill/save session is ever started for it. This is
 *    the effective control on modern Compose, whose semantic-autofill path ignores the
 *    host View's importantForAutofill flag (see the note on secureModifier below).
 * 2. autoCorrectEnabled = false - Disables autocorrect
 * 3. PlatformImeOptions with Gboard incognito flags - Prevents keyboard learning
 * 4. KeyboardType.Password (when isPasswordField=true) - Completely hides suggestions bar
 *
 * @param isPasswordField Set to true for password/passphrase inputs to use Password keyboard type
 *        which completely hides the suggestions bar and password manager prompts
 */
@Composable
fun SecureOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    isPasswordField: Boolean = false,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors()
) {
    // Create secure keyboard options with incognito mode flags
    val secureKeyboardOptions = keyboardOptions.copy(
        // Disable autocorrect
        autoCorrectEnabled = false,
        // Disable auto-capitalization to prevent keyboard learning
        capitalization = KeyboardCapitalization.None,
        // For password fields, use Password keyboard type to completely hide suggestions
        keyboardType = if (isPasswordField) KeyboardType.Password else keyboardOptions.keyboardType,
        // Use platform IME options to enable incognito mode (no learning, no suggestions)
        // These are Gboard-specific flags that put the keyboard in "incognito" mode
        platformImeOptions = PlatformImeOptions(
            privateImeOptions = "nm,np," +
                    "com.google.android.inputmethod.latin.noMicrophoneKey," +
                    "com.google.android.inputmethod.latin.noLearning," +
                    "com.google.android.inputmethod.latin.noSuggestions," +
                    "com.google.android.inputmethod.latin.flagNoPersonalizedLearning"
        )
    )
    
    // Opt this field out of the autofill framework at the semantics level.
    //
    // Compose text fields register themselves for autofill by tagging their semantics node
    // with contentDataType = Text (plus onFillData, and ContentType.Password for password
    // fields). The platform AutofillManager is then notified on focus and on every value
    // change, which is what makes an external password manager (e.g. Proton Pass) pop the
    // "save password?" dialog for whatever the user typed here. Marking the node
    // contentDataType = None makes it report as non-autofillable
    // (AndroidAutofillManager.isAutofillable() short-circuits on None), so no autofill
    // session is ever started or committed for it — no fill suggestions, no save prompt.
    //
    // This override must land on the SAME layout node as the field's own semantics and win
    // the collapse. CoreTextField applies our incoming modifier as the OUTERMOST semantics
    // and its own CoreTextFieldSemanticsModifier inner; same-node peer collapse is first-wins
    // per key, so the outermost value (ours) wins. Note this replaces the old
    // SecurityUtils.disableAutofill()/AutofillManager.cancel() approach, which no longer works:
    // the new semantic-autofill notify path ignores the host View's importantForAutofill flag.
    val secureModifier = modifier.semantics { contentDataType = ContentDataType.None }
    
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = secureModifier,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        prefix = prefix,
        suffix = suffix,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = secureKeyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        enabled = enabled,
        readOnly = readOnly,
        colors = colors
    )
}

/**
 * A secure password field with an explicit, accessible show/hide control.
 *
 * Passwords remain hidden by default and visibility is local to this field. The state is
 * deliberately not saveable, so a recreated screen always returns to the hidden state.
 * Clearing the field also drops back to hidden.
 */
@Composable
fun SecurePasswordTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors()
) {
    var isPasswordVisible by remember { mutableStateOf(false) }

    LaunchedEffect(value.isEmpty()) {
        if (value.isEmpty()) isPasswordVisible = false
    }

    SecureOutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = {
            IconButton(
                onClick = { isPasswordVisible = !isPasswordVisible },
                enabled = enabled
            ) {
                Icon(
                    painter = painterResource(
                        if (isPasswordVisible) {
                            R.drawable.ic_visibility_off
                        } else {
                            R.drawable.ic_visibility
                        }
                    ),
                    contentDescription = if (isPasswordVisible) "Hide password" else "Show password"
                )
            }
        },
        supportingText = supportingText,
        isError = isError,
        visualTransformation = if (isPasswordVisible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        enabled = enabled,
        readOnly = readOnly,
        isPasswordField = true,
        colors = colors
    )
}

/**
 * TextFieldValue overload of SecureOutlinedTextField that supports text selection control.
 * This is useful for dialogs where you want to auto-select all text when the dialog opens.
 * 
 * Same security protections as the String-based version:
 * - Autocorrect disabled
 * - Predictive text disabled
 * - Autofill disabled
 * - Keyboard learning disabled
 * 
 * @param value TextFieldValue containing both text and selection state
 * @param onValueChange Callback when the TextFieldValue changes (including selection)
 */
@Composable
fun SecureOutlinedTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    isPasswordField: Boolean = false,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors()
) {
    // Create secure keyboard options with incognito mode flags
    val secureKeyboardOptions = keyboardOptions.copy(
        autoCorrectEnabled = false,
        capitalization = KeyboardCapitalization.None,
        keyboardType = if (isPasswordField) KeyboardType.Password else keyboardOptions.keyboardType,
        platformImeOptions = PlatformImeOptions(
            privateImeOptions = "nm,np," +
                    "com.google.android.inputmethod.latin.noMicrophoneKey," +
                    "com.google.android.inputmethod.latin.noLearning," +
                    "com.google.android.inputmethod.latin.noSuggestions," +
                    "com.google.android.inputmethod.latin.flagNoPersonalizedLearning"
        )
    )
    
    // Opt this field out of the autofill framework at the semantics level.
    // See the String overload above for the full rationale.
    val secureModifier = modifier.semantics { contentDataType = ContentDataType.None }
    
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = secureModifier,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        prefix = prefix,
        suffix = suffix,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = secureKeyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        enabled = enabled,
        readOnly = readOnly,
        colors = colors
    )
}
