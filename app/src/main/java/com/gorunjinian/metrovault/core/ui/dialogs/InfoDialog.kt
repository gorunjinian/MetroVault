package com.gorunjinian.metrovault.core.ui.dialogs

import androidx.annotation.DrawableRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource

/**
 * Informational dialog with a single button; the button and a dismiss outside the dialog
 * both call [onDismiss].
 */
@Composable
fun InfoDialog(
    title: String,
    onDismiss: () -> Unit,
    buttonLabel: String = "OK",
    @DrawableRes icon: Int? = null,
    text: @Composable () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = if (icon != null) {
            { Icon(painter = painterResource(icon), contentDescription = null) }
        } else {
            null
        },
        title = { Text(title) },
        text = text,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(buttonLabel)
            }
        }
    )
}
