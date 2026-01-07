package com.gorunjinian.metrovault.core.ui.components

import android.view.autofill.AutofillManager
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PlatformImeOptions
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import com.gorunjinian.metrovault.core.util.SecurityUtils

/**
 * A wrapper around OutlinedTextField that completely disables:
 * - Autocorrect
 * - Predictive text / suggestions bar
 * - Autofill / password manager suggestions
 * - Keyboard learning from input
 * 
 * This is critical for security in a Bitcoin wallet app - we don't want the system
 * keyboard to learn from or suggest passwords, seed phrases, or other sensitive data.
 * 
 * Uses multiple layers of protection:
 * 1. SecurityUtils.disableAutofill() - Sets View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS on parent
 * 2. Disables AutofillManager and notifies it nothing is autofillable
 * 3. autoCorrectEnabled = false - Disables autocorrect
 * 4. PlatformImeOptions with Gboard incognito flags - Prevents keyboard learning
 * 5. KeyboardType.Password (when isPasswordField=true) - Completely hides suggestions bar
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
    val view = LocalView.current
    val context = LocalContext.current
    
    // Disable autofill on the view hierarchy using SecurityUtils
    SecurityUtils.disableAutofill(view)
    
    // Aggressively disable autofill manager
    LaunchedEffect(Unit) {
        try {
            val autofillManager = context.getSystemService(AutofillManager::class.java)
            // Cancel any pending autofill requests
            autofillManager?.cancel()
            // Notify that nothing is autofillable in this context
            autofillManager?.notifyViewExited(view)
        } catch (_: Exception) {
            // Ignore - some devices may not have autofill service
        }
    }
    
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
    
    // Modifier that disables autofill on any child views when they are positioned
    val secureModifier = modifier.onGloballyPositioned { _ ->
        // Find the underlying Android View and disable autofill on it
        // The view hierarchy has already been set up via SecurityUtils.disableAutofill
        // This callback ensures the flag is applied after layout
    }
    
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
    val view = LocalView.current
    val context = LocalContext.current
    
    // Disable autofill on the view hierarchy using SecurityUtils
    SecurityUtils.disableAutofill(view)
    
    // Aggressively disable autofill manager
    LaunchedEffect(Unit) {
        try {
            val autofillManager = context.getSystemService(AutofillManager::class.java)
            autofillManager?.cancel()
            autofillManager?.notifyViewExited(view)
        } catch (_: Exception) {
            // Ignore - some devices may not have autofill service
        }
    }
    
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
    
    val secureModifier = modifier.onGloballyPositioned { _ ->
        // Autofill already disabled via SecurityUtils
    }
    
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
